package app.echomind.surfaces

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import app.echomind.App

/**
 * "Read aloud" from any app: in the menu that appears when you select text, and in the
 * Share sheet. EchoMind reads the text with your chosen voice and the usual player
 * controls (notification, earphone buttons); nothing is saved.
 */
class ReadAloudActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim().orEmpty()
        val parts = paragraphs(text)
        when {
            parts.isEmpty() -> Toast.makeText(this, "Nothing to read", Toast.LENGTH_SHORT).show()
            isBareLink(text) -> Toast.makeText(this, "That's a link — select the page's text and choose Read aloud", Toast.LENGTH_LONG).show()
            else -> {
                val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() } ?: callingTitle()
                App.graph.speaker.speak(title, parts)
            }
        }
        finish()
    }

    private fun callingTitle(): String = runCatching {
        referrer?.host?.let { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }
    }.getOrNull()?.let { "From $it" } ?: "Selected text"

    companion object {
        /** Paragraphs (blank-line or line-break separated), so next/previous skip by paragraph. */
        fun paragraphs(text: String): List<String> =
            text.split(Regex("\\n\\s*\\n|\\r?\\n")).map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }

        fun isBareLink(text: String) = Regex("^https?://\\S+$").matches(text.trim())
    }
}
