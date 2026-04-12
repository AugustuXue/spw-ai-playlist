package com.gg.spwaiplaylist

import java.io.File
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.math.sqrt

/**
 * Manages the SQLite database for storing song embeddings.
 *
 * Schema:
 *   track_id    TEXT PRIMARY KEY  -- UUID from SPW's Track entity
 *   source_text TEXT NOT NULL     -- Text used to generate embedding (editable by user)
 *   vector      BLOB NOT NULL     -- float[1536] serialized as bytes
 *   updated_at  INTEGER NOT NULL  -- Unix timestamp for incremental updates
 */
class EmbeddingDatabase(private val dataDir: File) {

    private val dbFile: File = File(dataDir, "embeddings.db")
    private var connection: Connection? = null

    companion object {
        private const val CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS songs_embedding (
                track_id    TEXT PRIMARY KEY,
                source_text TEXT NOT NULL,
                vector      BLOB NOT NULL,
                updated_at  INTEGER NOT NULL,
                content_hash TEXT,
                title       TEXT DEFAULT '',
                artist      TEXT DEFAULT '',
                album       TEXT DEFAULT ''
            );
        """

        private const val INSERT_SQL = """
            INSERT OR REPLACE INTO songs_embedding (track_id, source_text, vector, updated_at, content_hash, title, artist, album)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """

        private const val SELECT_BY_ID_SQL = """
            SELECT track_id, source_text, vector, updated_at, content_hash, title, artist, album
            FROM songs_embedding WHERE track_id = ?
        """

        private const val SELECT_BY_HASH_SQL = """
            SELECT track_id, source_text, vector, updated_at, content_hash, title, artist, album
            FROM songs_embedding WHERE content_hash = ?
        """

        private const val SELECT_BY_METADATA_SQL = """
            SELECT track_id, source_text, vector, updated_at, content_hash, title, artist, album
            FROM songs_embedding WHERE title = ? AND artist = ? AND album = ?
        """

        private const val SELECT_LIMIT_SQL = """
            SELECT track_id, source_text, vector, updated_at, content_hash, title, artist, album
            FROM songs_embedding ORDER BY updated_at DESC LIMIT ?
        """

        private const val DELETE_SQL = """
            DELETE FROM songs_embedding WHERE track_id = ?
        """

        private const val REKEY_SQL = """
            UPDATE songs_embedding
            SET track_id = ?, content_hash = ?, title = ?, artist = ?, album = ?
            WHERE track_id = ?
        """

        private const val COUNT_SQL = """
            SELECT COUNT(*) FROM songs_embedding
        """
    }

    /**
     * Opens the database connection and creates the table if needed.
     * Must be called before any other operations.
     */
    @Synchronized
    @Throws(SQLException::class)
    fun open() {
        if (connection != null) return

        dataDir.mkdirs()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        connection = DriverManager.getConnection(url).apply {
            createStatement().use {
                it.execute("PRAGMA journal_mode=WAL;")
                it.execute(CREATE_TABLE_SQL)
            }
        }
        migrateIfNeeded()
    }

    /**
     * Adds title/artist/album columns to an existing database that was created
     * before these columns existed. Safe to call on a new database (columns already present).
     */
    @Synchronized
    private fun migrateIfNeeded() {
        val conn = connection ?: return
        val existingColumns = mutableSetOf<String>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(songs_embedding)").use { rs ->
                while (rs.next()) existingColumns.add(rs.getString("name"))
            }
        }
        listOf("content_hash", "title", "artist", "album").forEach { col ->
            if (col !in existingColumns) {
                conn.createStatement().use { it.execute("ALTER TABLE songs_embedding ADD COLUMN $col TEXT DEFAULT ''") }
            }
        }
    }

    /**
     * Closes the database connection.
     */
    @Synchronized
    fun close() {
        connection?.close()
        connection = null
    }

    /**
     * Inserts or replaces an embedding record.
     */
    @Throws(SQLException::class)
    @Synchronized
    fun upsert(
        trackId: String,
        sourceText: String,
        vector: FloatArray,
        contentHash: String = "",
        title: String = "",
        artist: String = "",
        album: String = "",
        updatedAt: Long = System.currentTimeMillis()
    ) {
        connection?.prepareStatement(INSERT_SQL)?.use { stmt ->
            stmt.setString(1, trackId)
            stmt.setString(2, sourceText)
            stmt.setBytes(3, floatArrayToBytes(vector))
            stmt.setLong(4, updatedAt)
            stmt.setString(5, contentHash)
            stmt.setString(6, title)
            stmt.setString(7, artist)
            stmt.setString(8, album)
            stmt.executeUpdate()
        } ?: throw SQLException("Database not open")
    }

    /**
     * Retrieves a single record by track ID.
     */
    @Throws(SQLException::class)
    fun getById(trackId: String): SongRecord? {
        connection?.prepareStatement(SELECT_BY_ID_SQL)?.use { stmt ->
            stmt.setString(1, trackId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return readSongRecord(rs)
                }
            }
        }
        return null
    }

    /**
     * Retrieves a single record by content hash.
     */
    @Throws(SQLException::class)
    fun getByHash(contentHash: String): SongRecord? {
        if (contentHash.isBlank()) return null
        connection?.prepareStatement(SELECT_BY_HASH_SQL)?.use { stmt ->
            stmt.setString(1, contentHash)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return readSongRecord(rs)
                }
            }
        }
        return null
    }

    /**
     * Retrieves a single record by title, artist, and album.
     */
    @Throws(SQLException::class)
    fun getByMetadata(title: String, artist: String, album: String): SongRecord? {
        if (title.isBlank() || artist.isBlank() || album.isBlank()) return null
        connection?.prepareStatement(SELECT_BY_METADATA_SQL)?.use { stmt ->
            stmt.setString(1, title)
            stmt.setString(2, artist)
            stmt.setString(3, album)
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    return readSongRecord(rs)
                }
            }
        }
        return null
    }

    /**
     * Updates the track ID and content hash for an existing record.
     */
    @Throws(SQLException::class)
    @Synchronized
    fun rekey(existingTrackId: String, newTrackId: String, contentHash: String, title: String, artist: String, album: String) {
        connection?.prepareStatement(REKEY_SQL)?.use { stmt ->
            stmt.setString(1, newTrackId)
            stmt.setString(2, contentHash)
            stmt.setString(3, title)
            stmt.setString(4, artist)
            stmt.setString(5, album)
            stmt.setString(6, existingTrackId)
            stmt.executeUpdate()
        } ?: throw SQLException("Database not open")
    }

    /**
     * Deletes a record by track ID.
     */
    @Throws(SQLException::class)
    fun delete(trackId: String) {
        connection?.prepareStatement(DELETE_SQL)?.use { stmt ->
            stmt.setString(1, trackId)
            stmt.executeUpdate()
        }
    }

    /**
     * Retrieves the most recently updated records.
     */
    @Throws(SQLException::class)
    fun getLatestRecords(limit: Int): List<SongRecord> {
        val result = mutableListOf<SongRecord>()
        connection?.prepareStatement(SELECT_LIMIT_SQL)?.use { stmt ->
            stmt.setInt(1, limit)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result += readSongRecord(rs)
                }
            }
        }
        return result
    }

    /**
     * Calculates cosine similarity between two vectors.
     */
    fun cosineSimilarity(left: FloatArray, right: FloatArray): Double {
        val size = minOf(left.size, right.size)
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (i in 0 until size) {
            val l = left[i].toDouble()
            val r = right[i].toDouble()
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }

    /**
     * Finds the most similar records using a hybrid score:
     *   final_score = cosine_similarity + metadata_bonus
     *
     * metadata_bonus rewards exact/partial matches against artist, title, album so that
     * queries like "周杰伦" (artist name) reliably surface that artist's songs.
     */
    fun findTopMatches(queryVector: FloatArray, queryText: String, limit: Int): List<Pair<String, Double>> {
        val queryLower = queryText.trim().lowercase()
        val result = mutableListOf<Pair<String, Double>>()
        connection?.prepareStatement("SELECT track_id, vector, title, artist, album FROM songs_embedding")?.use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val trackId = rs.getString("track_id")
                    val vector = bytesToFloatArray(rs.getBytes("vector"))
                    val title = rs.getString("title")?.lowercase().orEmpty()
                    val artist = rs.getString("artist")?.lowercase().orEmpty()
                    val album = rs.getString("album")?.lowercase().orEmpty()
                    val cosine = cosineSimilarity(queryVector, vector)
                    val bonus = computeMetadataBonus(queryLower, title, artist, album)
                    val score = cosine + bonus
                    if (score >= 0.25) {
                        result.add(trackId to score)
                    }
                }
            }
        }
        return result.sortedByDescending { it.second }.take(limit)
    }

    /**
     * Computes an additive score bonus based on exact/partial matches between
     * the query and song metadata fields.
     *
     * Bonuses are chosen to be large enough to reliably promote exact-match
     * results above semantically similar but wrong-artist results, while still
     * allowing cosine similarity to determine ordering within a matching artist.
     */
    private fun computeMetadataBonus(query: String, title: String, artist: String, album: String): Double {
        if (query.isBlank()) return 0.0
        var bonus = 0.0
        // Artist: exact match is the strongest signal (user typed the artist's full name)
        if (artist == query) bonus += 0.5
        else if (artist.contains(query) || query.contains(artist)) bonus += 0.35
        // Title: direct song name lookup
        if (title == query) bonus += 0.4
        else if (title.contains(query)) bonus += 0.2
        // Album: weaker signal
        if (album.contains(query)) bonus += 0.1
        return bonus
    }

    /**
     * Retrieves all records.
     */
    @Throws(SQLException::class)
    fun getAllRecords(): List<SongRecord> {
        val result = mutableListOf<SongRecord>()
        connection?.prepareStatement("SELECT track_id, source_text, vector, updated_at, content_hash, title, artist, album FROM songs_embedding")?.use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result += readSongRecord(rs)
                }
            }
        }
        return result
    }

    /**
     * Updates only the title/artist/album columns for an existing record.
     * Used to backfill metadata into rows that were inserted before these columns existed.
     */
    @Synchronized
    fun updateMetadata(trackId: String, title: String, artist: String, album: String) {
        connection?.prepareStatement(
            "UPDATE songs_embedding SET title = ?, artist = ?, album = ? WHERE track_id = ?"
        )?.use { stmt ->
            stmt.setString(1, title)
            stmt.setString(2, artist)
            stmt.setString(3, album)
            stmt.setString(4, trackId)
            stmt.executeUpdate()
        }
    }

    /**
     * Returns track IDs whose artist column is empty (need metadata backfill).
     */
    @Throws(SQLException::class)
    fun getTrackIdsWithEmptyMetadata(): Set<String> {
        val result = mutableSetOf<String>()
        connection?.prepareStatement("SELECT track_id FROM songs_embedding WHERE artist = '' OR artist IS NULL")?.use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) result.add(rs.getString("track_id"))
            }
        }
        return result
    }

    /**
     * Returns track IDs whose content hash is empty.
     */
    @Throws(SQLException::class)
    fun getTrackIdsWithEmptyHash(): Set<String> {
        val result = mutableSetOf<String>()
        connection?.prepareStatement("SELECT track_id FROM songs_embedding WHERE content_hash = '' OR content_hash IS NULL")?.use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) result.add(rs.getString("track_id"))
            }
        }
        return result
    }

    /**
     * Retrieves all track IDs.
     */
    @Throws(SQLException::class)
    fun getAllTrackIds(): Set<String> {
        val result = mutableSetOf<String>()
        connection?.prepareStatement("SELECT track_id FROM songs_embedding")?.use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(rs.getString("track_id"))
                }
            }
        }
        return result
    }

    /**
     * Returns the number of records in the database.
     */
    @Throws(SQLException::class)
    fun count(): Int {
        connection?.prepareStatement(COUNT_SQL)?.use { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    /**
     * Batch inserts multiple records in a single transaction.
     */
    data class BatchRecord(
        val trackId: String,
        val sourceText: String,
        val vector: FloatArray,
        val contentHash: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = ""
    )

    @Throws(SQLException::class)
    fun upsertBatch(records: List<BatchRecord>) {
        connection?.let { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(INSERT_SQL).use { stmt ->
                    val now = System.currentTimeMillis()
                    records.forEach { r ->
                        stmt.setString(1, r.trackId)
                        stmt.setString(2, r.sourceText)
                        stmt.setBytes(3, floatArrayToBytes(r.vector))
                        stmt.setLong(4, now)
                        stmt.setString(5, r.contentHash)
                        stmt.setString(6, r.title)
                        stmt.setString(7, r.artist)
                        stmt.setString(8, r.album)
                        stmt.addBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    // --- Serialization helpers ---

    private fun floatArrayToBytes(arr: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(arr.size * 4)
        arr.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        val arr = FloatArray(bytes.size / 4)
        for (i in arr.indices) {
            arr[i] = buffer.getFloat()
        }
        return arr
    }

    // --- Data class ---

    data class SongRecord(
        val trackId: String,
        val sourceText: String,
        val vector: FloatArray,
        val updatedAt: Long,
        val contentHash: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = ""
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SongRecord) return false
            return trackId == other.trackId
        }

        override fun hashCode(): Int = trackId.hashCode()
    }

    private fun readSongRecord(rs: java.sql.ResultSet): SongRecord {
        return SongRecord(
            trackId = rs.getString("track_id"),
            sourceText = rs.getString("source_text"),
            vector = bytesToFloatArray(rs.getBytes("vector")),
            updatedAt = rs.getLong("updated_at"),
            contentHash = rs.getString("content_hash")?.takeIf { it.isNotBlank() }.orEmpty(),
            title = rs.getString("title")?.takeIf { it.isNotBlank() }.orEmpty(),
            artist = rs.getString("artist")?.takeIf { it.isNotBlank() }.orEmpty(),
            album = rs.getString("album")?.takeIf { it.isNotBlank() }.orEmpty()
        )
    }
}
