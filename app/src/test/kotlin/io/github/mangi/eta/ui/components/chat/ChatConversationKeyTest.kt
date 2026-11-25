package io.github.mangi.eta.ui.components.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatConversationKeyTest {
    @Test
    fun sameConversationKeepsTheSameCompositionKey() {
        assertEquals(
            chatConversationCompositionKey("conversation-1"),
            chatConversationCompositionKey("conversation-1"),
        )
    }

    @Test
    fun switchingConversationChangesTheCompositionKey() {
        assertNotEquals(
            chatConversationCompositionKey("conversation-1"),
            chatConversationCompositionKey("conversation-2"),
        )
    }

    @Test
    fun draftConversationHasAStableNonNullKey() {
        assertEquals("conversation:draft", chatConversationCompositionKey(null))
    }
}
