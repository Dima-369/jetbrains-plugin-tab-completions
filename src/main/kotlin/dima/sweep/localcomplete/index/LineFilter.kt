package dima.sweep.localcomplete.index

object LineFilter {
    private val trivialTokens = setOf("/*", "*/", "//", "*")
    private val trivialKeywords = setOf("return", "return;", "break", "break;", "continue", "continue;")
    private val singleCharTokens = setOf('{', '}', '(', ')', '[', ']', ';')

    fun shouldSkip(normalizedContent: String, rawLength: Int, maxLineLength: Int): Boolean {
        if (normalizedContent.isBlank()) return true
        if (rawLength > maxLineLength) return true
        if (normalizedContent.length == 1 && normalizedContent[0] in singleCharTokens) return true
        if (normalizedContent.length <= 2 && normalizedContent in trivialTokens) return true
        if (normalizedContent in trivialKeywords) return true
        return false
    }

    fun bracketBalancePenalty(content: String, rawSuffixText: String): Double {
        val candidateBalance = bracketBalance(content)
        if (candidateBalance.isBalanced()) return 1.0

        val combinedBalance = bracketBalance(content + rawSuffixText)
        return if (combinedBalance.isBalanced()) 1.0 else 0.5
    }

    private fun bracketBalance(content: String): BracketBalance {
        var curly = 0
        var minCurly = 0
        var round = 0
        var minRound = 0
        var square = 0
        var minSquare = 0
        for (character in content) {
            when (character) {
                '{' -> curly++
                '}' -> curly--
                '(' -> round++
                ')' -> round--
                '[' -> square++
                ']' -> square--
            }
            minCurly = minOf(minCurly, curly)
            minRound = minOf(minRound, round)
            minSquare = minOf(minSquare, square)
        }
        return BracketBalance(curly, minCurly, round, minRound, square, minSquare)
    }

    private data class BracketBalance(
        val curly: Int,
        val minCurly: Int,
        val round: Int,
        val minRound: Int,
        val square: Int,
        val minSquare: Int,
    ) {
        fun isBalanced(): Boolean {
            return curly == 0 && round == 0 && square == 0 &&
                minCurly >= 0 && minRound >= 0 && minSquare >= 0
        }
    }
}