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
        val document = request.document
        val offset = request.endOffset

        val (textBeforeCursor, textAfterCursor, filePath, cursorLineIndex, cursorColInLine) = readAction {
            val text = document.text
            val file = request.file
            val path = file.virtualFile?.name ?: file.name
            val lineNum = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(lineNum)
            val col = offset - lineStart
            CursorInfo(text.substring(0, offset), text.substring(offset), path, lineNum, col)
        }

        val prompt = buildSweepPrompt(filePath, textBeforeCursor, textAfterCursor)

        val predicted = SweepClient.complete(prompt) ?: return InlineCompletionSuggestion.Empty

        // The "current" content we sent to the model
        val maxBeforeChars = 3000
        val maxAfterChars = 3000
        val before = if (textBeforeCursor.length > maxBeforeChars) {
            textBeforeCursor.takeLast(maxBeforeChars)
        } else {
            textBeforeCursor
        }
        val afterSnippet = textAfterCursor.take(maxAfterChars)
        val currentSent = before + afterSnippet

        // Find cursor line within the window we sent
        val linesBeforeCursor = before.count { it == '\n' }

        val suggestion = extractSuggestionByDiff(currentSent, predicted, linesBeforeCursor, cursorColInLine)

        SweepClient.log("cursorLine=$linesBeforeCursor cursorCol=$cursorColInLine suggestion='${suggestion?.take(200)}'")

        if (suggestion.isNullOrBlank()) return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(suggestion))
        }
    }

    private data class CursorInfo(
        val textBefore: String,
        val textAfter: String,
        val filePath: String,
        val cursorLine: Int,
        val cursorCol: Int
    )

    private fun buildSweepPrompt(
        filePath: String,
        textBeforeCursor: String,
        textAfterCursor: String,
    ): String {
        val maxBeforeChars = 3000
        val maxAfterChars = 3000
        val before = if (textBeforeCursor.length > maxBeforeChars) {
            textBeforeCursor.takeLast(maxBeforeChars)
        } else {
            textBeforeCursor
        }

        // For "original", strip the last line being typed (simulates "before this edit")
        val lastNewline = before.lastIndexOf('\n')
        val original = if (lastNewline >= 0) before.substring(0, lastNewline + 1) else ""

        val afterSnippet = textAfterCursor.take(maxAfterChars)
        val current = before + afterSnippet

        val parts = mutableListOf<String>()
        parts.add("<|file_sep|>original/$filePath")
        parts.add(original + afterSnippet)
        parts.add("<|file_sep|>current/$filePath")
        parts.add(current)
        parts.add("<|file_sep|>updated/$filePath")
        return parts.joinToString("\n")
    }

    private fun extractSuggestionByDiff(
        currentSent: String,
        predicted: String,
        cursorLineInWindow: Int,
        cursorCol: Int,
    ): String? {
        val currentLines = currentSent.lines()
        val predictedLines = predicted.lines()

        // The predicted often starts with a leading \n, so the first line is empty
        // Align: find which predicted line corresponds to current line 0
        // by matching the first non-empty current line
        val predictedOffset = findAlignment(currentLines, predictedLines)

        SweepClient.log("Alignment offset=$predictedOffset, currentLines=${currentLines.size}, predictedLines=${predictedLines.size}")

        // Walk lines around the cursor looking for differences
        for (delta in 0..5) {
            for (sign in listOf(0, 1, -1)) {
                val cIdx = cursorLineInWindow + delta * sign
                if (cIdx < 0 || cIdx >= currentLines.size) continue

                val pIdx = cIdx + predictedOffset
                if (pIdx < 0 || pIdx >= predictedLines.size) continue

                val curLine = currentLines[cIdx]
                val predLine = predictedLines[pIdx]

                if (curLine != predLine) {
                    // Found a difference!
                    if (cIdx == cursorLineInWindow) {
                        // Difference on cursor line: suggest text after cursor column
                        val prefix = curLine.substring(0, minOf(cursorCol, curLine.length))
                        val commonLen = predLine.commonPrefixWith(prefix).length

                        if (commonLen >= cursorCol || cursorCol <= prefix.length) {
                            // The predicted line extends beyond what's typed
                            val insertAt = minOf(cursorCol, predLine.length)
                            val newText = predLine.substring(insertAt)
                            if (newText.isNotEmpty()) {
                                // Also gather any inserted lines after this one
                                val extraLines = gatherExtraLines(currentLines, predictedLines, cIdx, pIdx, predictedOffset)
                                val result = newText + extraLines
                                return result.take(300).trimEnd().ifEmpty { null }
                            }
                        }
                    } else if (cIdx > cursorLineInWindow) {
                        // Change is after cursor - might be an insertion
                        // Check if predicted has extra lines inserted here
                        val insertedText = predLine
                        if (insertedText.isNotBlank()) {
                            return insertedText.take(300).trimEnd().ifEmpty { null }
                        }
                    }
                }
            }
        }

        // Check if predicted has more lines (new lines added)
        val expectedPredEnd = currentLines.size + predictedOffset
        if (predictedLines.size > expectedPredEnd) {
            val newLines = predictedLines.subList(expectedPredEnd, minOf(expectedPredEnd + 3, predictedLines.size))
            val text = newLines.joinToString("\n")
            if (text.isNotBlank()) return "\n$text".take(300)
        }

        return null
    }

    private fun findAlignment(currentLines: List<String>, predictedLines: List<String>): Int {
        // The predicted output sometimes starts with \n<?php etc.
        // Find offset so that currentLines[0] == predictedLines[offset]
        if (currentLines.isEmpty() || predictedLines.isEmpty()) return 0

        val firstNonEmpty = currentLines.firstOrNull { it.isNotBlank() } ?: return 0
        for (i in 0..minOf(5, predictedLines.size - 1)) {
            if (predictedLines[i].trim() == firstNonEmpty.trim()) {
                return i
            }
        }
        return 0
    }

    private fun gatherExtraLines(
        currentLines: List<String>,
        predictedLines: List<String>,
        curLineIdx: Int,
        predLineIdx: Int,
        offset: Int
    ): String {
        // Check if predicted has extra lines inserted after the current changed line
        val sb = StringBuilder()
        var pIdx = predLineIdx + 1
        var cIdx = curLineIdx + 1

        // Look for lines in predicted that don't match current (inserted lines)
        var count = 0
        while (pIdx < predictedLines.size && count < 3) {
            if (cIdx < currentLines.size && predictedLines[pIdx].trim() == currentLines[cIdx].trim()) {
                break // back in sync
            }
            sb.append("\n").append(predictedLines[pIdx])
            pIdx++
            count++
        }

        return sb.toString()
    }
}
