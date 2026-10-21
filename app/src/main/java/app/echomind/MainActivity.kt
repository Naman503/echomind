package app.echomind

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.echomind.brain.TextNorm
import app.echomind.data.Utterance
import app.echomind.surfaces.StartListeningActivity
import app.echomind.ui.AppRoot
import app.echomind.ui.theme.EchoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** A route asked for by a notification, shortcut, assist or share; consumed by the UI. */
    val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val s by App.graph.settings.flow.collectAsStateWithLifecycle()
            EchoTheme(theme = s.theme, dynamic = s.dynamicColor) {
                // The root surface sets the default text colour for every screen.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(pendingRoute)
                }
            }
        }
        publishShortcuts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is a good moment to catch up: recover, import calls, wake the pipeline.
        val g = App.graph
        lifecycleScope.launch {
            runCatching { g.capture.recoverOrphans() }
            g.models.refresh()
            g.brain.importCalls()
            g.governor.refresh()
            // Something waiting and nothing running it: start catching up now (allowed while the app is open).
            if (!app.echomind.capture.CaptureService.running && g.brain.hasWork()) g.capture.processNow()
            g.brain.poke()
        }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_ASSIST -> pendingRoute.value = "ask"
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                if (!text.isNullOrEmpty()) {
                    val g = App.graph
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        g.db.utterances().insert(Utterance(startAt = now, endAt = now + 1_000, text = text.take(4_000), speaker = "me", source = "typed", engine = "shared", searchText = TextNorm.searchable(text)))
                        g.brain.poke()
                    }
                    pendingRoute.value = "today"
                }
            }
            else -> intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
        }
    }

    private fun publishShortcuts() {
        val listen = ShortcutInfoCompat.Builder(this, "listen").setShortLabel("Start listening")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_stat_listen))
            .setIntent(Intent(this, StartListeningActivity::class.java).setAction(Intent.ACTION_VIEW)).build()
        val ask = ShortcutInfoCompat.Builder(this, "ask").setShortLabel("Ask")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_stat_idle))
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_ROUTE, "ask")).build()
        val note = ShortcutInfoCompat.Builder(this, "note").setShortLabel("New note")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_stat_idle))
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_ROUTE, "note:new")).build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, listOf(listen, ask, note)) }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
