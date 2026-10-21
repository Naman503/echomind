package app.echomind.ai

import app.echomind.core.Cloud
import app.echomind.core.Secrets
import app.echomind.util.Js
import app.echomind.util.arr
import app.echomind.util.obj
import app.echomind.util.str
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private val JSON_TYPE = "application/json".toMediaType()

/** Shared plumbing: every call goes through [Cloud.send] (indicator + ledger). */
abstract class CloudLlm(protected val cloud: Cloud, protected val secrets: Secrets, private val keyName: String) : Llm {
    override val local = false
    override val promptBudgetChars = 60_000

    protected fun key(): String = secrets.get(keyName)
        ?: throw LlmException("Add your ${keyName.replaceFirstChar { it.uppercase() }} API key in Settings → Providers", retryable = false)

    protected suspend fun <T> sendJson(req: LlmRequest, request: Request, bytes: Long, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        cloud.send(req.feature, id, bytes, req.purpose) {
            cloud.http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) {
                    val body = r.body.string()
                    throw LlmException("$id: HTTP ${r.code} ${body.take(240)}", retryable = r.code == 429 || r.code >= 500)
                }
                read(r)
            }
        }
    }

    /** Reads an SSE body line by line, handing each `data:` payload to [onData]. */
    protected fun readSse(r: Response, onData: (String) -> Unit) {
        val source = r.body.source()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isNotEmpty()) onData(data)
        }
    }
}

/** Google Gemini via generateContent / streamGenerateContent. */
class GeminiLlm(cloud: Cloud, secrets: Secrets, private val model: () -> String) : CloudLlm(cloud, secrets, "gemini") {
    override val id = "gemini"

    private fun body(req: LlmRequest, json: Boolean): String = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", req.system) }) } }
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", req.user) }) }
            })
        }
        putJsonObject("generationConfig") {
            // Thinking tokens count against the output limit.
            put("maxOutputTokens", req.maxTokens * when (req.think) { Think.OFF -> 2; Think.SOME -> 6; Think.DEEP -> 12 })
            put("temperature", req.temperature)
            if (json && req.schema != null) {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", req.schema)
            }
            // Lite models think "minimal" by default; larger ones take "low" as the cheapest level.
            val level = when (req.think) {
                Think.OFF -> if ("lite" in model()) "minimal" else "low"
                Think.SOME -> "medium"
                Think.DEEP -> "high"
            }
            putJsonObject("thinkingConfig") { put("thinkingLevel", level) }
        }
    }.toString()

    override suspend fun json(req: LlmRequest): JsonObject {
        val b = body(req, json = true)
        val request = Request.Builder().url("$BASE/${model()}:generateContent").header("x-goog-api-key", key()).post(b.toRequestBody(JSON_TYPE)).build()
        val text = sendJson(req, request, b.length.toLong()) { r -> Js.geminiText(Js.parseObject(r.body.string())) }
        return text?.let { Js.parseLenient(it) } ?: throw LlmException("Gemini returned no usable output")
    }

    override fun stream(req: LlmRequest): Flow<String> = channelFlow {
        val b = body(req, json = false)
        val request = Request.Builder().url("$BASE/${model()}:streamGenerateContent?alt=sse").header("x-goog-api-key", key()).post(b.toRequestBody(JSON_TYPE)).build()
        sendJson(req, request, b.length.toLong()) { r -> readSse(r) { data -> runCatching { Js.geminiText(Js.parseObject(data)) }.getOrNull()?.let { trySendBlocking(it) } } }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    }
}

/** OpenAI-compatible chat completions: OpenAI, Groq, OpenRouter, Ollama / llama.cpp servers. */
class OpenAiLlm(cloud: Cloud, secrets: Secrets, private val baseUrl: () -> String, private val model: () -> String) : CloudLlm(cloud, secrets, "openai") {
    override val id = "openai"

    private fun body(req: LlmRequest, json: Boolean, stream: Boolean): String {
        val base = baseUrl()
        val isOpenAi = "api.openai.com" in base
        return buildJsonObject {
            put("model", model())
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", req.system) })
                add(buildJsonObject { put("role", "user"); put("content", req.user) })
            }
            if (isOpenAi) {
                put("max_completion_tokens", req.maxTokens * when (req.think) { Think.OFF -> 2; Think.SOME -> 6; Think.DEEP -> 12 })
                put("reasoning_effort", when (req.think) { Think.OFF -> "low"; Think.SOME -> "medium"; Think.DEEP -> "high" })
            } else {
                put("max_tokens", req.maxTokens * 2)
                put("temperature", req.temperature)
            }
            if (stream) put("stream", true)
            if (json && req.schema != null) {
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") { put("name", "out"); put("strict", isOpenAi); put("schema", req.schema) }
                }
            }
            if ("openrouter.ai" in base) {
                // Only zero-data-retention endpoints, and only ones that honour the schema.
                putJsonObject("provider") { put("zdr", true); put("data_collection", "deny"); put("require_parameters", json) }
            }
        }.toString()
    }

    private fun request(body: String): Request = Request.Builder()
        .url(baseUrl().trimEnd('/') + "/chat/completions")
        .apply { secrets.get("openai")?.let { header("Authorization", "Bearer $it") } }
        .header("X-OpenRouter-Title", "EchoMind")
        .post(body.toRequestBody(JSON_TYPE))
        .build()

    override suspend fun json(req: LlmRequest): JsonObject {
        val b = body(req, json = true, stream = false)
        val text = sendJson(req, request(b), b.length.toLong()) { r ->
            Js.parseObject(r.body.string()).arr("choices")?.firstOrNull()?.let { it as? JsonObject }?.obj("message")?.str("content")
        }
        return text?.let { Js.parseLenient(it) } ?: throw LlmException("No usable output from ${model()}")
    }

    override fun stream(req: LlmRequest): Flow<String> = channelFlow {
        val b = body(req, json = false, stream = true)
        sendJson(req, request(b), b.length.toLong()) { r ->
            readSse(r) { data ->
                runCatching {
                    Js.parseObject(data).arr("choices")?.firstOrNull()?.let { it as? JsonObject }?.obj("delta")?.str("content")
                }.getOrNull()?.let { trySendBlocking(it) }
            }
        }
    }.flowOn(Dispatchers.IO)
}

/** Anthropic Claude through the official Java SDK. */
class AnthropicLlm(cloud: Cloud, secrets: Secrets, private val model: () -> String) : CloudLlm(cloud, secrets, "anthropic") {
    override val id = "anthropic"

    @Volatile private var client: AnthropicClient? = null
    @Volatile private var clientKey: String? = null

    private fun client(): AnthropicClient {
        val k = key()
        client?.let { if (clientKey == k) return it }
        return AnthropicOkHttpClient.builder().apiKey(k).maxRetries(2).build().also { client = it; clientKey = k }
    }

    /** Models that turned down adaptive thinking once are asked without it from then on. */
    @Volatile private var noThinking = emptySet<String>()

    private fun params(req: LlmRequest, json: Boolean, thinking: Boolean = req.think != Think.OFF && model() !in noThinking): MessageCreateParams {
        val effort = when (req.think) { Think.OFF -> OutputConfig.Effort.LOW; Think.SOME -> OutputConfig.Effort.MEDIUM; Think.DEEP -> OutputConfig.Effort.HIGH }
        val output = OutputConfig.builder().effort(effort)
        if (json && req.schema != null) {
            val schema = JsonOutputFormat.Schema.builder()
            for ((k, v) in strictSchema(req.schema)) schema.putAdditionalProperty(k, JsonValue.from(toPlain(v)))
            output.format(JsonOutputFormat.builder().schema(schema.build()).build())
        }
        val b = MessageCreateParams.builder()
            .model(model())
            .maxTokens((req.maxTokens * if (thinking) 16 else 4).toLong())
            .system(req.system)
            .outputConfig(output.build())
            .addUserMessage(req.user)
        // Adaptive thinking: Claude decides how much to reason, steered by the effort above.
        if (thinking) b.thinking(ThinkingConfigAdaptive.builder().build())
        return b.build()
    }

    /** Runs [block] with thinking, and once more without it if this model refuses thinking. */
    private fun <T> withThinkingFallback(req: LlmRequest, json: Boolean, block: (MessageCreateParams) -> T): T = try {
        call { block(params(req, json)) }
    } catch (e: LlmException) {
        val bad = (e.cause as? AnthropicServiceException)?.statusCode() == 400 && "thinking" in (e.message ?: "")
        if (!bad || req.think == Think.OFF) throw e
        noThinking = noThinking + model()
        call { block(params(req, json, thinking = false)) }
    }

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: RateLimitException) {
        throw LlmException("Claude rate limit: ${e.message}", retryable = true, cause = e)
    } catch (e: AnthropicServiceException) {
        throw LlmException("Claude: ${e.message}", retryable = e.statusCode() >= 500, cause = e)
    }

    override suspend fun json(req: LlmRequest): JsonObject {
        val text = withContext(Dispatchers.IO) {
            cloud.send(req.feature, id, (req.system.length + req.user.length).toLong(), req.purpose) {
                withThinkingFallback(req, json = true) { p ->
                    // Thinking blocks are skipped; only the answer's text blocks are read.
                    client().messages().create(p).content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
                }
            }
        }
        return Js.parseLenient(text) ?: throw LlmException("Claude returned no usable output")
    }

    override fun stream(req: LlmRequest): Flow<String> = channelFlow {
        cloud.send(req.feature, id, (req.system.length + req.user.length).toLong(), req.purpose) {
            withThinkingFallback(req, json = false) { p ->
                client().messages().createStreaming(p).use { s ->
                    s.stream().forEach { event ->
                        event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { trySendBlocking(it.text()) }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Claude's structured outputs reject numeric/length bounds; strip them and close every object. */
        fun strictSchema(e: JsonObject): JsonObject = buildJsonObject {
            for ((k, v) in e) {
                if (k in setOf("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "multipleOf")) continue
                put(k, strip(v))
            }
            if (e["type"]?.str() == "object" && "additionalProperties" !in e) put("additionalProperties", false)
        }

        private fun strip(v: JsonElement): JsonElement = when (v) {
            is JsonObject -> strictSchema(v)
            is JsonArray -> JsonArray(v.map { strip(it) })
            else -> v
        }

        fun toPlain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
            is JsonObject -> e.mapValues { toPlain(it.value) }
            is JsonArray -> e.map { toPlain(it) }
        }
    }
}
