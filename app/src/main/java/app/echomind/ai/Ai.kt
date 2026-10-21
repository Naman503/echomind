package app.echomind.ai

import app.echomind.core.Feature
import app.echomind.core.Providers
import app.echomind.core.Secrets
import app.echomind.core.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Routes each feature to the model the user chose for it, and tracks what is running. */
class Ai(
    private val settings: SettingsStore,
    private val secrets: Secrets,
    val local: LocalLlm,
    private val gemini: GeminiLlm,
    private val anthropic: AnthropicLlm,
    private val openai: OpenAiLlm,
) {
    data class Running(val purpose: String, val provider: String, val since: Long)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    fun llm(feature: Feature): Llm = when (settings.value.provider(feature)) {
        "gemini" -> gemini
        "anthropic" -> anthropic
        "openai" -> openai
        else -> local
    }

    /** Whether [feature] can run now, and if not, why. */
    fun readiness(feature: Feature): String? {
        val id = settings.value.provider(feature)
        val info = Providers.get(id) ?: return "Unknown provider"
        if (info.local) return if (local.installedModel() == null) "Download an on-device model (Settings → On-device models)" else null
        val key = info.keyName ?: return null
        if (id == "openai" && settings.value.openaiBaseUrl.contains("localhost")) return null
        return if (!secrets.has(key)) "Add your ${info.label} key (Settings → Providers)" else null
    }

    fun ready(feature: Feature) = readiness(feature) == null

    suspend fun <T> track(req: LlmRequest, block: suspend (Llm) -> T): T {
        val llm = llm(req.feature)
        _running.value = Running(req.purpose, llm.id, System.currentTimeMillis())
        try {
            return block(llm)
        } finally {
            _running.value = null
        }
    }

    /**
     * The quality setting decides how much thinking a request gets:
     * fast — never; balanced — some, for judgement-heavy jobs; best — deep for those, some for the rest.
     */
    fun resolve(req: LlmRequest): LlmRequest {
        val think = when (settings.value.aiQuality) {
            "fast" -> Think.OFF
            "best" -> if (req.deep) Think.DEEP else Think.SOME
            else -> if (req.deep) Think.SOME else Think.OFF
        }
        return req.copy(think = think)
    }

    val bestQuality get() = settings.value.aiQuality == "best"

    suspend fun json(req: LlmRequest): kotlinx.serialization.json.JsonObject = resolve(req).let { r -> track(r) { it.json(r) } }

    fun stream(req: LlmRequest) = resolve(req).let { r -> llm(r.feature).stream(r) }
}
