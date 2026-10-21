package app.echomind.stt

import java.io.File

/** One recognised line, with times relative to the start of the audio file. */
data class SpokenLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: String? = null,
    val lang: String? = null,
)

data class SpeechRequest(
    /** 16 kHz mono 16-bit WAV. */
    val file: File,
    val startMs: Long,
    val endMs: Long,
    /** BCP-47, most likely first (e.g. en-IN, hi-IN). */
    val languages: List<String>,
    val isCall: Boolean = false,
)

interface SpeechEngine {
    val id: String
    /** Longest piece this engine wants in one call. */
    val maxPieceMs: Long
    suspend fun transcribe(req: SpeechRequest): List<SpokenLine>
}

class SpeechException(val code: String, message: String, val retryable: Boolean = true) : Exception(message)
