package dima.sweep.localcomplete.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class LinePrefixMatcherTest {
    @Test
    fun `matches prefixes even when whitespace differs`() {
        val matchEnd = LinePrefixMatcher.findMatchEnd("    val x = compute()", "    val x=")

        assertNotNull(matchEnd)
        assertEquals(" compute()", "    val x = compute()".substring(matchEnd!!))
    }

    @Test
    fun `removes overlapping typed suffix`() {
        assertEquals("callSomething(", LinePrefixMatcher.removeSuffixOverlap("callSomething())", "))"))
        assertEquals("result = build", LinePrefixMatcher.removeSuffixOverlap("result = build})", "})"))
    }

    @Test
    fun `normalizes prefixes without whitespace`() {
        assertEquals("valx=foo", LinePrefixMatcher.normalizeForLookup("  val x = foo"))
        assertFalse(LinePrefixMatcher.normalizeForLookup("   ").isNotEmpty())
    }
}