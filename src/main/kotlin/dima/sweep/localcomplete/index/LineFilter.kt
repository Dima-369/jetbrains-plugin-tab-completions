package dima.sweep.localcomplete.index

object LineFilter {
    private val trivialTokens = setOf("/*", "*/", "//", "*")
    private val singleCharTokens = setOf('{', '}', '(', ')', '[', ']', ';')

    fun shouldSkip(normalizedContent: String, rawLength: Int, maxLineLength: Int): Boolean {
        if (normalizedContent.isBlank()) return true
        if (rawLength > maxLineLength) return true
        if (normalizedContent.length == 1 && normalizedContent[0] in singleCharTokens) return true
        if (normalizedContent.length <= 2 && normalizedContent in trivialTokens) return true
        return false
    }

    fun bracketBalancePenalty(content: String, rawSuffixText: String): Double {
        val candidateBalance = bracketBalance(content)
        if (candidateBalance.isBalanced()) return 1.0

        val combinedBalance = candidateBalance + bracketBalance(rawSuffixText)
        return if (combinedBalance.isBalanced()) 1.0 else 0.5
    }

    private fun bracketBalance(content: String): BracketBalance {
        var curly = 0
        var round = 0
        var square = 0
        for (character in content) {
            when (character) {
                '{' -> curly++
                '}' -> curly--
                '(' -> round++
                ')' -> round--
                '[' -> square++
                ']' -> square--
            }
        }
        return BracketBalance(curly, round, square)
    }

    private data class BracketBalance(
        val curly: Int,
        val round: Int,
        val square: Int,
    ) {
        fun isBalanced(): Boolean = curly == 0 && round == 0 && square == 0

        operator fun plus(other: BracketBalance): BracketBalance {
            return BracketBalance(
                curly = curly + other.curly,
                round = round + other.round,
                square = square + other.square,
            )
        }
    }
}