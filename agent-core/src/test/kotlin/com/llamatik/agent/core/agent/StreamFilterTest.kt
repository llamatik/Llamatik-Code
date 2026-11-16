package com.llamatik.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamFilterTest {
    private fun agent(text: String, chunk: Int): Pair<String, String> {
        val thinking = StringBuilder()
        val answer = StringBuilder()
        val f = StreamFilter(StreamFilter.Mode.AGENT, onAnswer = { answer.append(it) }, onThinking = { thinking.append(it) })
        text.chunked(chunk).forEach { f.accept(it) }
        return thinking.toString() to answer.toString()
    }

    private fun chat(text: String, chunk: Int): String {
        val answer = StringBuilder()
        val f = StreamFilter(StreamFilter.Mode.CHAT, onAnswer = { answer.append(it) })
        text.chunked(chunk).forEach { f.accept(it) }
        return answer.toString()
    }

    @Test
    fun `agent mode streams only reasoning and never answer text, however the tags are split`() {
        for (chunk in listOf(1, 2, 3, 5, 100)) {
            val (thinking, answer) = agent("<think>plan the work</think>I will call a tool now", chunk)
            assertEquals("chunk=$chunk", "plan the work", thinking)
            assertEquals("chunk=$chunk", "", answer)
        }
    }

    @Test
    fun `agent mode ends reasoning at alternative closers and drops what follows them`() {
        val (thinking, _) = agent("<think>abc<|im_end|>ignored", 2)
        assertEquals("abc", thinking)
        val (thinking2, _) = agent("<think>abc<wait a moment", 3)
        assertEquals("abc", thinking2)
    }

    @Test
    fun `agent mode keeps an unfinished reasoning block streaming without losing characters`() {
        val (thinking, _) = agent("<think>still going", 1)
        assertEquals("still going", thinking)
    }

    @Test
    fun `reset forgets a half-seen tag and leaves the think block`() {
        val thinking = StringBuilder()
        val f = StreamFilter(StreamFilter.Mode.AGENT, onThinking = { thinking.append(it) })
        f.accept("<think>one")
        f.reset()
        f.accept("plain text")
        f.accept("<think>two</think>")
        assertEquals("onetwo", thinking.toString())
    }

    @Test
    fun `chat mode streams answer text and drops reasoning`() {
        for (chunk in listOf(1, 2, 3, 5, 8, 100)) {
            assertEquals("chunk=$chunk", "Hello world", chat("Hello <think>secret</think>world", chunk))
        }
        // The opening tag may be split across tokens: it is held back until resolved.
        assertEquals("Hello ", chat("Hello <think>secret", 1))
    }

    @Test
    fun `chat mode recognises a closing think tag split across tokens`() {
        // Used to drop the rest of the reply: buffered text was thrown away on every token inside a think block.
        assertEquals("Hello world", chat("Hello <think>secret</think>world", 1))
        assertEquals("Hello world", chat("Hello <think>a < b</think>world", 1))
    }

    @Test
    fun `chat mode holds back a partial think tag until it is resolved`() {
        assertEquals("a < b", chat("a < b", 1))
        assertEquals("x", chat("x<end_of_turn>", 3).takeWhile { it != '<' })
    }

    @Test
    fun `hold length helpers`() {
        assertEquals(3, StreamFilter.holdLenForTags("abc<th", listOf("<think>")))
        assertEquals(0, StreamFilter.holdLenForTags("abc", ANSWER_HOLD_TAGS))
        assertEquals(2, StreamFilter.holdLenForStartsWith("xx</", THINK_CLOSERS))
    }
}
