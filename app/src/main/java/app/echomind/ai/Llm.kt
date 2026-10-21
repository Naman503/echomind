package app.echomind.ai

import app.echomind.core.Feature
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** One request to a language model. */
data class LlmRequest(
    val feature: Feature,
    /** Shown in the progress UI and the data ledger ("Notes for 3:40 PM call"). */
    val purpose: String,
    val system: String,
    val user: String,
    /** JSON schema for structured output; null for free text. */
    val schema: JsonObject? = null,
    val maxTokens: Int = 1024,
    val temperature: Double = 0.2,
    /** A judgement-heavy job (reading a conversation, the journal, organising): worth thinking about first. */
    val deep: Boolean = false,
    /** How long the model may think before answering; set by [Ai] from the quality setting. */
    val think: Think = Think.OFF,
)

/** OFF answers straight away; SOME and DEEP let the model reason first (slower, better judgement). */
enum class Think { OFF, SOME, DEEP }

interface Llm {
    val id: String
    val local: Boolean
    /** Rough prompt budget in characters this model handles well (small for the phone). */
    val promptBudgetChars: Int

    /** Structured output; throws [LlmException] when nothing usable came back. */
    suspend fun json(req: LlmRequest): JsonObject

    /** Free text, streamed as deltas. */
    fun stream(req: LlmRequest): Flow<String>
}

class LlmException(message: String, val retryable: Boolean = true, cause: Throwable? = null) : Exception(message, cause)
