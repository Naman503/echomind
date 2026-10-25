package app.echomind.telemetry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.echomind.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WHAT = listOf(
    "How you use EchoMind: app opens, time in the app (rounded), which screens you open, and counts of features used — listening, Ask, voice input, commands by type, tasks, notes, check-ins, reminders, widgets, tiles and searches (that you searched, not what).",
    "How well it works: whether answers, transcription, nightly reviews and downloads succeeded, the kind of error (for example \"offline\" or \"no key\") and how long they took, rounded.",
    "How it's set up, once a day: your settings' on/off and choice values, the AI provider names you picked, which permissions are allowed, and storage and data sizes as rough ranges.",
    "Setup steps, the app version, phone model and language, and crash types with their code location, so problems get fixed.",
)
private val NEVER = "Never anything you said, wrote, asked or were answered, nor transcripts, notes, names of people or places, search words, API keys, server addresses or model names you typed. Everything you say still stays on this phone unless you choose a cloud provider."

/** Shown once: anonymous usage sharing is part of using EchoMind. */
@Composable
fun UsageTermsScreen(onAccept: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Usage terms", style = MaterialTheme.typography.headlineMedium)
            Text("EchoMind shares anonymous usage with its developer to see which features help and what breaks:", style = MaterialTheme.typography.bodyLarge)
            WHAT.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text(NEVER, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Sharing is part of using the app and is on. To stop it, send a request from Settings → Phone & data → Usage sharing; it stops once the developer approves it, and what was shared is then deleted. Each report shows in the Data ledger.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("I understand") }
        }
    }
}

/** Settings → Phone & data → Usage sharing: status and the stop request. */
@Composable
fun UsageSharingScreen() {
    val usage = App.graph.usage
    var status by remember { mutableStateOf(usage.requestStatus) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Usage sharing", style = MaterialTheme.typography.headlineSmall)
            Text(
                when {
                    usage.stopped -> "Stopped. Your request was approved: nothing is shared and what was shared was deleted."
                    status == "pending" -> "On · request to stop is waiting for the developer's approval."
                    status == "declined" -> "On · your last request was declined. You can ask again."
                    else -> "On · anonymous usage is shared every 6 hours."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            WHAT.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text(NEVER, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!usage.stopped && status != "pending") {
                OutlinedButton(onClick = {
                    usage.requestStop()
                    status = "pending"
                    scope.launch { withContext(Dispatchers.IO) { runCatching { usage.flush { emptyMap() } } }; status = usage.requestStatus }
                }) { Text("Request to stop sharing") }
            }
            Text("Install id: ${usage.installId.take(8)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Anonymous usage for a search box: counts one search per visit once something has been typed
 * and left for a moment. Only the screen's fixed name is sent, never the words.
 */
@Composable
fun TrackSearch(surface: String, query: String) {
    var counted by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(query.isBlank()) {
        if (counted || query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(1_200)
        counted = true
        Usage.count("search_used", mapOf("surface" to surface))
    }
}
