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

    override fun onStart() {
        super.onStart()
        App.graph.usage.sessionStart()
    }

    override fun onStop() {
        super.onStop()
        // A rotation is not the end of a visit.
        if (!isChangingConfigurations) App.graph.usage.sessionEnd()
    }

    override fun onPause() {
        super.onPause()
        App.graph.speaker.uiVisible = false
    }

    override fun onResume() {
        super.onResume()
        App.graph.speaker.uiVisible = true
        // Opening the app is a good moment to catch up: recover, import calls, wake the pipeline.
        val g = App.graph
        g.usage.count("app_open")
        // A permission granted or taken away in system settings since last time (states only).
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { g.usage.checkPermissions(app.echomind.telemetry.UsageDaily.permissions(this@MainActivity)) } }
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
        // Debug builds only: `am start … --es review_day 2026-10-21 [--ez review_reset true]` runs a day's review now.
        // Debug builds only: `--es travel on|off`.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) intent.getStringExtra("travel")?.let { v ->
            lifecycleScope.launch { if (v == "on") App.graph.travel.enable("manual") else App.graph.travel.disable() }
        }
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) intent.getStringExtra("review_day")?.let { day ->
            val g = App.graph
            lifecycleScope.launch {
                if (intent.getBooleanExtra("review_reset", false)) g.db.analyses().delete("day", day)
                g.capture.processNow(); g.brain.reviewNow(java.time.LocalDate.parse(day))
            }
        }
        // Debug builds only: `--es stt_test win_….wav [--el stt_from 0 --el stt_to 60000] [--es stt_lang hi-IN]` runs the
        // Android recogniser on one queued recording and logs what it heard (tag EchoSttTest); the queue is untouched.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) intent.getStringExtra("stt_test")?.let { name ->
            val g = App.graph
            lifecycleScope.launch {
                val f = java.io.File(filesDir, "audio/$name")
                val from = intent.getLongExtra("stt_from", 0); val to = intent.getLongExtra("stt_to", 60_000)
                val langs = intent.getStringExtra("stt_lang")?.let { listOf(it) } ?: g.settings.value.speechLanguages.ifEmpty { listOf("en-IN") }
                val began = System.currentTimeMillis()
                runCatching { g.androidSpeech.transcribe(app.echomind.stt.SpeechRequest(f, from, to, langs)) }
                    .map { l -> "$name $langs ${System.currentTimeMillis() - began} ms: ${l.size} lines: ${l.joinToString(" | ") { it.text }.take(600)}" }
                    .getOrElse { e -> "$name $langs failed after ${System.currentTimeMillis() - began} ms: ${e.message}" }
                    .let { line -> android.util.Log.w("EchoSttTest", line); java.io.File(filesDir, "stt_test.txt").appendText(line + "\n") }
            }
        }
        when (intent.action) {
            Intent.ACTION_ASSIST -> { pendingRoute.value = "ask"; App.graph.usage.count("deep_link", mapOf("target" to "ask", "via" to "assist")) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
                if (!text.isNullOrEmpty()) {
                    val g = App.graph
                    // Anonymous usage: that something was shared in, and roughly how long — never the text.
                    g.usage.count("capture_added", mapOf("type" to "text", "source" to "share", "len" to app.echomind.telemetry.UsageMath.countBucket(text.length)))
                    lifecycleScope.launch {
                        val now = System.currentTimeMillis()
                        g.db.utterances().insert(Utterance(startAt = now, endAt = now + 1_000, text = text.take(4_000), speaker = "me", source = "typed", engine = "shared", searchText = TextNorm.searchable(text)))
                        g.brain.poke()
                    }
                    pendingRoute.value = "today"
                }
            }
            else -> intent.getStringExtra(EXTRA_ROUTE)?.let {
                pendingRoute.value = it
                // Notifications open without an action; shortcuts and widgets use ACTION_VIEW.
                App.graph.usage.count("deep_link", mapOf("target" to app.echomind.telemetry.UsageMath.linkTarget(it), "via" to if (intent.action == Intent.ACTION_VIEW) "shortcut_widget" else "notification"))
            }
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
        val task = ShortcutInfoCompat.Builder(this, "task").setShortLabel("New task")
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_stat_idle))
            .setIntent(Intent(this, app.echomind.surfaces.QuickAddActivity::class.java).setAction(Intent.ACTION_VIEW)).build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, listOf(task, listen, ask, note)) }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
