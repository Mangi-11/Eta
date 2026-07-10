package fuck.andes.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomScriptsTest {
    @Test
    fun `readable extraction is visibility bounded and strips url secrets`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.readable(offset = 0, maxChars = 8_000))

        assertTrue(script.contains("!visible(node)"))
        assertTrue(script.contains("remainingNodes: 8000"))
        assertTrue(script.contains("deadline: Date.now() + 750"))
        assertTrue(script.contains("parsed.username = ''"))
        assertTrue(script.contains("parsed.search = ''"))
        assertTrue(script.contains("parsed.hash = ''"))
        assertFalse(script.contains("innerText"))
        assertFalse(script.contains("textContent"))
    }

    @Test
    fun `target resolution never falls back to a hidden selector match`() {
        val script = BrowserDomScripts.wrap(
            BrowserDomScripts.click(selector = "#submit", x = null, y = null)
        )

        assertTrue(script.contains("TARGET_NOT_VISIBLE"))
        assertTrue(script.contains("TARGET_DISABLED"))
        assertFalse(script.contains("document.querySelector(selector);"))
    }
}
