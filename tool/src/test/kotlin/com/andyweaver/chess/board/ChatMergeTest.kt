package com.andyweaver.chess.board

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [mergeChatHistory] splices the server's chat history against whatever the board stream
 * has already delivered. Chat carries no ids or timestamps, so these cases pin down the
 * positional reasoning the merge depends on.
 */
class ChatMergeTest {

    private fun them(text: String) = ChatMessage(username = "spidey3145", text = text, fromOpponent = true)
    private fun mine(text: String) = ChatMessage(username = "w2andy", text = text, fromOpponent = false)
    private fun sys(text: String) = ChatMessage(username = "lichess", text = text, fromOpponent = false, system = true)

    private fun texts(messages: List<ChatMessage>) = messages.map { it.text }

    @Test
    fun seedsAnEmptyListFromHistory() {
        val history = listOf(them("Test"), mine("good test, thanks"))
        assertEquals(texts(history), texts(mergeChatHistory(history, emptyList())))
    }

    @Test
    fun keepsLiveOnlyTailBeyondTheHistorySnapshot() {
        // "np" arrived on the stream after the server composed the history response.
        val history = listOf(them("Test"), mine("ok"))
        val current = listOf(them("Test"), mine("ok"), mine("np"))
        assertEquals(listOf("Test", "ok", "np"), texts(mergeChatHistory(history, current)))
    }

    @Test
    fun refetchAfterSendingDoesNotDuplicateTheConversation() {
        // The regression this merge exists to prevent: reopening the chat after sending
        // refetches a history that ALREADY contains the sent messages, while the live list
        // does not. Matching on content overlap found nothing in common and concatenated
        // both, rendering the whole conversation twice.
        val history = listOf(
            them("Test"),
            mine("good test, thanks"),
            sys("Black offers draw"),
            sys("White declines draw"),
            mine("ok"),
            mine("np"),
        )
        val current = listOf(
            them("Test"),
            mine("good test, thanks"),
            sys("Black offers draw"),
            sys("White declines draw"),
        )
        val merged = mergeChatHistory(history, current)
        assertEquals(6, merged.size)
        assertEquals(texts(history), texts(merged))
    }

    @Test
    fun isIdempotentAcrossRepeatedFetches() {
        val history = listOf(them("Test"), mine("ok"))
        val once = mergeChatHistory(history, emptyList())
        val twice = mergeChatHistory(history, once)
        val thrice = mergeChatHistory(history, twice)
        assertEquals(texts(history), texts(thrice))
    }

    @Test
    fun repeatedIdenticalTextIsPreserved() {
        // The old greedy-overlap match could collapse back-to-back identical messages into
        // one; position-based merging keeps both.
        val history = listOf(mine("ok"), mine("ok"))
        assertEquals(listOf("ok", "ok"), texts(mergeChatHistory(history, listOf(mine("ok"), mine("ok")))))
    }

    @Test
    fun emptyHistoryLeavesLiveMessagesUntouched() {
        val current = listOf(them("hello"))
        assertEquals(listOf("hello"), texts(mergeChatHistory(emptyList(), current)))
    }
}
