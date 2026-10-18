package com.gg.spwaiplaylist

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.FileInputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(UnstableSpwWorkshopApi::class)
class MainPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    private lateinit var embeddingDb: EmbeddingDatabase
    private lateinit var remoteAi: RemoteAiClients
    private lateinit var debugLogFile: File
    private var debugLogEnabled = false
    private var debugLogWriter: BufferedWriter? = null
    private val logQueue = LinkedBlockingQueue<String>()
    private val logFlusherRunning = AtomicBoolean(false)
    private var logFlusherThread: Thread? = null
    private var currentSearchJob: kotlinx.coroutines.Job? = null
    private var pluginScope: CoroutineScope? = null
    @Volatile private var cachedTrackMap: Map<String, MediaItem>? = null

    private val awtListener = AWTEventListener { event ->
        if (event is KeyEvent && event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_ENTER) {
            onEnterPressed()
        }
    }

    private var isSyncing = false
    private val maxDebugLogBytes = 10L * 1024L * 1024L

    // -------------------------------------------------------------------------
    // Cached reflection objects
    // All nullable; resolved once in initReflection() or lazily on first use.
    // @Volatile ensures cross-thread visibility for lazy-resolved fields.
    // -------------------------------------------------------------------------

    // PiscesMediaItem
    @Volatile private var rfPiscesItemCtor: java.lang.reflect.Constructor<*>? = null

    // PlaybackController
    @Volatile private var rfPlaybackControllerInstanceField: java.lang.reflect.Field? = null
    @Volatile private var rfSetQueueMethod: java.lang.reflect.Method? = null      // lazy: needs controller runtime class
    @Volatile private var rfPlayMusicAtMethod: java.lang.reflect.Method? = null   // lazy: needs controller runtime class
    @Volatile private var rfPlaybackQueueStateMethod: java.lang.reflect.Method? = null

    // Vri + TagParser (lyrics)
    @Volatile private var rfVriCompanionField: java.lang.reflect.Field? = null
    @Volatile private var rfVriParseMethod: java.lang.reflect.Method? = null
    @Volatile private var rfTagParserInstanceField: java.lang.reflect.Field? = null
    @Volatile private var rfReadLyricsMethod: java.lang.reflect.Method? = null

    // Compose 1.19 search-box chain (lazy: resolved on first successful getSearchText call)
    @Volatile private var rfCwPanel: java.lang.reflect.Field? = null
    @Volatile private var rfCwpContainer: java.lang.reflect.Field? = null
    @Volatile private var rfCcMediator: java.lang.reflect.Field? = null
    @Volatile private var rfCsmGetTextInputService2: java.lang.reflect.Method? = null
    @Volatile private var cachedTextInputMediator: Any? = null
    @Volatile private var cachedTextInputService2: Any? = null
    @Volatile private var rfDtis2InputMethodSession: java.lang.reflect.Field? = null
    @Volatile private var rfInputSessionRequest: java.lang.reflect.Field? = null
    @Volatile private var rfRequestGetState: java.lang.reflect.Method? = null
    @Volatile private var rfTextEditorStateGetText: java.lang.reflect.Method? = null

    // -------------------------------------------------------------------------

    override fun start() {
        val configManager = WorkshopApi.manager.createConfigManager()
        val config = configManager.getConfig()
        val pluginDataDir = config.getConfigPath().toFile().parentFile
        val logFlag = config.get("debug.enable_file_log", false)
        debugLogEnabled = logFlag as Boolean
        debugLogFile = File(pluginDataDir, "logs/plugin-debug.log")
        if (debugLogEnabled) {
            pruneDebugLogIfNeeded()
            openDebugLogWriter()
        }

        logDebug("start(): pluginPath=${pluginContext.pluginPath}")
        logDebug("start(): pluginDataDir=$pluginDataDir")

        try {
            Class.forName("org.sqlite.JDBC")
            logDebug("start(): org.sqlite.JDBC loaded")
        } catch (t: Throwable) {
            logThrowable("start(): JDBC driver load failed", t)
            WorkshopApi.ui.toast("SQLite driver load failed", WorkshopApi.Ui.ToastType.Error)
            return
        }

        embeddingDb = EmbeddingDatabase(pluginDataDir)
        try {
            embeddingDb.open()
            logDebug("start(): sqlite opened, count=${embeddingDb.count()}")
        } catch (t: Throwable) {
            logThrowable("start(): sqlite open failed", t)
            WorkshopApi.ui.toast("嵌入数据库初始化失败", WorkshopApi.Ui.ToastType.Error)
            return
        }

        remoteAi = try {
            val settings = RemoteAiSettings.from(config)
            logDebug(
                "start(): remote AI config loaded, " +
                    "embeddingModel=${settings.embeddingModel}, chatModel=${settings.chatModel}, " +
                    "embeddingUrl=${settings.embeddingBaseUrl}, chatUrl=${settings.chatBaseUrl}, " +
                    "hasEmbeddingKey=${settings.embeddingApiKey.isNotBlank()}, " +
                    "hasChatKey=${settings.chatApiKey.isNotBlank()}"
            )
            RemoteAiClients(settings, ::logDebug)
        } catch (t: Throwable) {
            logThrowable("start(): remote AI config load failed", t)
            RemoteAiClients(RemoteAiSettings.empty(), ::logDebug)
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(awtListener, AWTEvent.KEY_EVENT_MASK)
        logDebug("start(): AWT listener registered")

        pluginScope?.cancel()
        pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)) {
            logDebug("start(): LIBRARY_READ permission is not granted; library features are unavailable")
            WorkshopApi.ui.toast("请在插件权限中授权“读取音乐库”后重启插件", WorkshopApi.Ui.ToastType.Warning)
        }
        initReflection()
        refreshTrackCache("start()")

        pluginScope!!.launch {
            delay(5000) // Wait for host to settle
            startBackgroundSync()
        }
    }

    /**
     * Resolves all reflection objects that can be found from class names alone.
     * Dynamic parts (methods on runtime-typed instances, Compose chain fields) are
     * resolved lazily on first use and stored in the same @Volatile fields.
     */
    private fun initReflection() {
        // PiscesMediaItem 6-arg constructor
        try {
            val cls = Class.forName("com.xuncorp.pisces.PiscesMediaItem")
            rfPiscesItemCtor = cls.getConstructor(
                String::class.java, String::class.java, String::class.java,
                String::class.java, String::class.java, String::class.java
            )
            logDebug("initReflection(): PiscesMediaItem ctor cached")
        } catch (t: Throwable) { logThrowable("initReflection(): PiscesMediaItem ctor", t) }

        // PlaybackController INSTANCE field
        try {
            val cls = Class.forName("com.xuncorp.voxzen.service.PlaybackController")
            rfPlaybackControllerInstanceField = cls.getField("INSTANCE")
            rfPlaybackQueueStateMethod = cls.getMethod("getPlaybackQueueState")
            logDebug("initReflection(): PlaybackController INSTANCE field cached")
        } catch (t: Throwable) { logThrowable("initReflection(): PlaybackController field", t) }

        // Vri companion object + parse(String) method
        try {
            val vriClass = Class.forName("com.xuncorp.spc.v.Vri")
            rfVriCompanionField = vriClass.getField("\u037F")
            val companion = rfVriCompanionField!!.get(null)
            rfVriParseMethod = companion.javaClass.getDeclaredMethod("\u0528", String::class.java)
                .also { it.isAccessible = true }
            logDebug("initReflection(): Vri parse method cached")
        } catch (t: Throwable) { logThrowable("initReflection(): Vri", t) }

        // TagParser INSTANCE + readLyrics method
        try {
            val tagParserClass = Class.forName("com.xuncorp.voxzen.tag.TagParser")
            rfTagParserInstanceField = tagParserClass.getField("INSTANCE")
            val vriClass = Class.forName("com.xuncorp.spc.v.Vri")
            rfReadLyricsMethod = tagParserClass.getDeclaredMethod("readLyrics-IoAF18A", vriClass)
                .also { it.isAccessible = true }
            logDebug("initReflection(): TagParser readLyrics method cached")
        } catch (t: Throwable) { logThrowable("initReflection(): TagParser", t) }
    }

    private suspend fun startBackgroundSync() {
        if (isSyncing) return
        isSyncing = true
        try {
            if (remoteAi.settings.embeddingApiKey.isBlank() || remoteAi.settings.chatApiKey.isBlank()) {
                logDebug("startBackgroundSync(): missing API keys, aborting sync")
                WorkshopApi.ui.toast("请先在插件设置中填写完整的 AI 密钥才能处理歌曲", WorkshopApi.Ui.ToastType.Warning)
                return
            }

            val tracks = getAllTracks()
            if (tracks.isNullOrEmpty()) {
                logDebug("startBackgroundSync(): no tracks found, skipping sync")
                return
            }
            refreshTrackCache("startBackgroundSync(): initial load", tracks)

            val existingIds = embeddingDb.getAllTrackIds()

            // Backfill metadata for existing records that were indexed before artist/title/album columns existed
            val needsBackfill = embeddingDb.getTrackIdsWithEmptyMetadata()
            if (needsBackfill.isNotEmpty()) {
                logDebug("startBackgroundSync(): backfilling metadata for ${needsBackfill.size} existing records")
                var backfilled = 0
                for (track in tracks) {
                    val id = track.id.takeIf { it.isNotBlank() } ?: continue
                    if (id !in needsBackfill) continue
                    val title = track.title
                    val artist = track.artist
                    val album = track.album
                    embeddingDb.updateMetadata(id, title, artist, album)
                    backfilled++
                }
                logDebug("startBackgroundSync(): backfilled $backfilled records")
            }

            val pendingTracks = tracks.filter { track ->
                track.id.isNotBlank() && track.id !in existingIds
            }

            if (pendingTracks.isEmpty()) {
                logDebug("startBackgroundSync(): all ${tracks.size} tracks already synced.")
                return
            }

            logDebug("startBackgroundSync(): found ${pendingTracks.size} new tracks to sync.")
            WorkshopApi.ui.toast("发现 ${pendingTracks.size} 首新歌，开始后台AI处理...", WorkshopApi.Ui.ToastType.Success)

            val processed = AtomicInteger(0)
            val total = pendingTracks.size
            val threadCount = remoteAi.settings.ragThreadCount
            val delayMs = remoteAi.settings.requestDelayMs

            if (threadCount <= 1) {
                for (track in pendingTracks) {
                    var success = false
                    while (!success) {
                        val result = seedFirstTrack(track, false)
                        if (result == SeedResult.SUCCESS) {
                            val current = processed.incrementAndGet()
                            WorkshopApi.ui.toast("AI 处理中: $current / $total", WorkshopApi.Ui.ToastType.Success)
                            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                            success = true
                        } else if (result == SeedResult.RATE_LIMITED) {
                            WorkshopApi.ui.toast("触发 API 频率限制，等待 1 分钟后重试...", WorkshopApi.Ui.ToastType.Warning)
                            logDebug("startBackgroundSync(): API rate limited, waiting 60s before retry")
                            kotlinx.coroutines.delay(60_000)
                        } else if (result == SeedResult.SKIPPED) {
                            success = true
                        } else {
                            logDebug("startBackgroundSync(): failed to sync track, skipping to next.")
                            success = true
                        }
                    }
                }
            } else {
                val index = AtomicInteger(0)
                coroutineScope {
                    val deferreds = (1..threadCount).map {
                        async(Dispatchers.IO) {
                            while (true) {
                                val i = index.getAndIncrement()
                                if (i >= pendingTracks.size) break
                                val track = pendingTracks[i]
                                var success = false
                                while (!success) {
                                    val result = seedFirstTrack(track, true)
                                    if (result == SeedResult.SUCCESS) {
                                        val current = processed.incrementAndGet()
                                        WorkshopApi.ui.toast("AI 处理中: $current / $total", WorkshopApi.Ui.ToastType.Success)
                                        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                                        success = true
                                    } else if (result == SeedResult.RATE_LIMITED) {
                                        WorkshopApi.ui.toast("触发 API 频率限制，等待 1 分钟后重试...", WorkshopApi.Ui.ToastType.Warning)
                                        logDebug("startBackgroundSync(): API rate limited, waiting 60s before retry")
                                        kotlinx.coroutines.delay(60_000)
                                    } else if (result == SeedResult.SKIPPED) {
                                        success = true
                                    } else {
                                        logDebug("startBackgroundSync(): failed to sync track, skipping to next.")
                                        success = true
                                    }
                                }
                            }
                        }
                    }
                    deferreds.awaitAll()
                }
            }

            refreshTrackCache("startBackgroundSync(): completed sync", tracks)
            if (processed.get() > 0) {
                WorkshopApi.ui.toast("AI 歌曲处理完成！共新增 ${processed.get()} 首", WorkshopApi.Ui.ToastType.Success)
            } else {
                logDebug("startBackgroundSync(): no new tracks processed; completion toast suppressed")
            }

        } catch (t: Throwable) {
            logThrowable("startBackgroundSync(): failed", t)
            WorkshopApi.ui.toast("AI 歌曲处理发生异常", WorkshopApi.Ui.ToastType.Error)
        } finally {
            isSyncing = false
        }
    }

    override fun stop() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener)
        logDebug("stop(): AWT listener removed")
        currentSearchJob?.cancel()
        currentSearchJob = null
        pluginScope?.cancel()
        pluginScope = null
        if (::embeddingDb.isInitialized) {
            try {
                embeddingDb.close()
                logDebug("stop(): sqlite closed")
            } catch (t: Throwable) {
                logThrowable("stop(): sqlite close failed", t)
            }
        }
        closeDebugLogWriter()
    }

    override fun delete() = Unit
    override fun update() = Unit

    private fun onEnterPressed() {
        val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        if (window == null) {
            logDebug("onEnterPressed(): no active window")
            return
        }

        val text = getSearchText(window)?.trim()
        if (text.isNullOrBlank()) {
            logDebug("onEnterPressed(): getSearchText returned null or blank")
            return
        }

        logDebug("onEnterPressed(): captured text='$text'")
        WorkshopApi.ui.toast("🎵 正在探索: $text ...", WorkshopApi.Ui.ToastType.Success)

        currentSearchJob?.cancel()
        val scope = pluginScope ?: return
        currentSearchJob = scope.launch {
            try {
                if (remoteAi.settings.embeddingApiKey.isBlank()) {
                    logDebug("onEnterPressed(): missing Embedding API key, aborting")
                    WorkshopApi.ui.toast("请先配置向量模型 (Embedding) 的 API 密钥", WorkshopApi.Ui.ToastType.Warning)
                    return@launch
                }

                val trackMap = cachedTrackMap
                if (trackMap.isNullOrEmpty()) {
                    logDebug("onEnterPressed(): track cache not ready")
                    WorkshopApi.ui.toast("曲库缓存还在初始化，请稍后再试", WorkshopApi.Ui.ToastType.Warning)
                    return@launch
                }

                val queryText = if (remoteAi.settings.enableQueryExpansion && shouldExpandQuery(text)) {
                    if (remoteAi.settings.chatApiKey.isBlank()) {
                        logDebug("onEnterPressed(): query expansion enabled but missing Chat API key")
                        WorkshopApi.ui.toast("已开启查询扩展，但大语言模型 API 密钥未配置", WorkshopApi.Ui.ToastType.Warning)
                        return@launch
                    }
                    val expanded = remoteAi.expandQuery(text)
                    logDebug("onEnterPressed(): query expanded from '$text' to '$expanded'")
                    expanded
                } else {
                    if (remoteAi.settings.enableQueryExpansion) {
                        logDebug("onEnterPressed(): query expansion skipped for constrained query='$text'")
                    }
                    text
                }

                val queryVector = remoteAi.createEmbedding(queryText)
                val matches = embeddingDb.findTopMatches(queryVector, queryText, remoteAi.settings.maxPlaybackItems)
                logDebug("onEnterPressed(): query matched ${matches.size} tracks")
                matches.take(10).forEachIndexed { index, match ->
                    logDebug("onEnterPressed(): match#$index trackId=${match.first} score=${match.second}")
                }
                if (matches.size > 10) {
                    logDebug("onEnterPressed(): ${matches.size - 10} lower-ranked matches omitted from debug log")
                }

                if (matches.isNotEmpty()) {
                    val playbackItems = matches.mapNotNull { match ->
                        val trackId = match.first
                        val track = trackMap[trackId]
                        if (track == null) {
                            logDebug("onEnterPressed(): trackId=$trackId not found in trackMap, skipping")
                            null
                        } else {
                            buildPlaybackItem(track)
                        }
                    }
                    if (playbackItems.isNotEmpty()) {
                        updatePlaybackQueue(playbackItems)
                        logDebug("onEnterPressed(): playback queue replaced with ${playbackItems.size} matched tracks")
                        WorkshopApi.ui.toast("✨ 已为您生成「$text」的专属播放列表", WorkshopApi.Ui.ToastType.Success)
                    } else {
                        WorkshopApi.ui.toast("抱歉，匹配到的歌曲无法播放", WorkshopApi.Ui.ToastType.Warning)
                    }
                } else {
                    WorkshopApi.ui.toast("抱歉，曲库中未找到符合条件的歌曲", WorkshopApi.Ui.ToastType.Warning)
                }
            } catch (t: Throwable) {
                logThrowable("onEnterPressed(): unexpected failure", t)
                if (t is IllegalStateException && (t.message?.contains("429") == true || t.message?.contains("RPM limit exceeded") == true || t.message?.contains("403") == true)) {
                    WorkshopApi.ui.toast("搜索失败：触发了 AI 平台的 API 频率限制，请稍后再试", WorkshopApi.Ui.ToastType.Warning)
                } else {
                    WorkshopApi.ui.toast("搜索失败，请查看日志", WorkshopApi.Ui.ToastType.Error)
                }
            }
        }
    }

    private fun shouldExpandQuery(query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isBlank()) return false

        val lower = normalized.lowercase()
        val broadSignals = listOf(
            "适合",
            "推荐",
            "想听",
            "帮我找",
            "类似",
            "氛围",
            "背景音乐",
            "歌单",
            "听歌",
            "播放"
        )
        return broadSignals.any { lower.contains(it) }
    }



    private suspend fun getAllTracks(): List<MediaItem>? {
        return try {
            if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.LIBRARY_READ)) {
                logDebug("getAllTracks(): LIBRARY_READ permission is not granted")
                return null
            }
            WorkshopApi.library.getAllTracks().toCompletableFuture().get().also {
                logDebug("getAllTracks(): WorkshopApi.library returned ${it.size} tracks")
            }
        } catch (t: Throwable) {
            logThrowable("getAllTracks(): failure", t)
            null
        }
    }

    private fun refreshTrackCache(reason: String, tracks: List<MediaItem>? = null) {
        if (tracks != null) {
            try {
                cachedTrackMap = loadTrackMapFromTracks(tracks)
                logDebug("refreshTrackCache($reason): cached ${tracks.size} tracks, mapSize=${cachedTrackMap?.size ?: -1}")
            } catch (t: Throwable) {
                logThrowable("refreshTrackCache($reason): failure", t)
            }
            return
        }

        val scope = pluginScope ?: return
        scope.launch {
            try {
                val sourceTracks = getAllTracks()
                if (sourceTracks.isNullOrEmpty()) {
                    logDebug("refreshTrackCache($reason): no tracks loaded")
                    return@launch
                }
                cachedTrackMap = loadTrackMapFromTracks(sourceTracks)
                logDebug("refreshTrackCache($reason): cached ${sourceTracks.size} tracks, mapSize=${cachedTrackMap?.size ?: -1}")
            } catch (t: Throwable) {
                logThrowable("refreshTrackCache($reason): failure", t)
            }
        }
    }

    private fun getCachedTrackMap(): Map<String, MediaItem>? {
        val map = cachedTrackMap
        if (map != null) return map
        return null
    }

    private fun loadTrackMapFromTracks(tracks: List<MediaItem>): Map<String, MediaItem> {
        return tracks.mapNotNull { track ->
            val id = track.id
            if (id.isBlank()) null else id to track
        }.toMap()
    }

    private enum class SeedResult {
        SUCCESS,
        SKIPPED,
        RATE_LIMITED,
        ERROR
    }

    private fun seedFirstTrack(track: MediaItem, checkExisting: Boolean): SeedResult {
        return try {
            val trackId = track.id.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Track id missing")
            val title = track.title
            val artist = track.artist
            val album = track.album
            val contentHash = buildTrackContentHash(track)
            val existingById = embeddingDb.getById(trackId)
            if (existingById != null) {
                logDebug("seedFirstTrack(): skipped existing trackId=$trackId")
                return SeedResult.SKIPPED
            }
            val existingByHash = if (contentHash.isNotBlank()) embeddingDb.getByHash(contentHash) else null
            if (existingByHash != null) {
                embeddingDb.rekey(existingByHash.trackId, trackId, contentHash, title, artist, album)
                logDebug("seedFirstTrack(): rekeyed by hash oldTrackId=${existingByHash.trackId} newTrackId=$trackId")
                return SeedResult.SKIPPED
            }
            val existingByMetadata = embeddingDb.getByMetadata(title, artist, album)
            if (existingByMetadata != null) {
                if (contentHash.isNotBlank() && existingByMetadata.contentHash.isBlank()) {
                    embeddingDb.rekey(existingByMetadata.trackId, trackId, contentHash, title, artist, album)
                    logDebug("seedFirstTrack(): rekeyed by metadata oldTrackId=${existingByMetadata.trackId} newTrackId=$trackId")
                    return SeedResult.SKIPPED
                }
                logDebug("seedFirstTrack(): skipped existing metadata title='$title' artist='$artist' album='$album'")
                return SeedResult.SKIPPED
            }
            val sourceText = buildTrackSourceText(track)
            logDebug("seedFirstTrack(): trackId=$trackId sourceTextLength=${sourceText.length}")

            val description = remoteAi.generateTrackDescription(sourceText)
            logDebug("seedFirstTrack(): descriptionLength=${description.length}")

            val embeddingInput = buildEmbeddingInput(sourceText, description)
            val vector = remoteAi.createEmbedding(embeddingInput)
            embeddingDb.upsert(trackId, embeddingInput, vector, contentHash, title, artist, album)
            logDebug("seedFirstTrack(): upserted trackId=$trackId vectorSize=${vector.size}")
            SeedResult.SUCCESS
        } catch (t: Throwable) {
            logThrowable("seedFirstTrack(): failure", t)
            if (t is IllegalStateException && (t.message?.contains("429") == true || t.message?.contains("RPM limit exceeded") == true || t.message?.contains("403") == true)) {
                SeedResult.RATE_LIMITED
            } else {
                SeedResult.ERROR
            }
        }
    }

    private fun buildTrackSourceText(track: MediaItem): String {
        val title = track.title
        val artist = track.artist
        val album = track.album
        val year = track.year.toString()
        val lyrics = normalizeLyrics(readTrackLyrics(track.path).orEmpty())

        logDebug("buildTrackSourceText(): lyricsIncluded=${lyrics.isNotBlank()} lyricsLength=${lyrics.length}")

        return buildString {
            appendLine("歌名：$title")
            appendLine("歌手：$artist")
            appendLine("专辑：$album")
            appendLine("年份：$year")
            if (lyrics.isNotBlank()) {
                appendLine("歌词：")
                appendLine(lyrics.trim())
            }
        }.trimEnd()
    }

    private fun buildTrackContentHash(track: MediaItem): String {
        val path = track.path
        if (path.isBlank()) return ""
        val normalizedPath = fileUriToOsPath(path)
        return try {
            val file = File(normalizedPath)
            if (!file.isFile) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            logThrowable("buildTrackContentHash(): failure for path='$normalizedPath'", t)
            ""
        }
    }


    /**
     * Combines the raw track metadata text with the AI-generated description
     * into a single string used as the embedding input.
     */
    private fun buildEmbeddingInput(sourceText: String, description: String): String {
        return if (description.isBlank()) {
            sourceText
        } else {
            "$sourceText\n---\nAI描述：$description"
        }
    }

    /**
     * Constructs a PiscesMediaItem via the cached constructor.
     */
    private fun buildPlaybackItem(track: MediaItem): Any? {
        return try {
            val id = track.id
            val title = track.title
            val artist = track.artist
            val album = track.album
            val albumArtist = track.albumArtist
            val rawPath = track.path
            // SPW stores paths as file:/// URIs, but PiscesMediaItem expects OS paths.
            // Convert: "file:///D:/Music/x.flac" → "D:\Music\x.flac" (Windows)
            val path = fileUriToOsPath(rawPath)
            if (id.isBlank() || path.isBlank()) {
                logDebug("buildPlaybackItem(): missing id or path for trackId=$id")
                return null
            }
            logDebug("buildPlaybackItem(): id=$id title='$title' artist='$artist' path='$path'")
            val ctor = rfPiscesItemCtor ?: run {
                logDebug("buildPlaybackItem(): PiscesMediaItem ctor not cached")
                return null
            }
            ctor.newInstance(id, title, artist, album, albumArtist, path)
        } catch (t: Throwable) {
            logThrowable("buildPlaybackItem(): failure", t)
            null
        }
    }

    /**
     * Converts a file:/// URI to an OS path, matching SPW's internal conversion.
     * "file:///D:/Music/x.flac" → "D:\Music\x.flac" on Windows
     */
    private fun fileUriToOsPath(uri: String): String {
        if (uri.startsWith("file:///")) {
            val stripped = uri.removePrefix("file:///")
            return if (System.getProperty("os.name")?.contains("win", ignoreCase = true) == true) {
                stripped.replace('/', '\\')
            } else {
                "/$stripped"
            }
        }
        if (uri.startsWith("file://")) {
            val stripped = uri.removePrefix("file://")
            return if (System.getProperty("os.name")?.contains("win", ignoreCase = true) == true) {
                "\\\\" + stripped.replace('/', '\\')
            } else {
                "/$stripped"
            }
        }
        return uri
    }

    /**
     * Clears the current playback queue and replaces it with the matched items,
     * then starts playback from the first item.
     * setPlaybackQueue and playMusicAt methods are resolved lazily from the controller instance.
     */
    private suspend fun updatePlaybackQueue(items: List<Any>) {
        if (items.isEmpty()) return
        val instanceField = rfPlaybackControllerInstanceField ?: run {
            logDebug("updatePlaybackQueue(): PlaybackController instance field not cached")
            return
        }
        val controller = instanceField.get(null)
            ?: throw IllegalStateException("PlaybackController.INSTANCE is null")

        // setPlaybackQueue sends a command to the host channel. Observe its public StateFlow
        // instead of imposing a fixed 500 ms delay on every search.
        val setQueueMethod = rfSetQueueMethod ?: controller.javaClass.getMethod(
            "setPlaybackQueue", List::class.java, Int::class.javaPrimitiveType
        ).also { rfSetQueueMethod = it }
        val queueStateFlow = rfPlaybackQueueStateMethod?.invoke(controller) as? StateFlow<*>
        val stateBeforeSet = queueStateFlow?.value

        setQueueMethod.invoke(controller, items, 0)
        if (queueStateFlow != null && stateBeforeSet != null) {
            val updated = withTimeoutOrNull(800) {
                while (queueStateFlow.value === stateBeforeSet) delay(10)
                true
            } ?: false
            logDebug("updatePlaybackQueue(): setQueue(${items.size}) stateUpdated=$updated")
            if (!updated) {
                // Preserve correctness on a host version that does not replace the state object.
                delay(150)
            }
        } else {
            logDebug("updatePlaybackQueue(): StateFlow unavailable, using conservative wait")
            delay(150)
        }

        val playMusicAtMethod = rfPlayMusicAtMethod ?: controller.javaClass.getMethod(
            "playMusicAt", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        ).also { rfPlayMusicAtMethod = it }

        playMusicAtMethod.invoke(controller, 0, false)
        logDebug("updatePlaybackQueue(): playMusicAt(0) invoked")
    }

    private fun normalizeLyrics(rawLyrics: String): String {
        return rawLyrics
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                when {
                    line.startsWith("[by:", ignoreCase = true) -> null
                    line.startsWith("[ti:", ignoreCase = true) -> null
                    line.startsWith("[ar:", ignoreCase = true) -> null
                    line.startsWith("[al:", ignoreCase = true) -> null
                    line.startsWith("[offset:", ignoreCase = true) -> null
                    line.startsWith("作词") -> null
                    line.startsWith("作曲") -> null
                    line.startsWith("编曲") -> null
                    else -> line
                        .replace(Regex("^(?:\\[\\d{2}:\\d{2}(?:\\.\\d{2,3})?\\])+"), "")
                        .trim()
                        .takeIf { it.isNotBlank() }
                }
            }
            .joinToString("\n")
    }

    private fun readTrackLyrics(path: String): String? {
        if (path.isBlank()) {
            logDebug("readTrackLyrics(): blank path")
            return null
        }
        return try {
            val companionField = rfVriCompanionField ?: run {
                logDebug("readTrackLyrics(): Vri companion field not cached")
                return null
            }
            val parseMethod = rfVriParseMethod ?: run {
                logDebug("readTrackLyrics(): Vri parse method not cached")
                return null
            }
            val tagParserField = rfTagParserInstanceField ?: run {
                logDebug("readTrackLyrics(): TagParser instance field not cached")
                return null
            }
            val readLyricsMethod = rfReadLyricsMethod ?: run {
                logDebug("readTrackLyrics(): readLyrics method not cached")
                return null
            }

            val companion = companionField.get(null)
            val vri = parseMethod.invoke(companion, path) ?: run {
                logDebug("readTrackLyrics(): Vri parse returned null for path='$path'")
                return null
            }
            val tagParser = tagParserField.get(null)
            val result = readLyricsMethod.invoke(tagParser, vri)
            val lyrics = when (result) {
                is String -> result.trim().takeIf { it.isNotBlank() }
                else -> result?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }
            logDebug(
                "readTrackLyrics(): path='$path' resultType=${result?.javaClass?.name} lyricsLength=${lyrics?.length ?: 0}"
            )
            lyrics
        } catch (t: Throwable) {
            logThrowable("readTrackLyrics(): failure", t)
            null
        }
    }

    /**
     * Reads the search box text from the active Compose window.
     *
     * SPW 1.19 uses the new Compose text-input service for TextFieldState. The
     * names below are the actual names emitted by the binary, not JADX's Kotlin
     * metadata aliases. This intentionally targets only the 1.19.0 service.
     */
    private fun getSearchText(window: java.awt.Window): String? {
        val panel = cachedGetField(window, "composePanel", "ComposeWindow.composePanel", { rfCwPanel }, { rfCwPanel = it })
            ?: return null
        val container = cachedGetField(panel, "_composeContainer", "ComposeWindowPanel._composeContainer", { rfCwpContainer }, { rfCwpContainer = it })
            ?: return null
        val mediator = cachedGetField(container, "mediator", "ComposeContainer.mediator", { rfCcMediator }, { rfCcMediator = it })
            ?: return null

        return readNewTextInputState(mediator)
    }

    /**
     * Reads Compose 1.19's active TextEditorState through the actual runtime
     * chain used by DesktopTextInputService2. This is the path used by SPW's
     * ScreenSearchBar(TextFieldState).
     */
    private fun readNewTextInputState(mediator: Any): String? {
        return try {
            val textInputService2 = if (cachedTextInputMediator === mediator) {
                cachedTextInputService2
            } else {
                val getTextInputService2 = rfCsmGetTextInputService2 ?: mediator.javaClass
                    .getDeclaredMethod("getTextInputService2")
                    .apply { isAccessible = true }
                    .also { rfCsmGetTextInputService2 = it }
                getTextInputService2.invoke(mediator).also {
                    cachedTextInputMediator = mediator
                    cachedTextInputService2 = it
                }
            }
                ?: run {
                    logDebug("readNewTextInputState(): ComposeSceneMediator.getTextInputService2 returned null")
                    return null
                }
            val inputMethodSession = cachedGetField(
                textInputService2,
                "inputMethodSession",
                "DesktopTextInputService2.inputMethodSession",
                { rfDtis2InputMethodSession },
                { rfDtis2InputMethodSession = it }
            ) ?: run {
                logDebug("readNewTextInputState(): inputMethodSession is null")
                return null
            }
            val request = cachedGetField(
                inputMethodSession,
                "request",
                "InputMethodSession.request",
                { rfInputSessionRequest },
                { rfInputSessionRequest = it }
            ) ?: run {
                logDebug("readNewTextInputState(): request is null")
                return null
            }
            val getState = rfRequestGetState ?: request.javaClass
                .getMethod("getState")
                .apply { isAccessible = true }
                .also { rfRequestGetState = it }
            val state = getState.invoke(request) ?: run {
                logDebug("readNewTextInputState(): PlatformTextInputMethodRequest.getState returned null")
                return null
            }
            val getText = rfTextEditorStateGetText ?: state.javaClass
                .getMethod("getText")
                .apply { isAccessible = true }
                .also { rfTextEditorStateGetText = it }
            val text = (getText.invoke(state) as? String)?.takeIf { it.isNotBlank() }
            logDebug(
                "readNewTextInputState(): stateClass=${state.javaClass.name}, textLength=${text?.length ?: 0}"
            )
            text
        } catch (t: Throwable) {
            logThrowable("readNewTextInputState(): failed", t)
            null
        }
    }

    /**
     * Reads a field from obj, using a cached Field reference when available.
     * On first call, walks the class hierarchy to find the field, then stores it via [setCache].
     */
    private fun cachedGetField(
        obj: Any,
        name: String,
        label: String,
        getCache: () -> java.lang.reflect.Field?,
        setCache: (java.lang.reflect.Field) -> Unit
    ): Any? {
        val cached = getCache()
        if (cached != null) {
            return try {
                val value = cached.get(obj)
                if (debugLogEnabled) {
                    logDebug("cachedGetField(): $label cache hit in ${cached.declaringClass.name}, valueClass=${value?.javaClass?.name}")
                }
                value
            } catch (t: Throwable) {
                logThrowable("cachedGetField(): $label get failed", t)
                null
            }
        }
        // Not yet cached — walk hierarchy to find and cache the field
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val field = c.getDeclaredField(name)
                field.isAccessible = true
                val value = field.get(obj)
                setCache(field)
                if (debugLogEnabled) {
                    logDebug("cachedGetField(): $label cached from ${c.name}, valueClass=${value?.javaClass?.name}")
                }
                return value
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (t: Throwable) {
                logThrowable("cachedGetField(): $label failed in ${c.name}", t)
                return null
            }
        }
        if (debugLogEnabled) {
            logDebug("cachedGetField(): $label not found from ${obj.javaClass.name}")
        }
        return null
    }

    private fun logThrowable(message: String, t: Throwable) {
        logDebug("$message: ${t::class.java.name}: ${t.message}")
        val writer = StringWriter()
        t.printStackTrace(PrintWriter(writer))
        writer.toString().lineSequence().forEach { line ->
            logDebug("  $line")
        }
    }

    private fun openDebugLogWriter() {
        if (debugLogWriter != null) return
        debugLogFile.parentFile?.mkdirs()
        debugLogWriter = BufferedWriter(FileWriter(debugLogFile, true))
        if (logFlusherRunning.compareAndSet(false, true)) {
            logFlusherThread = Thread {
                while (logFlusherRunning.get() || logQueue.isNotEmpty()) {
                    try {
                        val first = logQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                        val writer = debugLogWriter ?: continue
                        writer.write(first)
                        writer.newLine()
                        var drained = 0
                        while (drained < 127) {
                            val next = logQueue.poll() ?: break
                            writer.write(next)
                            writer.newLine()
                            drained++
                        }
                        writer.flush()
                    } catch (_: InterruptedException) {
                        // exit check happens at loop head
                    } catch (t: Throwable) {
                        runCatching {
                            debugLogWriter?.flush()
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "spw-debug-log-flusher"
                start()
            }
        }
    }

    private fun closeDebugLogWriter() {
        logFlusherRunning.set(false)
        logFlusherThread?.join(1000)
        logFlusherThread = null
        runCatching {
            debugLogWriter?.flush()
            debugLogWriter?.close()
        }
        debugLogWriter = null
    }

    private fun pruneDebugLogIfNeeded() {
        if (!debugLogFile.exists()) return
        if (debugLogFile.length() <= maxDebugLogBytes) return
        runCatching { debugLogFile.delete() }
    }

    private fun logDebug(message: String) {
        if (!debugLogEnabled) return
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        logQueue.offer("[$timestamp] $message")
        if (debugLogWriter == null) {
            openDebugLogWriter()
        }
    }

}
