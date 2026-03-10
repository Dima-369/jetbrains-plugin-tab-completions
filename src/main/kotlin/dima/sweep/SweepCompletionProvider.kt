package dima.sweep

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SweepCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID("dima.sweep.SweepCompletionProvider")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return event is InlineCompletionEvent.DocumentChange ||
                event is InlineCompletionEvent.DirectCall
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration =
        500.milliseconds

    override suspend fun getSuggestionDebounced(
        request: InlineCompletionRequest
    ): InlineCompletionSuggestion {
        val editor = request.editor
        val document = request.document
        val offset = request.endOffset

        val (currentContent, filePath, linePrefix) = readAction {
            val text = document.text
            val file = request.file
            val path = file.virtualFile?.path ?: file.name
            val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
            val prefix = text.substring(lineStart, offset)
            Triple(text, path, prefix)
        }

        val prompt = buildSweepPrompt(
            filePath = filePath,
            currentContent = currentContent,
        )

        val predicted = SweepClient.complete(prompt) ?: return InlineCompletionSuggestion.Empty

        // Compute the diff: find what text was added/changed compared to current
        val suggestion = computeInlineSuggestion(currentContent, predicted, offset)
            ?: return InlineCompletionSuggestion.Empty

        if (suggestion.isBlank()) return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(suggestion))
        }
    }

    private fun buildSweepPrompt(
        filePath: String,
        currentContent: String,
    ): String {
        // Minimal prompt: just current file as both original and current
        // The model predicts the "updated" version
        val parts = mutableListOf<String>()
        parts.add("<|file_sep|>original/$filePath")
        parts.add(currentContent)
        parts.add("<|file_sep|>current/$filePath")
        parts.add(currentContent)
        parts.add("<|file_sep|>updated/$filePath")
        return parts.joinToString("\n")
    }

    private fun computeInlineSuggestion(
        currentContent: String,
        predictedContent: String,
        caretOffset: Int
    ): String? {
        // Find the first difference between current and predicted
        val currentLines = currentContent.lines()
        val predictedLines = predictedContent.lines()

        // Find the line the caret is on
        var charCount = 0
        var caretLine = 0
        for ((i, line) in currentLines.withIndex()) {
            val lineEnd = charCount + line.length + 1 // +1 for \n
            if (caretOffset <= charCount + line.length) {
                caretLine = i
                break
            }
            charCount = lineEnd
            if (i == currentLines.lastIndex) caretLine = i
        }

        // Look for differences at or after the caret line
        for (i in caretLine until minOf(currentLines.size, predictedLines.size)) {
            val cur = currentLines[i]
            val pred = predictedLines[i]
            if (cur != pred) {
                // Find the column where they diverge
                val commonPrefix = cur.commonPrefixWith(pred)
                val caretCol = if (i == caretLine) caretOffset - charCountForLine(currentContent, i) else 0

                // Only suggest if the divergence is at or after caret position on the caret line
                if (i == caretLine && commonPrefix.length < caretCol) continue

                val insertionText = pred.substring(commonPrefix.length)
                if (insertionText.isNotEmpty()) {
                    // Also include any additional new lines from predicted
                    val extraLines = if (predictedLines.size > currentLines.size) {
                        val from = minOf(i + 1, predictedLines.size)
                        val to = minOf(i + 1 + (predictedLines.size - currentLines.size), predictedLines.size)
                        if (from < to) "\n" + predictedLines.subList(from, to).joinToString("\n") else ""
                    } else ""
                    return insertionText + extraLines
                }
            }
        }

        // If predicted has more lines than current
        if (predictedLines.size > currentLines.size) {
            val newLines = predictedLines.subList(currentLines.size, predictedLines.size)
            val newText = newLines.joinToString("\n")
            if (newText.isNotBlank()) return "\n" + newText
        }

        return null
    }

    private fun charCountForLine(text: String, lineIndex: Int): Int {
        var count = 0
        for ((i, line) in text.lines().withIndex()) {
            if (i == lineIndex) return count
            count += line.length + 1
        }
        return count
    }
}
