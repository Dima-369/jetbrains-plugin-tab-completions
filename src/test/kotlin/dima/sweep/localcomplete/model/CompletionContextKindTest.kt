package dima.sweep.localcomplete.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionContextKindTest {
    @Test
    fun `code context rejects comment lines`() {
        val commentLine = IndexedLine("// TODO", "// TODO", "", "/tmp/a.kt", 1, listOf(0L))
        val codeLine = IndexedLine("val x = 1", "val x = 1", "", "/tmp/a.kt", 2, listOf(0L))

        assertFalse(CompletionContextKind.CODE.allows(commentLine))
        assertTrue(CompletionContextKind.CODE.allows(codeLine))
    }

    @Test
    fun `comment context prefers comment lines only`() {
        val commentLine = IndexedLine("// TODO", "// TODO", "", "/tmp/a.kt", 1, listOf(0L))
        val codeLine = IndexedLine("val x = 1", "val x = 1", "", "/tmp/a.kt", 2, listOf(0L))

        assertTrue(CompletionContextKind.COMMENT.allows(commentLine))
        assertFalse(CompletionContextKind.COMMENT.allows(codeLine))
        assertFalse(CompletionContextKind.STRING.allows(commentLine))
    }
}