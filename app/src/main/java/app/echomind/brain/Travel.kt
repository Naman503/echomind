package app.echomind.brain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.util.Log
import app.echomind.core.SettingsStore
import app.echomind.util.Js
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Travel mode: EchoMind rests so the battery lasts the trip. No transcription,
 * no AI, no night review (even on the charger), no check-ins or nudges; the
 * AI model is freed. Reminders still ring. Listening stops unless you chose to
 * keep recording for later.
 *
 * It turns itself on when you're far from all your places (two readings, or
 * moving fast), from cheap network location checked about every 30 minutes —
 * and stops checking the moment it's on. Planned trips switch it by date.
 */
class Travel(
    private val context: Context,
    private val settings: SettingsStore,
    private val onStart: suspend (keepRecording: Boolean) -> Unit,
    private val onStop: suspend (wasListening: Boolean) -> Unit,
    private val notify: (on: Boolean, title: String, text: String, auto: Boolean) -> Unit,
) {
    @Serializable data class Span(val from: Long, val to: Long = 0, val reason: String = "")

    private val prefs = context.getSharedPreferences("travel", Context.MODE_PRIVATE)
    val active get() = settings.value.travel

    // ── Switching ────────────────────────────────────────────────

    suspend fun enable(reason: String, where: String = "") {
        if (active) return
        val s = settings.value
        settings.update { it.copy(travel = true, travelSince = System.currentTimeMillis(), travelReason = reason, travelWhere = where, travelWasListening = it.captureWanted) }
        log { it + Span(System.currentTimeMillis(), reason = reason) }
        prefs.edit().remove("recent").apply()
        onStart(s.travelRecords)
        showOngoing()
        Log.i(TAG, "travel mode on ($reason) $where")
    }

    /** [suppressHours]: "Not travelling" — no automatic switch for that long. */
    suspend fun disable(suppressHours: Int = 0) {
        if (!active) return
        val s = settings.value
        val until = if (suppressHours > 0) System.currentTimeMillis() + suppressHours * 3_600_000L else s.travelSuppressUntil
        // A trip you planned and ended early stays ended.
        val trips = if (s.travelReason == "plan") s.trips.filterNot { tripActive(it, LocalDate.now()) } else s.trips
        settings.update { it.copy(travel = false, travelReason = "", travelWhere = "", travelSuppressUntil = until, trips = trips) }
        log { l -> l.dropLast(1) + (l.lastOrNull()?.copy(to = System.currentTimeMillis()) ?: Span(s.travelSince, System.currentTimeMillis())) }
        notify(false, "", "", false)
        onStop(s.travelWasListening)
        Log.i(TAG, "travel mode off")
    }

    /** The persistent notification (also re-shown after a restart). */
    fun showOngoing() {
        val s = settings.value
        if (!s.travel) return
        val auto = s.travelReason == "auto"
        notify(
            true,
            if (auto) "Travel mode turned on by itself" else "Travel mode is on",
            buildString {
                if (auto && s.travelWhere.isNotBlank()) append("You seem to be away: ${s.travelWhere}. ")
                append(when {
                    s.travelRecords -> "Recording and noting places for later — "
                    s.travelProfile == "places" -> "Only noting where you go — "
                    else -> "Resting completely — "
                })
                append(if (s.travelProcessOnCharge) "catches up on the charger. " else "no AI. ")
                append("Reminders still ring.")
            },
            auto,
        )
    }

    // ── Planned trips ────────────────────────────────────────────

    fun tripActive(t: Trip, d: LocalDate) = runCatching { !d.isBefore(LocalDate.parse(t.from)) && !d.isAfter(LocalDate.parse(t.to)) }.getOrDefault(false)

    /** On when a planned trip starts; off when the trip that turned it on is over. */
    suspend fun checkPlans() {
        val s = settings.value
        val today = LocalDate.now()
        val trip = s.trips.firstOrNull { tripActive(it, today) }
        if (trip != null && !s.travel) enable("plan", trip.label)
        if (trip == null && s.travel && s.travelReason == "plan") disable()
        // Old trips are forgotten.
        val kept = s.trips.filter { runCatching { !LocalDate.parse(it.to).isBefore(today.minusDays(1)) }.getOrDefault(false) }
        if (kept.size != s.trips.size) settings.update { it.copy(trips = kept) }
    }

    // ── Noticing you're away ─────────────────────────────────────

    fun hasPermission(background: Boolean = true): Boolean {
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = !background || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return coarse && bg
    }

    /** What the last check found, for the settings screen. */
    fun lastCheck(): String? = prefs.getString("lastCheck", null)

    /**
     * The periodic check (about every 30 minutes). While travelling it never asks for a
     * location; at most it reads one other apps already got, to offer "you're home".
     */
    suspend fun check() {
        checkPlans()
        val s = settings.value
        if (s.travel) {
            if (s.travelNoticeHome && hasPermission()) noticeHome()
            return
        }
        if (!s.travelAuto && !s.travelLearn) return
        if (!hasPermission()) return
        val fix = locate() ?: return
        if (s.travelLearn) learn(fix)
        if (!s.travelAuto || s.places.isEmpty() || System.currentTimeMillis() < s.travelSuppressUntil) return
        val where = Geo.classify(fix, s.places, s.travelAwayKm.toDouble(), s.travelCorridorKm.toDouble())
        prefs.edit().putString("lastCheck", "${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(fix.at)} · ${describe(where, fix)}").apply()
        val recent = (recent() + (fix to where)).filter { fix.at - it.first.at < 3 * 3_600_000L }.takeLast(6)
        saveRecent(recent.map { it.first })
        if (Geo.confirmsTravel(recent)) enable("auto", describe(where, fix))
    }

    fun describe(w: Geo.Where, f: GeoFix): String = when (w) {
        is Geo.Where.At -> "at ${w.place.name}"
        is Geo.Where.Commuting -> "between ${w.from.name} and ${w.to.name}"
        is Geo.Where.Nearby -> "${Geo.km(w.meters)} from ${w.place.name}"
        is Geo.Where.Away -> w.nearest?.let { "${Geo.km(w.meters)} ${Geo.direction(it.lat, it.lon, f.lat, f.lon)} of ${it.name}" } ?: "away from your places"
    }

    private fun recent(): List<Pair<GeoFix, Geo.Where>> {
        val s = settings.value
        val fixes = prefs.getString("recent", null)?.let { runCatching { Js.json.decodeFromString(ListSerializer(GeoFix.serializer()), it) }.getOrNull() }.orEmpty()
        return fixes.map { it to Geo.classify(it, s.places, s.travelAwayKm.toDouble(), s.travelCorridorKm.toDouble()) }
    }

    private fun saveRecent(f: List<GeoFix>) = prefs.edit().putString("recent", Js.json.encodeToString(ListSerializer(GeoFix.serializer()), f)).apply()

    /** Coarse fixes kept for suggesting places (two weeks at most, on the phone only). */
    fun samples(): List<GeoFix> = prefs.getString("samples", null)?.let { runCatching { Js.json.decodeFromString(ListSerializer(GeoFix.serializer()), it) }.getOrNull() }.orEmpty()

    private fun learn(f: GeoFix) {
        val kept = (samples() + f).filter { f.at - it.at < 14 * 86_400_000L }.takeLast(700)
        prefs.edit().putString("samples", Js.json.encodeToString(ListSerializer(GeoFix.serializer()), kept)).apply()
    }

    fun forgetSamples() = prefs.edit().remove("samples").remove("recent").remove("lastCheck").apply()

    fun suggestions(): List<Geo.Suggestion> = Geo.suggest(samples(), settings.value.places)

    private fun noticeHome() {
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val loc = runCatching { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull() ?: return
        if (System.currentTimeMillis() - loc.time > 60 * 60_000L) return
        val f = GeoFix(loc.latitude, loc.longitude, loc.time, loc.accuracy)
        val w = Geo.classify(f, settings.value.places, settings.value.travelAwayKm.toDouble(), settings.value.travelCorridorKm.toDouble())
        if (w is Geo.Where.At && w.place.kind == "home" && prefs.getLong("homePrompted", 0) < settings.value.travelSince) {
            prefs.edit().putLong("homePrompted", System.currentTimeMillis()).apply()
            notify(true, "Welcome back to ${w.place.name}?", "Travel mode is still on. Tap “I'm back” to turn EchoMind on again.", false)
        }
    }

    /** Precise location allowed (Wi-Fi level instead of Android's ±2 km approximate). */
    fun hasPrecise() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * One reading. A recent one the phone already has if it's good enough; else a fresh one:
     * with precise location, Wi-Fi level (balanced power, no constant GPS); without it, Android's
     * approximate location (about ±2 km — the most it allows). [now]: you asked ("where am I"),
     * so a short GPS fix is fine.
     */
    @Suppress("MissingPermission")
    suspend fun locate(now: Boolean = false): GeoFix? {
        if (!hasPermission(background = false)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val precise = hasPrecise()
        val t = System.currentTimeMillis()
        val goodEnough = if (precise) (if (now) 60f else 200f) else 3_000f
        val known = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { t - it.time < (if (now) 2 else 10) * 60_000L && it.accuracy <= goodEnough }
            .minByOrNull { it.accuracy }
        if (known != null) return known.toFix()
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val quality = when {
            precise && now -> LocationRequest.QUALITY_HIGH_ACCURACY
            precise -> LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
            else -> LocationRequest.QUALITY_LOW_POWER
        }
        val req = LocationRequest.Builder(0).setQuality(quality).setDurationMillis(if (now) 20_000 else 30_000).build()
        return withTimeoutOrNull(35_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching {
                    lm.getCurrentLocation(provider, req, cancel, Executors.newSingleThreadExecutor()) { loc -> if (cont.isActive) cont.resume(loc) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }?.toFix()
    }

    private fun Location.toFix() = GeoFix(latitude, longitude, time, accuracy)

    // ── History ──────────────────────────────────────────────────

    fun spans(): List<Span> = prefs.getString("log", null)?.let { runCatching { Js.json.decodeFromString(ListSerializer(Span.serializer()), it) }.getOrNull() }.orEmpty()

    private fun log(change: (List<Span>) -> List<Span>) {
        val l = change(spans()).takeLast(200)
        prefs.edit().putString("log", Js.json.encodeToString(ListSerializer(Span.serializer()), l)).apply()
    }

    /** Whether any part of [d] was spent in travel mode. */
    fun wasTravelling(d: LocalDate): Boolean {
        val z = java.time.ZoneId.systemDefault()
        val a = d.atStartOfDay(z).toInstant().toEpochMilli(); val b = d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        return spans().any { it.from < b && (if (it.to == 0L) System.currentTimeMillis() else it.to) > a }
    }

    companion object { private const val TAG = "EchoTravel" }
}
