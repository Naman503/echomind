package app.echomind.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import app.echomind.core.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides when the phone may work on transcription and AI, so EchoMind never
 * makes it hot, slow or flat while it is in someone's pocket. Every limit is
 * the owner's to set (Settings → Phone health); listening itself never stops.
 *
 *  - **Duty cycle.** At utilisation u%, every W ms of work is followed by W·(100−u)/u ms of rest.
 *  - **Sustained load.** After N minutes of near-continuous work, a break (skin heat builds slowly).
 *  - **Heat.** Battery temperature, Android's thermal status and its headroom forecast,
 *    with a cool-down margin so work doesn't flap on and off at the limit.
 *  - **Battery.** Below the floor and not charging; optionally Battery Saver.
 *  - **You.** A pause you set, or "ignore limits for a while" (Android's own emergency level always stops work).
 */
class Governor(private val context: Context, private val settings: SettingsStore) {
    data class Power(val charging: Boolean, val percent: Int, val tempC: Float, val thermal: Int, val headroom: Float?, val saver: Boolean = false)
    data class State(val allowed: Boolean, val reason: String, val power: Power, val restUntil: Long = 0, val rule: String? = null)
    /** One reading for the temperature chart. */
    data class Sample(val at: Long, val tempC: Float, val headroom: Float?, val thermal: Int, val working: Boolean)

    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _history = MutableStateFlow<List<Sample>>(emptyList())
    /** The last 3 hours, one sample a minute. */
    val history: StateFlow<List<Sample>> = _history.asStateFlow()

    // The first reading isn't recorded: the state it would log doesn't exist yet.
    private val _state = MutableStateFlow(State(true, "", read(record = false)))
    val state: StateFlow<State> = _state.asStateFlow()

    private var busySince = 0L
    private var lastWorkEnd = 0L
    private var lastHeadroomAt = 0L
    private var lastHeadroom: Float? = null
    @Volatile private var hot = false
    /** "Ignore the limits" until this time (not saved: it ends with the app). */
    @Volatile var ignoreLimitsUntil = 0L

    fun read(record: Boolean = true): Power {
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val now = System.currentTimeMillis()
        // getThermalHeadroom returns NaN if called more often than ~every 10 s.
        if (now - lastHeadroomAt > 10_000) {
            lastHeadroomAt = now
            lastHeadroom = pm.getThermalHeadroom(30).takeIf { !it.isNaN() && it > 0f }
        }
        val p = Power(charging, if (level >= 0) level * 100 / scale else -1, temp, pm.currentThermalStatus, lastHeadroom, pm.isPowerSaveMode)
        if (record) record(p)
        return p
    }

    private fun record(p: Power) {
        val now = System.currentTimeMillis()
        val h = _history.value
        if (h.isNotEmpty() && now - h.last().at < 60_000) return
        _history.value = (h + Sample(now, p.tempC, p.headroom, p.thermal, _state.value.allowed)).filter { now - it.at < 3 * 3_600_000L }
    }

    /** Threads for speech/AI runtimes, as set for battery and charging (fewer when warm). */
    fun threads(): Int {
        val p = _state.value.power
        val s = settings.value
        return (if (p.charging && p.thermal <= PowerManager.THERMAL_STATUS_LIGHT) s.threadsCharging else s.threadsBattery).coerceIn(1, 8)
    }

    /** Why work may not run right now (and which rule), or null if it may. */
    private fun blocker(p: Power): Pair<String, String>? {
        val s = settings.value
        val now = System.currentTimeMillis()
        // Android's own emergency levels always stop work: past this the system shuts things down itself.
        if (p.thermal >= PowerManager.THERMAL_STATUS_EMERGENCY) return "Phone is overheating — Android's emergency level" to "android"
        if (s.travel && !s.travelMayProcess(p.charging)) return (if (s.travelProcessOnCharge) "Travel mode — catching up when charging" else "Travel mode — resting to save battery") to "travel"
        if (now < ignoreLimitsUntil) return null
        if (s.processingPausedUntil > now) return (if (s.processingPausedUntil == Long.MAX_VALUE) "Paused by you" else "Paused by you until ${app.echomind.ai.Prompts.time(s.processingPausedUntil)}") to "pause"
        if (s.onlyWhileCharging && !p.charging) return "Waiting for the charger (your setting)" to "charging"
        if (s.pauseOnBatterySaver && p.saver && !p.charging) return "Battery Saver is on" to "saver"
        if (!p.charging && p.percent in 0 until s.minBatteryPct) return "Battery below ${s.minBatteryPct}% — only listening" to "battery"
        // Hysteresis: once hot, wait until clearly cooler before resuming.
        val margin = if (hot) s.coolDownC.toFloat() else 0f
        val reason = when {
            p.tempC >= s.maxBatteryTempC - margin -> (if (hot) "Letting the phone cool: battery ${"%.1f".format(p.tempC)}°C, resuming below ${s.maxBatteryTempC - s.coolDownC}°C" else "Letting the phone cool: battery ${"%.1f".format(p.tempC)}°C (limit ${s.maxBatteryTempC}°C)") to "temp"
            p.thermal >= s.thermalPauseAt.coerceIn(1, 5) -> "Letting the phone cool: Android reports it ${thermalName(p.thermal)}" to "thermal"
            p.headroom != null && s.headroomLimit <= 1.2f && p.headroom >= s.headroomLimit - (if (hot) 0.12f else 0f) -> (if (hot) "Letting the phone cool: heat forecast ${(p.headroom * 100).toInt()}%, resuming below ${((s.headroomLimit - 0.12f) * 100).toInt()}%" else "Letting the phone cool: heat forecast ${(p.headroom * 100).toInt()}% (limit ${(s.headroomLimit * 100).toInt()}%)") to "headroom"
            else -> null
        }
        hot = reason != null
        return reason
    }

    /** Suspends until work is allowed; returns immediately when it is. */
    suspend fun awaitTurn() {
        while (true) {
            val p = read()
            val now = System.currentTimeMillis()
            val s = settings.value
            val block = blocker(p)
            if (block != null) {
                busySince = 0
                if (_state.value.reason != block.first) android.util.Log.i("EchoGovernor", "holding work: ${block.first} (${block.second})")
                _state.value = State(false, block.first, p, rule = block.second)
                delay(30_000)
                continue
            }
            // Sustained-load break.
            if (s.breakAfterMin > 0 && busySince > 0 && now - busySince > s.breakAfterMin * 60_000L && now >= ignoreLimitsUntil) {
                busySince = 0
                val breakMs = s.breakMin.coerceIn(1, 30) * 60_000L
                android.util.Log.i("EchoGovernor", "break for ${breakMs / 60_000} min")
                _state.value = State(false, "Short break after ${s.breakAfterMin} minutes of work", p, now + breakMs, rule = "break")
                delay(breakMs)
                continue
            }
            val rest = _state.value.restUntil
            if (rest > now && now >= ignoreLimitsUntil) {
                _state.value = _state.value.copy(allowed = false, reason = "Pacing to keep the phone cool", power = p, rule = "pace")
                delay(rest - now)
                continue
            }
            if (busySince == 0L || now - lastWorkEnd > 60_000) busySince = now
            _state.value = State(true, "", p)
            return
        }
    }

    /** Call after a unit of work; schedules the rest that keeps utilisation at the user's level. */
    fun workDone(workMs: Long) {
        val p = _state.value.power
        val s = settings.value
        val u = (if (p.charging) maxOf(s.chargingUtilization, s.utilization) else s.utilization).coerceIn(10, 100)
        val rest = workMs * (100 - u) / u
        lastWorkEnd = System.currentTimeMillis()
        _state.value = _state.value.copy(restUntil = lastWorkEnd + rest)
    }

    fun refresh() {
        val p = read()
        val b = blocker(p)
        _state.value = _state.value.copy(power = p, allowed = b == null, reason = b?.first ?: "", rule = b?.second)
    }

    companion object {
        fun thermalName(t: Int) = when (t) { 0 -> "cool"; 1 -> "slightly warm"; 2 -> "warm"; 3 -> "hot"; 4 -> "very hot"; 5 -> "overheating"; else -> "shutting down" }
    }
}
