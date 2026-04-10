package com.gg.spwaiplaylist

import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter

// ... imports and settings above are not modified, so I'll just rewrite the relevant class parts ...
import java.net.HttpURLConnection
import java.net.URL

private const val DEFAULT_EMBEDDING_MODEL = "Qwen/Qwen3-Embedding-8B"
private const val DEFAULT_CHAT_MODEL = "deepseek/deepseek-v3.2-251201"
private const val DEFAULT_EMBEDDING_BASE_URL = "https://api.siliconflow.cn/v1/embeddings"
private const val DEFAULT_CHAT_BASE_URL = "https://api.qnaigc.com/v1/chat/completions"

@OptIn(UnstableSpwWorkshopApi::class)
class RemoteAiSettings(
    val embeddingApiKey: String,
    val chatApiKey: String,
    val embeddingModel: String,
    val chatModel: String,
    val embeddingBaseUrl: String,
    val chatBaseUrl: String,
    val ragThreadCount: Int,
    val maxPlaybackItems: Int,
    val requestDelayMs: Long,
    val enableQueryExpansion: Boolean,
) {
    companion object {
        fun from(config: ConfigHelper): RemoteAiSettings {
            return RemoteAiSettings(
                embeddingApiKey = config.get("remote_ai.embedding_api_key", "") as String,
                chatApiKey = config.get("remote_ai.chat_api_key", "") as String,
                embeddingModel = config.get("remote_ai.embedding_model", DEFAULT_EMBEDDING_MODEL) as String,
                chatModel = config.get("remote_ai.chat_model", DEFAULT_CHAT_MODEL) as String,
                embeddingBaseUrl = config.get("remote_ai.embedding_base_url", DEFAULT_EMBEDDING_BASE_URL) as String,
                chatBaseUrl = config.get("remote_ai.chat_base_url", DEFAULT_CHAT_BASE_URL) as String,
                ragThreadCount = (config.get("rag.thread_count", "1") as String).toIntOrNull()?.coerceAtLeast(1) ?: 1,
                maxPlaybackItems = (config.get("rag.max_playback_items", "20") as String).toIntOrNull()?.coerceAtLeast(1) ?: 20,
                requestDelayMs = (config.get("rag.request_delay_ms", "1000") as String).toLongOrNull()?.coerceAtLeast(0L) ?: 1000L,
                enableQueryExpansion = config.get("rag.enable_query_expansion", false) as Boolean,
            )
        }

        fun empty(): RemoteAiSettings {
            return RemoteAiSettings(
                embeddingApiKey = "",
                chatApiKey = "",
                embeddingModel = DEFAULT_EMBEDDING_MODEL,
                chatModel = DEFAULT_CHAT_MODEL,
                embeddingBaseUrl = DEFAULT_EMBEDDING_BASE_URL,
                chatBaseUrl = DEFAULT_CHAT_BASE_URL,
                ragThreadCount = 1,
                maxPlaybackItems = 20,
                requestDelayMs = 1000L,
                enableQueryExpansion = false,
            )
        }
    }
}

class RemoteAiClients(
    val settings: RemoteAiSettings,
    private val logger: (String) -> Unit,
) {

    fun generateTrackDescription(sourceText: String): String {
        val apiKey = settings.chatApiKey.ifBlank {
            throw IllegalStateException("Missing LLM Chat API key in plugin config")
        }
        val requestJson = buildChatRequest(sourceText)
        val responseBody = postJson(
            url = settings.chatBaseUrl,
            apiKey = apiKey,
            body = requestJson
        )
        val json = JSONObject(responseBody)
        val description = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
        logger("generateTrackDescription(): response length=${description.length}")
        return description
    }

    fun expandQuery(query: String): String {
        val apiKey = settings.chatApiKey.ifBlank {
            throw IllegalStateException("Missing LLM Chat API key in plugin config")
        }
        val requestJson = buildQueryExpansionRequest(query)
        val responseBody = postJson(
            url = settings.chatBaseUrl,
            apiKey = apiKey,
            body = requestJson
        )
        val json = JSONObject(responseBody)
        val expanded = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
        logger("expandQuery(): response length=${expanded.length}")
        return expanded
    }

    fun createEmbedding(input: String): FloatArray {
        val apiKey = settings.embeddingApiKey.ifBlank {
            throw IllegalStateException("Missing Embedding API key in plugin config")
        }
        val requestJson = buildEmbeddingRequest(input)
        val responseBody = postJson(
            url = settings.embeddingBaseUrl,
            apiKey = apiKey,
            body = requestJson
        )
        val json = JSONObject(responseBody)
        val dataArray = json.getJSONArray("data")
        val embeddingArray = dataArray.getJSONObject(0).getJSONArray("embedding")
        val values = FloatArray(embeddingArray.length())
        for (i in 0 until embeddingArray.length()) {
            values[i] = embeddingArray.getFloat(i)
        }
        logger("createEmbedding(): vector size=${values.size}")
        return values
    }

    private fun postJson(url: String, apiKey: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")

        conn.outputStream.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
        }

        val statusCode = conn.responseCode
        val responseBody = (if (statusCode in 200..299) conn.inputStream else conn.errorStream)
            ?.reader(Charsets.UTF_8)
            ?.readText()
            .orEmpty()
        logger("postJson(): $url -> $statusCode")
        if (statusCode !in 200..299) {
            throw IllegalStateException("HTTP $statusCode from $url: $responseBody")
        }
        return responseBody
    }

    private fun buildChatRequest(sourceText: String): String {
        val prompt = """
你是一个音乐标签助手。请根据下面的歌曲元数据，生成一段简短的中文描述，突出风格、情绪、适合的听歌场景。
要求：
- 只输出一段话，不要列表
- 80 字以内
- 不要编造过于具体但无法从元数据推出的信息

歌曲元数据：
$sourceText
""".trimIndent()

        return """
{
  "model": ${jsonString(settings.chatModel)},
  "messages": [
    {"role": "system", "content": "你是一个擅长生成音乐描述的助手。"},
    {"role": "user", "content": ${jsonString(prompt)}}
  ],
  "temperature": 0.2,
  "stream": false
}
""".trimIndent()
    }

    private fun buildQueryExpansionRequest(query: String): String {
        val prompt = """
你是一个音乐搜索查询扩展器。请把用户的简短搜索词扩展成更适合做向量检索的中文语义描述。
要求：
- 只输出扩展后的文本，不要解释
- 保留原始意图
- 可以补充情绪、场景、风格、节奏、乐器等相关词
- 不要输出项目符号或编号

用户搜索词：
$query
""".trimIndent()

        return """
{
  "model": ${jsonString(settings.chatModel)},
  "messages": [
    {"role": "system", "content": "你是一个擅长扩展音乐搜索意图的助手。"},
    {"role": "user", "content": ${jsonString(prompt)}}
  ],
  "temperature": 0.2,
  "stream": false
}
""".trimIndent()
    }

    private fun buildEmbeddingRequest(input: String): String {
        return """
{
  "model": ${jsonString(settings.embeddingModel)},
  "input": ${jsonString(input)},
  "encoding_format": "float",
  "dimensions": 1536
}
""".trimIndent()
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append(String.format("\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

}
