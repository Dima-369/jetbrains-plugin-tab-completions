package dima.sweep.localcomplete.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineFilterTest {
    @Test
    fun `skips trivial control-flow boilerplate only`() {
        assertTrue(LineFilter.shouldSkip("return;", 7, 200))
        assertTrue(LineFilter.shouldSkip("break;", 6, 200))
        assertTrue(LineFilter.shouldSkip("continue;", 9, 200))
        assertFalse(LineFilter.shouldSkip("i++", 3, 200))
        assertFalse(LineFilter.shouldSkip("a=1", 3, 200))
    }

    @Test
    fun `penalizes unbalanced candidates`() {
        assertEquals(0.5, LineFilter.bracketBalancePenalty("if (user != null) {", ""), 0.0)
        assertEquals(0.5, LineFilter.bracketBalancePenalty("items.map(", ""), 0.0)
        assertEquals(0.5, LineFilter.bracketBalancePenalty("} else {", "}"), 0.0)
    }

    @Test
    fun `does not penalize when suffix already closes brackets`() {
        assertEquals(1.0, LineFilter.bracketBalancePenalty("if (user != null) {", "}"), 0.0)
        assertEquals(1.0, LineFilter.bracketBalancePenalty("call(", ")"), 0.0)
    }
}