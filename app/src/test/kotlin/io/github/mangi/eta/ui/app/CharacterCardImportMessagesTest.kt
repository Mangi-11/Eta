package io.github.mangi.eta.ui.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardImportMessagesTest {
    @Test
    fun damagedPriorityV3ExplainsThatV2WasNotUsed() {
        val message = checkNotNull(characterCardImportMessage("CARD_V3_INVALID"))
        assertTrue(message.contains("V3"))
        assertTrue(message.contains("未回退到 V2"))
    }

    @Test
    fun unrecognizedErrorCannotExposeFileOrExceptionText() {
        assertNull(characterCardImportMessage("角色卡原文：private example"))
        assertNull(characterCardImportMessage(null))
        assertNotNull(characterCardImportMessage("CARD_METADATA_MISSING"))
        assertNotNull(characterCardImportMessage("CARD_TOO_LARGE"))
    }
}
