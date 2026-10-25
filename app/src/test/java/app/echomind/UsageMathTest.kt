package app.echomind

import app.echomind.ai.LlmException
import app.echomind.core.Cloud
import app.echomind.core.Privacy
import app.echomind.core.Settings
import app.echomind.telemetry.UsageMath
import app.echomind.ui.AskRoute
import app.echomind.ui.ConversationRoute
import app.echomind.ui.SettingsRoute
import app.echomind.ui.TodayRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageMathTest {
    private val nameRe = Regex("^[a-z][a-z0-9_]{0,47}$")
    private val keyRe = Regex("^[a-z][a-z0-9_]{0,31}$")

    @Test fun namesFollowTheApi() {
        assertTrue(UsageMath.validName("ask_result"))
        assertFalse(UsageMath.validName("Ask"))
        assertFalse(UsageMath.validName("1st"))
        assertFalse(UsageMath.validName("a".repeat(49)))
    }

    @Test fun propsAreCleaned() {
        val p = UsageMath.cleanProps(mapOf("ok" to true, "n" to 3, "x" to 1.5, "nan" to Double.NaN, "Bad" to "x", "nul" to null, "s" to "y".repeat(100)))
        assertEquals(true, p["ok"])
        assertEquals(3L, p["n"])
        assertEquals(1.5, p["x"])
        assertFalse("nan" in p || "Bad" in p || "nul" in p)
        assertEquals(64, (p["s"] as String).length)
        val many = UsageMath.cleanProps((1..12).associate { "k$it" to it })
        assertEquals(8, many.size)
    }

    @Test fun buckets() {
        assertEquals("lt1s", UsageMath.latencyBucket(200))
        assertEquals("3_10s", UsageMath.latencyBucket(4_000))
        assertEquals("gt60s", UsageMath.latencyBucket(90_000))
        assertEquals("lt10s", UsageMath.sessionBucket(5_000))
        assertEquals("2_5m", UsageMath.sessionBucket(200_000))
        assertEquals("gt60m", UsageMath.sessionBucket(5_000_000))
        assertEquals("0", UsageMath.countBucket(0))
        assertEquals("1", UsageMath.countBucket(1))
        assertEquals("6_20", UsageMath.countBucket(7))
        assertEquals("gt10k", UsageMath.countBucket(50_000))
        assertEquals("lt1mb", UsageMath.sizeBucket(10_000))
        assertEquals("100mb_1gb", UsageMath.sizeBucket(300_000_000))
    }

    @Test fun errorCategoriesNeverNeedTheMessageToBeSent() {
        assertEquals("auth", UsageMath.errorCategory(LlmException("Add your Gemini API key in Settings → Providers", retryable = false)))
        assertEquals("rate_limit", UsageMath.errorCategory(LlmException("gemini: HTTP 429 slow down")))
        assertEquals("server", UsageMath.errorCategory(LlmException("gemini: HTTP 503 unavailable")))
        assertEquals("bad_request", UsageMath.errorCategory(LlmException("openai: HTTP 400 bad")))
        assertEquals("bad_output", UsageMath.errorCategory(LlmException("Gemini returned no usable output")))
        assertEquals("no_model", UsageMath.errorCategory(LlmException("No on-device AI model is downloaded. Get one in Settings → On-device models.")))
        assertEquals("blocked", UsageMath.errorCategory(Cloud.Blocked("Local-only mode is on")))
        assertEquals("timeout", UsageMath.errorCategory(java.net.SocketTimeoutException("read")))
        assertEquals("network", UsageMath.errorCategory(java.net.UnknownHostException("api.example.com")))
        assertEquals("cancelled", UsageMath.errorCategory(kotlinx.coroutines.CancellationException("x")))
        assertEquals("other", UsageMath.errorCategory(RuntimeException("something the user said")))
        assertEquals("none", UsageMath.errorCategory(null))
    }

    @Test fun screensAndLinksUseFixedNames() {
        assertEquals("today", UsageMath.screen(TodayRoute))
        assertEquals("ask", UsageMath.screen(AskRoute(question = "private question")))
        assertEquals("conversation", UsageMath.screen(ConversationRoute(42)))
        assertEquals("settings", UsageMath.screen(SettingsRoute))
        assertEquals("other", UsageMath.screen("nope"))
        assertEquals("note", UsageMath.linkTarget("note:123"))
        assertEquals("conversation", UsageMath.linkTarget("conversation:9"))
        assertEquals("other", UsageMath.linkTarget("something:else"))
    }

    @Test fun settingsSnapshotFitsTheApiAndHoldsNoFreeText() {
        val s = Settings(
            privacy = Privacy.CLOUD_ALLOWED, userName = "Secret Name", aboutMe = "I work at Secret Corp",
            providers = mapOf("ask" to "openai", "notes" to "my-own-server"), openaiBaseUrl = "https://private.example.com",
            openaiModel = "secret-model", webProvider = "searxng", searxngUrl = "https://search.private",
            checkInCustom = listOf("Did I call Mom?"), corrections = listOf("heard => meant"),
        )
        val snap = UsageMath.settingsSnapshot(s)
        val text = snap.toString()
        for (secret in listOf("Secret", "private", "secret-model", "Mom", "meant", "my-own-server")) assertFalse(secret, text.contains(secret))
        for ((name, props) in snap) {
            assertTrue(name, nameRe.matches(name))
            assertTrue("$name has ${props.size} props", props.size <= 8)
            for ((k, v) in props) {
                assertTrue(k, keyRe.matches(k))
                assertTrue("$name.$k", v is Boolean || v is Number || (v is String && v.length <= 64))
            }
        }
        assertEquals("cloud", snap.getValue("settings_privacy")["privacy"])
        assertEquals("openai", snap.getValue("settings_privacy")["p_ask"])
        assertEquals("custom", snap.getValue("settings_privacy")["p_notes"])
        assertEquals(true, snap.getValue("settings_look")["about_me_set"])
    }

    @Test fun keyNamesAreAllowListed() {
        assertEquals("gemini", UsageMath.keyName("gemini"))
        assertEquals("other", UsageMath.keyName("something typed"))
    }
}
