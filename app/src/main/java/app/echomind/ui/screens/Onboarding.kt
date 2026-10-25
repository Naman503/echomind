package app.echomind.ui.screens

import androidx.compose.material.icons.rounded.FactCheck
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.echomind.App
import app.echomind.capture.Wav
import app.echomind.core.Privacy
import app.echomind.models.ModelCatalog
import app.echomind.ui.Fmt
import app.echomind.ui.components.EchoCard
import app.echomind.ui.components.PrivacyChip
import app.echomind.ui.components.ScreenTopBar
import app.echomind.ui.theme.LocalSemantic
import app.echomind.ui.theme.Sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.echomind.ui.LocalNav

/** First run: what it does, where data goes, the permissions it needs and why, and the vivo settings. */
@Composable
fun Onboarding() {
    val g = App.graph
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    val s by g.settings.flow.collectAsStateWithLifecycle()
    val models by g.models.states.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val resumeTick = app.echomind.ui.components.rememberResumeTick()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).padding(Sp.xl)) {
        LinearProgressIndicator(progress = { (step + 1) / 5f }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Sp.xl))
        AnimatedContent(step, modifier = Modifier.weight(1f), label = "step") { st ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Sp.l)) {
                when (st) {
                    0 -> {
                        Text("EchoMind", style = MaterialTheme.typography.displaySmall)
                        Text("A second brain that listens to your day and remembers it for you.", style = MaterialTheme.typography.titleLarge)
                        Value(Icons.Rounded.GraphicEq, "Hears your day", "Conversations, calls and things you say to yourself — in English, Hindi or both.")
                        Value(Icons.Rounded.TaskAlt, "Turns it into action", "Notes that keep themselves up to date, reminders, tasks, and a daily journal.")
                        Value(Icons.Rounded.Psychology, "Answers anything", "Ask \"what did Rahul say about the invoice?\" and jump to the exact moment.")
                        Value(Icons.Rounded.PhoneAndroid, "Private by default", "Audio is deleted once transcribed. Everything can run on this phone alone.")
                    }
                    1 -> {
                        Text("Where should your data go?", style = MaterialTheme.typography.headlineSmall)
                        Choice(s.privacy == Privacy.LOCAL_ONLY, Icons.Rounded.PhoneAndroid, "Local only", "Everything — speech, notes, memory, answers — runs on this phone. Nothing personal is ever sent anywhere. Slower; needs a one-time model download.", true) {
                            scope.launch { g.settings.update { it.copy(privacy = Privacy.LOCAL_ONLY) } }
                        }
                        Choice(s.privacy == Privacy.CLOUD_ALLOWED, Icons.Rounded.Cloud, "Allow cloud for some features", "Faster, smarter notes with your own Gemini, Claude or OpenAI key. You choose per feature; an amber banner shows whenever data leaves, and a ledger lists every request.", false) {
                            scope.launch { g.settings.update { it.copy(privacy = Privacy.CLOUD_ALLOWED) } }
                        }
                        Text("You can change this any time in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    2 -> {
                        Text("Set up the AI", style = MaterialTheme.typography.headlineSmall)
                        if (s.privacy == Privacy.CLOUD_ALLOWED) {
                            Text("Paste a Gemini key to make notes in the cloud (free keys at aistudio.google.com). Speech stays on the phone unless you choose otherwise.", style = MaterialTheme.typography.bodyMedium)
                            var key by remember { mutableStateOf("") }
                            OutlinedTextField(key, { key = it }, label = { Text("Gemini API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                            Button(enabled = key.isNotBlank(), onClick = {
                                g.secrets.set("gemini", key); key = ""
                                scope.launch { g.settings.update { it.copy(providers = it.providers + mapOf("notes" to "gemini", "memory" to "gemini", "ask" to "gemini", "journal" to "gemini"), cloudConsent = it.cloudConsent + setOf("notes", "memory", "ask", "journal")) } }
                            }) { Text("Use Gemini for notes, memory, Ask and journal") }
                            Text("Other providers (Claude, OpenAI, Sarvam for Hindi speech…) are in Settings → Privacy & providers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(Sp.s))
                            Text("Optional on-device models:", style = MaterialTheme.typography.titleSmall)
                        } else {
                            Text("These run entirely on your phone. Download them on Wi-Fi; you can keep using the app while they download.", style = MaterialTheme.typography.bodyMedium)
                        }
                        ModelCatalog.all.filter { it.recommended }.forEach { m ->
                            val st = models[m.id]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${m.label} · ${Fmt.bytes(m.bytes)}", style = MaterialTheme.typography.bodyLarge)
                                    Text(m.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (st?.downloading == true) {
                                        LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                        Text(downloadLine(st, m.bytes), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                when {
                                    st?.installed == true -> Icon(Icons.Rounded.CheckCircle, "Installed", tint = LocalSemantic.current.positive)
                                    st?.downloading == true || st?.extracting == true -> Text("${((st.progress) * 100).toInt()}%")
                                    else -> TextButton(onClick = { g.models.download(m.id) }) { Text("Get") }
                                }
                            }
                        }
                        Text("Speech-to-text uses Google's offline recogniser already on your phone — no download needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    3 -> {
                        // Re-read on every return from a system dialog or settings page.
                        @Suppress("UNUSED_VARIABLE") val t = tick + resumeTick
                        val mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val notif = ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        val battery = ignoringBattery(ctx)
                        Text("Permissions", style = MaterialTheme.typography.headlineSmall)
                        Perm("Microphone", "To hear your day. While listening, a notification always shows it.", mic)
                        Perm("Notifications", "For reminders, the morning brief and the listening status.", notif)
                        if (!mic || !notif) Button(onClick = { perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) }) { Text("Allow") }
                        Spacer(Modifier.height(Sp.s))
                        Text("Keep it running", style = MaterialTheme.typography.titleMedium)
                        Perm("Battery optimisation off", "So Android doesn't stop EchoMind with the screen off.", battery)
                        if (!battery) TextButton(onClick = { requestBattery(ctx) }) { Text("Turn off battery optimisation") }
                        if (android.os.Build.MANUFACTURER.lowercase() in setOf("vivo", "iqoo")) {
                            Text("On vivo phones, also allow \"High background power consumption\" and Autostart for EchoMind — Settings → Battery & background in EchoMind shows you where.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        Text("Almost done", style = MaterialTheme.typography.headlineSmall)
                        var name by remember { mutableStateOf(s.userName) }
                        OutlinedTextField(name, { name = it; scope.launch { g.settings.update { st2 -> st2.copy(userName = it) } } }, label = { Text("What should it call you?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        EchoCard {
                            Text("How it works", style = MaterialTheme.typography.titleSmall)
                            Text("• Silence is never stored; speech is transcribed and then the audio is deleted.\n• Conversations become notes a few minutes after they end.\n• Found tasks wait in Tasks → Review until you accept them.\n• Pause any time from the notification, or forget the last few minutes.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrivacyChip(s.privacy == Privacy.LOCAL_ONLY, if (s.privacy == Privacy.LOCAL_ONLY) "Local only" else "Cloud allowed for chosen features")
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (step < 4) Button(onClick = { step++ }) { Text("Continue") }
            else Button(onClick = {
                scope.launch {
                    g.settings.update { it.copy(onboarded = true) }
                    if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) g.capture.start()
                }
            }) { Text("Start listening") }
        }
    }
}

@Composable
private fun Value(icon: ImageVector, title: String, body: String) {
    Row {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(Sp.l))
        Column { Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Choice(selected: Boolean, icon: ImageVector, title: String, body: String, local: Boolean, onClick: () -> Unit) {
    EchoCard(onClick = onClick, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(Sp.m))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            PrivacyChip(local)
        }
        Spacer(Modifier.height(Sp.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Perm(title: String, why: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (granted) Icon(Icons.Rounded.CheckCircle, "Done", tint = LocalSemantic.current.positive, modifier = Modifier.padding(12.dp).size(24.dp))
        else Checkbox(checked = false, onCheckedChange = null, modifier = Modifier.padding(horizontal = 0.dp))
        Column { Text(title, style = MaterialTheme.typography.bodyLarge); Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Enrol the owner's voice: three short clips → an averaged voice-print. */
@Composable
fun VoiceScreen() {
    val g = App.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val models by g.models.states.collectAsStateWithLifecycle()
    var clips by remember { mutableStateOf(listOf<ShortArray>()) }
    var recording by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var enrolled by remember { mutableStateOf(g.voice.enrolled()) }
    val prompts = listOf(
        "Read aloud: \"Today I need to finish the report and call my mother in the evening.\"",
        "Now in Hindi or Hinglish: \"Kal subah das baje meeting hai, mujhe yaad dilana.\"",
        "Finally, just talk naturally for a few seconds about your day.",
    )
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        ScreenTopBar("Your voice", onBack = { nav.back() })
        Column(Modifier.padding(Sp.gutter), verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            Text("EchoMind learns what your voice sounds like, so notes know what you said and what others said. Only a voice-print (a list of numbers) is kept — never the recording.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (models["voice-id"]?.installed != true) {
                EchoCard {
                    Text("Needs the Voice ID model (26 MB)", style = MaterialTheme.typography.titleSmall)
                    val st = models["voice-id"]
                    if (st?.downloading == true) LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                    else Button(onClick = { g.models.download("voice-id") }) { Text("Download") }
                }
                return@Column
            }
            if (enrolled) {
                val st by g.settings.flow.collectAsStateWithLifecycle()
                var adding by remember { mutableStateOf(false) }
                EchoCard(color = LocalSemantic.current.onDevice.copy(alpha = 0.1f)) {
                    Text("Your voice is set up ✓", style = MaterialTheme.typography.titleSmall)
                    Text("${g.voice.samples()} samples of your voice · ${g.voice.corrections()} corrections · match level ${"%.2f".format(g.voice.threshold())}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("How sure it must be that a line was you", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("lenient" to "Lenient", "normal" to "Normal", "strict" to "Strict").forEachIndexed { i, (k, l) ->
                        SegmentedButton(st.voiceSensitivity == k, { g.scope.launch { g.settings.update { it.copy(voiceSensitivity = k) } } },
                            SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
                Text(when (st.voiceSensitivity) { "lenient" -> "More of your lines are labelled “You”; others' may be mistaken for you."; "strict" -> "Only lines that clearly sound like you; some of yours may be missed."; else -> "Balanced." },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                app.echomind.ui.components.SwitchRow("Keep learning my voice", "Long lines it is very sure about add to your voice sample (phone in a pocket, on a desk, outdoors)", st.voiceLearn) { v -> g.scope.launch { g.settings.update { it.copy(voiceLearn = v) } } }
                app.echomind.ui.components.SwitchRow("Recognise people I talk to", "Name a speaker once — their voice is remembered and their lines get their name next time", st.recognisePeople) { v -> g.scope.launch { g.settings.update { it.copy(recognisePeople = v) } } }
                if (g.voice.enrolled()) app.echomind.ui.components.NavRow(Icons.Rounded.FactCheck, "Check accuracy", "How well it tells you apart, and lines it isn't sure about") { nav.go(app.echomind.ui.VoiceCheckRoute) }
                Text("Fix mistakes in any transcript: long-press a line → “This was me” or “That wasn't me”. Each fix teaches it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                    OutlinedButton(enabled = !adding, onClick = {
                        adding = true
                        scope.launch {
                            val clip = withContext(Dispatchers.IO) { recordClip(8_000) }
                            result = if (clip != null && g.voice.enroll(listOf(clip), append = true)) "Added — ${g.voice.samples()} samples now." else "Couldn't hear you clearly — try again (pause listening first if the mic is busy)."
                            adding = false
                        }
                    }) { Text(if (adding) "Listening… keep talking" else "Add a sample (8 s)") }
                    TextButton(onClick = { g.voice.forget(); enrolled = false; clips = emptyList() }) { Text("Set up again") }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                return@Column
            }
            val i = clips.size
            if (i < 3) {
                Text("Clip ${i + 1} of 3", style = MaterialTheme.typography.titleMedium)
                Text(prompts[i], style = MaterialTheme.typography.bodyLarge)
                Button(enabled = !recording, onClick = {
                    recording = true
                    scope.launch {
                        val clip = withContext(Dispatchers.IO) { recordClip(6_000) }
                        recording = false
                        if (clip != null) clips = clips + clip else result = "Couldn't use the microphone. Pause listening and try again."
                    }
                }) { Text(if (recording) "Listening… keep talking" else "Record (6 seconds)") }
            } else {
                Button(onClick = {
                    scope.launch {
                        result = "Learning your voice…"
                        val ok = g.voice.enroll(clips)
                        enrolled = ok
                        result = if (ok) "Done — your lines will be labelled \"You\"." else "That didn't work — the clips were too quiet. Try again somewhere calmer."
                        if (!ok) clips = emptyList()
                    }
                }) { Text("Finish") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@SuppressLint("MissingPermission")
private fun recordClip(ms: Long): ShortArray? {
    val min = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 32_000)) }.getOrNull() ?: return null
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
    val out = ShortArray((Wav.SAMPLE_RATE * ms / 1000).toInt())
    var n = 0
    rec.startRecording()
    try {
        while (n < out.size) {
            val r = rec.read(out, n, minOf(1_600, out.size - n))
            if (r <= 0) break
            n += r
        }
    } finally {
        rec.stop(); rec.release()
    }
    return if (n > Wav.SAMPLE_RATE * 2) out.copyOf(n) else null
}
