package dima.sweep.localcomplete.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextHashTest {
    @Test
    fun `hash ignores numbers and string literal values`() {
        val first = listOf(
            "fun sample() {",
            "    val id = 123",
            "    println(\"Hello\")",
            "}",
        )
        val second = listOf(
            "fun sample() {",
            "    val id = 987654",
            "    println(\"World\")",
            "}",
        )

        assertEquals(ContextHash.forLine(first, 3), ContextHash.forLine(second, 3))
        assertEquals(ContextHash.forLineGraduated(first, 3), ContextHash.forLineGraduated(second, 3))
    }

    @Test
    fun `hash still changes when structure changes`() {
        val first = listOf(
            "if (enabled) {",
            "    println(\"Hello\")",
        )
        val second = listOf(
            "while (enabled) {",
            "    println(\"Hello\")",
        )

        assertNotEquals(ContextHash.forLine(first, 1), ContextHash.forLine(second, 1))
    }

    @Test
    fun `graduated hashes include suffix context`() {
        val candidate = listOf(
            "logger.info(\"Success\")",
            "return true;",
        )
        val current = listOf(
            "",
            "return true;",
        )

        val candidateHashes = ContextHash.forLineGraduated(candidate, 0)
        val currentHashes = ContextHash.forLineGraduated(current, 0)

        assertTrue(candidateHashes.isNotEmpty())
        assertTrue(currentHashes.isNotEmpty())
        assertEquals(candidateHashes.last(), currentHashes.last())
    }
}