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
    "How often you open EchoMind, listen and use Ask: counts only.",
    "Setup steps, the AI provider you picked (its name, never keys or addresses) and the app version.",
    "Crash types, so problems get fixed.",
)
private val NEVER = "Never anything you said, wrote or asked, nor people, places or recordings. Everything you say still stays on this phone unless you choose a cloud provider."

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
                    else -> "On · anonymous counts are shared every 6 hours."
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
