package fuck.andes.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlPolicyTest {
    @Test
    fun `accepts normalized public https urls`() {
        val decision = BrowserUrlPolicy.inspect(" HTTPS://Example.COM:443/search?q=eta#result ")

        assertTrue(decision.allowed)
        assertEquals("https://example.com/search?q=eta#result", decision.normalizedUrl)
        assertEquals("example.com", decision.host)
        assertEquals("https://example.com/search?q=…", decision.displayUrl)
    }

    @Test
    fun `rejects unsupported schemes and url userinfo`() {
        assertEquals("UNSUPPORTED_SCHEME", BrowserUrlPolicy.inspect("file:///etc/passwd").errorCode)
        assertEquals("UNSUPPORTED_SCHEME", BrowserUrlPolicy.inspect("intent://scan/#Intent;end").errorCode)
        assertEquals("UNSUPPORTED_SCHEME", BrowserUrlPolicy.inspect("http://example.com/").errorCode)
        assertEquals(
            "URL_USERINFO_BLOCKED",
            BrowserUrlPolicy.inspect("https://user:password@example.com/private").errorCode
        )
    }

    @Test
    fun `rejects private local link local cgnat and multicast ipv4`() {
        val blocked = listOf(
            "https://0.0.0.0",
            "https://10.2.3.4",
            "https://100.64.1.2",
            "https://127.0.0.1",
            "https://169.254.169.254/latest/meta-data",
            "https://172.31.1.1",
            "https://192.168.0.1",
            "https://224.0.0.1",
        )

        blocked.forEach { url ->
            assertFalse("expected blocked: $url", BrowserUrlPolicy.inspect(url).allowed)
        }
        assertTrue(BrowserUrlPolicy.inspect("https://1.1.1.1/dns-query").allowed)
    }

    @Test
    fun `rejects private ipv6 including ipv4 mapped forms`() {
        val blocked = listOf(
            "https://[::1]/",
            "https://[fe80::1]/",
            "https://[fec0::1]/",
            "https://[fc00::1]/",
            "https://[ff02::1]/",
            "https://[::ffff:192.168.1.10]/",
            "https://[::127.0.0.2]/",
            "https://[::192.168.1.10]/",
            "https://[2001:db8::1]/",
            "https://[2001:20::1]/",
        )

        blocked.forEach { url ->
            assertFalse("expected blocked: $url", BrowserUrlPolicy.inspect(url).allowed)
        }
        assertTrue(BrowserUrlPolicy.inspect("https://[2606:4700:4700::1111]/").allowed)
    }

    @Test
    fun `rejects nonstandard numeric and local hostnames`() {
        assertFalse(BrowserUrlPolicy.inspect("https://127.1/").allowed)
        assertFalse(BrowserUrlPolicy.inspect("https://2130706433/").allowed)
        assertFalse(BrowserUrlPolicy.inspect("https://localhost/").allowed)
        assertFalse(BrowserUrlPolicy.inspect("https://router.lan/").allowed)
        assertFalse(BrowserUrlPolicy.inspect("https://printer/").allowed)
    }

    @Test
    fun `requires every dns answer to be public`() {
        val url = BrowserUrlPolicy.inspect("https://example.com/")

        assertTrue(BrowserUrlPolicy.inspectResolved(url, listOf("93.184.216.34")).allowed)
        assertFalse(
            BrowserUrlPolicy.inspectResolved(
                url,
                listOf("93.184.216.34", "192.168.1.20")
            ).allowed
        )
        assertEquals(
            "DNS_RESOLUTION_FAILED",
            BrowserUrlPolicy.inspectResolved(url, emptyList()).errorCode
        )
    }

    @Test
    fun `redacts all query values and fragment for display`() {
        val display = BrowserUrlPolicy.redactForDisplay(
            "https://example.com/callback?q=eta&access_token=top-secret&session-id=abc#private-fragment"
        )

        assertTrue(display.contains("q=…"))
        assertTrue(display.contains("access_token=…"))
        assertTrue(display.contains("session-id=…"))
        assertFalse(display.contains("top-secret"))
        assertFalse(display.contains("private-fragment"))
    }

    @Test
    fun `model origin removes path flag query and unicode host presentation`() {
        val url = "https://xn--fsqu00a.xn--0zwm56d/private/abcdefghijklmnopqrstuvwxyz123456?opaque-token#secret"

        assertEquals(
            "https://xn--fsqu00a.xn--0zwm56d/",
            BrowserUrlPolicy.originForModel(url),
        )
        val display = BrowserUrlPolicy.redactForDisplay(url)
        assertTrue(display.contains("xn--fsqu00a.xn--0zwm56d"))
        assertFalse(display.contains("abcdefghijklmnopqrstuvwxyz123456"))
        assertFalse(display.contains("opaque-token"))
    }

    @Test
    fun `detects captcha cloudflare and throttling challenges`() {
        assertEquals(
            "cloudflare",
            BrowserUrlPolicy.detectRiskChallenge(title = "Just a moment...", pageText = "Cloudflare")
        )
        assertEquals(
            "captcha",
            BrowserUrlPolicy.detectRiskChallenge(pageText = "Please verify you are human")
        )
        assertEquals("http_403", BrowserUrlPolicy.detectRiskChallenge(statusCode = 403))
        assertEquals("http_429", BrowserUrlPolicy.detectRiskChallenge(statusCode = 429))
        assertNull(BrowserUrlPolicy.detectRiskChallenge(statusCode = 200, title = "Example"))
    }
}
