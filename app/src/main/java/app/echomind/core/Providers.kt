package app.echomind.core

/**
 * Every engine the app can use, and the one thing the user must always be able
 * to see about it: whether data leaves the phone, and to whom.
 */
data class ProviderInfo(
    val id: String,
    val label: String,
    val kind: Feature.Kind,
    val local: Boolean,
    /** Who can see the data. */
    val who: String,
    val detail: String,
    /** Secret name in [Secrets], or null. */
    val keyName: String? = null,
)

object Providers {
    val all = listOf(
        // Speech
        ProviderInfo("android", "Google on-device", Feature.Kind.SPEECH, true, "Only this phone",
            "Android's offline speech recogniser (the engine behind voice typing). Light and fast; Hindi + English packs."),
        ProviderInfo("google_online", "Google online (like Gboard)", Feature.Kind.SPEECH, false, "Google",
            "The same server recogniser Gboard voice typing uses when online — usually the most accurate Google option. Speech audio goes to Google."),
        ProviderInfo("sherpa", "Whisper / Parakeet on-device", Feature.Kind.SPEECH, true, "Only this phone",
            "Open models run by EchoMind itself (download once). Also labels who spoke when the voice pack is installed."),
        ProviderInfo("sarvam", "Sarvam AI", Feature.Kind.SPEECH, false, "Sarvam AI (servers in India)",
            "Best Hindi / Hinglish accuracy. ~₹30 per hour of speech.", keyName = "sarvam"),
        ProviderInfo("gemini_stt", "Google Gemini", Feature.Kind.SPEECH, false, "Google",
            "Strong multilingual transcription with speakers, using your Gemini key.", keyName = "gemini"),
        ProviderInfo("elevenlabs", "ElevenLabs Scribe", Feature.Kind.SPEECH, false, "ElevenLabs",
            "Accurate, word timings and speakers. ~$0.22 per hour.", keyName = "elevenlabs"),
        ProviderInfo("deepgram", "Deepgram Nova-3", Feature.Kind.SPEECH, false, "Deepgram",
            "Fast, code-switching Hindi/English, speakers. ~$0.31 per hour.", keyName = "deepgram"),
        ProviderInfo("groq_stt", "Groq Whisper", Feature.Kind.SPEECH, false, "Groq",
            "Cheapest and fastest (~$0.04/hour); best for English-heavy speech.", keyName = "groq"),

        // Language models
        ProviderInfo("local", "On this phone", Feature.Kind.LLM, true, "Only this phone",
            "Gemma / Qwen running on the phone. Private; slower; works offline."),
        ProviderInfo("gemini", "Google Gemini", Feature.Kind.LLM, false, "Google",
            "Fast and capable. Paid keys are not used for training; free-tier keys may be.", keyName = "gemini"),
        ProviderInfo("anthropic", "Anthropic Claude", Feature.Kind.LLM, false, "Anthropic",
            "Careful, high-quality notes. API data is not used for training.", keyName = "anthropic"),
        ProviderInfo("openai", "OpenAI-compatible", Feature.Kind.LLM, false, "The server you configure",
            "OpenAI, Groq, OpenRouter, or your own Ollama / llama.cpp server.", keyName = "openai"),
    )

    fun get(id: String) = all.firstOrNull { it.id == id }
    fun isLocal(id: String) = get(id)?.local ?: true
    fun forKind(kind: Feature.Kind) = all.filter { it.kind == kind }
}
