package dima.sweep.localcomplete.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionContextKindTest {
    @Test
    fun `code context rejects comment lines`() {
        val commentLine = IndexedLine("// TODO", "// TODO", "", "/tmp/a.kt", 1, emptyList(), emptyList())
        val codeLine = IndexedLine("val x = 1", "val x = 1", "", "/tmp/a.kt", 2, emptyList(), emptyList())

        assertFalse(CompletionContextKind.CODE.allows(commentLine))
        assertTrue(CompletionContextKind.CODE.allows(codeLine))
    }

    @Test
    fun `comment context prefers comment lines only`() {
        val commentLine = IndexedLine("// TODO", "// TODO", "", "/tmp/a.kt", 1, emptyList(), emptyList())
        val codeLine = IndexedLine("val x = 1", "val x = 1", "", "/tmp/a.kt", 2, emptyList(), emptyList())

        assertTrue(CompletionContextKind.COMMENT.allows(commentLine))
        assertFalse(CompletionContextKind.COMMENT.allows(codeLine))
    }

    @Test
    fun `string context allows non-comment lines`() {
        val commentLine = IndexedLine("// TODO", "// TODO", "", "/tmp/a.kt", 1, emptyList(), emptyList())
        val codeLine = IndexedLine("val x = 1", "val x = 1", "", "/tmp/a.kt", 2, emptyList(), emptyList())

        assertFalse(CompletionContextKind.STRING.allows(commentLine))
        assertTrue(CompletionContextKind.STRING.allows(codeLine))
    }

    @Test
    fun `star-prefixed arithmetic is not mistaken for a comment`() {
        assertEquals(CompletionContextKind.CODE, CompletionContextKind.classifyLine("* 5 / 2"))
        assertEquals(CompletionContextKind.COMMENT, CompletionContextKind.classifyLine("* docs"))
        assertEquals(CompletionContextKind.COMMENT, CompletionContextKind.classifyLine("*/"))
    }
}