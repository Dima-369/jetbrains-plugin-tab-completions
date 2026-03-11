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
}