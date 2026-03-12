package dima.sweep.localcomplete.model

enum class CompletionContextKind {
    CODE,
    COMMENT,
    STRING,
    UNKNOWN,
    ;

    fun allows(line: IndexedLine): Boolean {
        return when (this) {
            STRING -> false
            COMMENT -> classifyLine(line.originalContent) == COMMENT
            CODE, UNKNOWN -> classifyLine(line.originalContent) != COMMENT
        }
    }

    companion object {
        fun classifyLine(content: String): CompletionContextKind {
            val trimmed = content.trimStart()
            return when {
                trimmed.startsWith("//") ||
                    trimmed.startsWith("/*") ||
                    trimmed.startsWith("*/") ||
                    isCommentContinuation(trimmed) -> COMMENT
                trimmed.startsWith("\"") || trimmed.startsWith("'") -> STRING
                else -> CODE
            }
        }

        private fun isCommentContinuation(trimmed: String): Boolean {
            if (!trimmed.startsWith("* ")) return false
            val firstContentChar = trimmed.drop(2).firstOrNull { !it.isWhitespace() } ?: return false
            return !firstContentChar.isDigit() && firstContentChar !in "+-*/%=&|<>!?"
        }
    }
}