package dima.sweep.localcomplete.index

import org.junit.Assert.assertEquals
import org.junit.Test

class LineFilterTest {
    @Test
    fun `penalizes unbalanced candidates`() {
        assertEquals(0.5, LineFilter.bracketBalancePenalty("if (user != null) {", ""), 0.0)
        assertEquals(0.5, LineFilter.bracketBalancePenalty("items.map(", ""), 0.0)
    }

    @Test
    fun `does not penalize when suffix already closes brackets`() {
        assertEquals(1.0, LineFilter.bracketBalancePenalty("if (user != null) {", "}"), 0.0)
        assertEquals(1.0, LineFilter.bracketBalancePenalty("call(", ")"), 0.0)
    }
}