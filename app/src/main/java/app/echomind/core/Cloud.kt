package app.echomind.core

import app.echomind.data.Egress
import app.echomind.data.EgressDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The single door out of the phone. Every request that carries personal data
 * goes through [send], which shows the cloud indicator while it is in flight
 * and writes a line to the data ledger.
 */
class Cloud(private val egress: EgressDao, private val settings: SettingsStore) {
    data class InFlight(val feature: String, val provider: String, val what: String)

    private val _active = MutableStateFlow<List<InFlight>>(emptyList())
    /** Requests leaving the phone right now; drives the always-visible indicator. */
    val active: StateFlow<List<InFlight>> = _active.asStateFlow()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    class Blocked(message: String) : Exception(message)

    /**
     * Runs [block] as a cloud request. Refuses outright in local-only mode — a
     * second line of defence behind the provider routing.
     */
    suspend fun <T> send(feature: Feature, provider: String, bytes: Long, what: String, block: suspend () -> T): T {
        if (settings.value.privacy == Privacy.LOCAL_ONLY) throw Blocked("Local-only mode is on")
        val item = InFlight(feature.label, Providers.get(provider)?.label ?: provider, what)
        _active.update { it + item }
        var ok = false
        try {
            val result = block()
            ok = true
            return result
        } finally {
            _active.update { list -> list.toMutableList().also { it.remove(item) } }
            egress.insert(Egress(at = System.currentTimeMillis(), feature = feature.label, provider = item.provider, bytes = bytes, detail = what, ok = ok))
        }
    }
}
