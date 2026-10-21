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
 * makes it hot, slow or flat while it is in someone's pocket.
 *
 *  - **Duty cycle.** At utilisation u%, every W ms of work is followed by
 *    W·(100−u)/u ms of rest.
 *  - **Sustained load.** After 20 minutes of near-continuous work, a 5-minute
 *    break regardless of temperature (skin heat builds slowly).
 *  - **Heat.** Android's thermal status and headroom forecast, plus battery
 *    temperature, pause work before the system has to throttle.
 *  - **Battery.** Below the floor and not charging, only capture runs.
 *  - **Charging** lifts the duty cycle to at least 80% and allows more threads.
 */
class Governor(private val context: Context, private val settings: SettingsStore) {
    data class Power(val charging: Boolean, val percent: Int, val tempC: Float, val thermal: Int, val headroom: Float?)
    data class State(val allowed: Boolean, val reason: String, val power: Power, val restUntil: Long = 0)

    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val _state = MutableStateFlow(State(true, "", read()))
    val state: StateFlow<State> = _state.asStateFlow()

    private var busySince = 0L
    private var lastWorkEnd = 0L
    private var lastHeadroomAt = 0L
    private var lastHeadroom: Float? = null
    @Volatile private var hot = false

    fun read(): Power {
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
        return Power(charging, if (level >= 0) level * 100 / scale else -1, temp, pm.currentThermalStatus, lastHeadroom)
    }

    /** Threads for speech/AI runtimes: more when plugged in and cool. */
    fun threads(): Int {
        val p = _state.value.power
        return if (p.charging && p.thermal <= PowerManager.THERMAL_STATUS_LIGHT) 4 else 2
    }

    /** Why work may not run right now, or null if it may. */
    private fun blocker(p: Power): String? {
        val s = settings.value
        if (s.onlyWhileCharging && !p.charging) return "Waiting for the charger (your setting)"
        if (!p.charging && p.percent in 0 until s.minBatteryPct) return "Battery below ${s.minBatteryPct}% — only listening"
        if (p.thermal >= PowerManager.THERMAL_STATUS_SEVERE) return "Phone is hot — cooling down"
        // Hysteresis: once hot, wait until clearly cooler before resuming.
        val tempLimit = s.maxBatteryTempC.toFloat() - if (hot) 2f else 0f
        val headroomLimit = if (hot) 0.7f else 0.85f
        val tooHot = p.tempC >= tempLimit || (p.headroom != null && p.headroom >= headroomLimit) || p.thermal >= PowerManager.THERMAL_STATUS_MODERATE
        hot = tooHot
        if (tooHot) return "Letting the phone cool (${p.tempC.toInt()}°C)"
        return null
    }

    /** Suspends until work is allowed; returns immediately when it is. */
    suspend fun awaitTurn() {
        while (true) {
            val p = read()
            val now = System.currentTimeMillis()
            val block = blocker(p)
            if (block != null) {
                busySince = 0
                _state.value = State(false, block, p)
                delay(30_000)
                continue
            }
            // Sustained-load break.
            if (busySince > 0 && now - busySince > SUSTAINED_MS) {
                busySince = 0
                _state.value = State(false, "Short break after 20 minutes of work", p, now + BREAK_MS)
                delay(BREAK_MS)
                continue
            }
            val rest = _state.value.restUntil
            if (rest > now) {
                _state.value = _state.value.copy(allowed = false, reason = "Pacing to keep the phone cool", power = p)
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
        val u = if (p.charging) maxOf(80, settings.value.utilization) else settings.value.utilization.coerceIn(10, 100)
        val rest = workMs * (100 - u) / u
        lastWorkEnd = System.currentTimeMillis()
        _state.value = _state.value.copy(restUntil = lastWorkEnd + rest)
    }

    fun refresh() {
        val p = read()
        _state.value = _state.value.copy(power = p, allowed = blocker(p) == null, reason = blocker(p) ?: "")
    }

    companion object {
        const val SUSTAINED_MS = 20 * 60_000L
        const val BREAK_MS = 5 * 60_000L
    }
}
