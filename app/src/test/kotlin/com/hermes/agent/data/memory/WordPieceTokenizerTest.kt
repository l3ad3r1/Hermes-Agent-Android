package com.hermes.agent.data.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class WordPieceTokenizerTest {

    private val sampleVocab = mapOf(
        "[PAD]" to 0,
        "[UNK]" to 1,
        "[CLS]" to 2,
        "[SEP]" to 3,
        "[MASK]" to 4,
        "hello" to 5,
        "world" to 6,
        "play" to 7,
        "##ing" to 8,
        "##er" to 9,
        "test" to 10,
    )

    private val tokenizer = WordPieceTokenizer(sampleVocab, maxTokens = 16)

    @Test
    fun `encodes known words with CLS and SEP`() {
        val encoding = tokenizer.encode("hello world")
        // [CLS] hello world [SEP] -> 2, 5, 6, 3
        assertArrayEquals(longArrayOf(2L, 5L, 6L, 3L), encoding.ids)
        assertArrayEquals(longArrayOf(1L, 1L, 1L, 1L), encoding.attentionMask)
    }

    @Test
    fun `encodes subwords with prefix`() {
        val encoding = tokenizer.encode("playing")
        // [CLS] play ##ing [SEP] -> 2, 7, 8, 3
        assertArrayEquals(longArrayOf(2L, 7L, 8L, 3L), encoding.ids)
    }

    @Test
    fun `replaces unknown characters with UNK`() {
        val encoding = tokenizer.encode("unknown")
        // [CLS] [UNK] [SEP] -> 2, 1, 3
        assertArrayEquals(longArrayOf(2L, 1L, 3L), encoding.ids)
    }

    @Test
    fun `loadVocab parses HuggingFace format correctly`() {
        val vocabText = "[PAD]\n[UNK]\n[CLS]\n[SEP]\nfoo\nbar\n##baz\n"
        val map = WordPieceTokenizer.loadVocab(ByteArrayInputStream(vocabText.toByteArray(Charsets.UTF_8)))
        assertEquals(0, map["[PAD]"])
        assertEquals(1, map["[UNK]"])
        assertEquals(2, map["[CLS]"])
        assertEquals(3, map["[SEP]"])
        assertEquals(4, map["foo"])
        assertEquals(5, map["bar"])
        assertEquals(6, map["##baz"])
    }
}
