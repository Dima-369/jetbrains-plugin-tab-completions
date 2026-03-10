package dima.sweep

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionSkipTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.ApplicationManager
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

        val (textBeforeCursor, textAfterCursor, filePath, cursorColInLine) = readAction {
            val text = document.text
            val file = request.file
            val path = file.virtualFile?.name ?: file.name
            val lineNum = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(lineNum)
            val col = offset - lineStart
            CursorInfo(text.substring(0, offset), text.substring(offset), path, col)
        }

        val prompt = buildSweepPrompt(filePath, textBeforeCursor, textAfterCursor)

        val predicted = SweepClient.complete(prompt)
        if (predicted.isNullOrBlank()) {
            publishDetectedEdits(request.editor, emptyList())
            return InlineCompletionSuggestion.Empty
        }

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
        val windowStartOffset = offset - before.length

        val detectedEdits = extractSuggestionByDiff(currentSent, predicted, linesBeforeCursor, cursorColInLine)
            .map { edit ->
                edit.copy(
                    startOffset = (windowStartOffset + edit.startOffset).coerceIn(0, document.textLength),
                    endOffset = (windowStartOffset + edit.endOffset).coerceIn(0, document.textLength),
                )
            }

        publishDetectedEdits(request.editor, detectedEdits)

        SweepClient.log(
            "cursorLine=$linesBeforeCursor cursorCol=$cursorColInLine edits=${detectedEdits.joinToString { "${it.startOffset}-${it.endOffset}:${it.replacement.take(80)}" }}"
        )

        val inlineEdit = detectedEdits.firstOrNull { it.startOffset == offset }
            ?: return InlineCompletionSuggestion.Empty

        val deletedText = if (inlineEdit.endOffset > inlineEdit.startOffset) {
            document.getText(com.intellij.openapi.util.TextRange(inlineEdit.startOffset, inlineEdit.endOffset))
        } else {
            ""
        }

        if (inlineEdit.replacement.isEmpty() && deletedText.isEmpty()) {
            return InlineCompletionSuggestion.Empty
        }

        return InlineCompletionSingleSuggestion.build {
            if (inlineEdit.replacement.isNotEmpty()) {
                emit(InlineCompletionGrayTextElement(inlineEdit.replacement))
            }
            if (deletedText.isNotEmpty()) {
                emit(InlineCompletionSkipTextElement(deletedText))
            }
        }
    }

    private data class CursorInfo(
        val textBefore: String,
        val textAfter: String,
        val filePath: String,
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
    ): List<SweepEdit> {
        val currentLines = currentSent.lines()
        val predictedLines = predicted.lines()

        // The predicted often starts with a leading \n, so the first line is empty
        // Align: find which predicted line corresponds to current line 0
        // by matching the first non-empty current line
        val predictedOffset = findAlignment(currentLines, predictedLines)
        val alignedPredictedLines = predictedLines.drop(predictedOffset)

        SweepClient.log(
            "Alignment offset=$predictedOffset, currentLines=${currentLines.size}, predictedLines=${predictedLines.size}, cursorLine=$cursorLineInWindow, cursorCol=$cursorCol"
        )

        if (alignedPredictedLines.isEmpty()) return emptyList()

        val mismatch = findNearestMismatch(currentLines, alignedPredictedLines, cursorLineInWindow)
            ?: return emptyList()
        val (currentStartLine, predictedStartLine) = mismatch

        val resync = findResyncPoint(currentLines, alignedPredictedLines, currentStartLine, predictedStartLine)
            ?: return emptyList()
        val (currentEndLine, predictedEndLine) = resync

        if (currentStartLine == currentEndLine && predictedStartLine == predictedEndLine) {
            return emptyList()
        }

        SweepClient.log(
            "Local diff current=$currentStartLine..$currentEndLine predicted=$predictedStartLine..$predictedEndLine"
        )

        val lineStartOffsets = computeLineStartOffsets(currentSent)
        val blockStartOffset = lineStartOffsets.getOrElse(currentStartLine) { currentSent.length }
        val blockEndOffset = if (currentEndLine < lineStartOffsets.size) {
            lineStartOffsets[currentEndLine]
        } else {
            currentSent.length
        }

        val currentBlock = currentSent.substring(blockStartOffset, blockEndOffset)
        val predictedBlock = blockText(alignedPredictedLines, predictedStartLine, predictedEndLine)

        val commonPrefixChars = currentBlock.commonPrefixWith(predictedBlock).length
        val currentRemainder = currentBlock.substring(commonPrefixChars)
        val predictedRemainder = predictedBlock.substring(commonPrefixChars)
        val commonSuffixChars = currentRemainder.commonSuffixWith(predictedRemainder).length

        val editStartOffset = blockStartOffset + commonPrefixChars
        val editEndOffset = (blockEndOffset - commonSuffixChars).coerceAtLeast(editStartOffset)
        val replacementEnd = (predictedBlock.length - commonSuffixChars).coerceAtLeast(commonPrefixChars)
        val replacement = predictedBlock.substring(commonPrefixChars, replacementEnd)

        if (editStartOffset == editEndOffset && replacement.isEmpty()) {
            return emptyList()
        }

        return listOf(
            SweepEdit(
                startOffset = editStartOffset,
                endOffset = editEndOffset,
                replacement = replacement,
            )
        )
    }

    private fun findNearestMismatch(
        currentLines: List<String>,
        predictedLines: List<String>,
        cursorLineInWindow: Int,
    ): Pair<Int, Int>? {
        val maxLineIndex = minOf(currentLines.lastIndex, predictedLines.lastIndex)
        if (maxLineIndex < 0) return null

        for (delta in 0..maxLineIndex) {
            for (sign in listOf(0, 1, -1)) {
                val index = cursorLineInWindow + delta * sign
                if (index !in 0..maxLineIndex) continue
                if (currentLines[index] != predictedLines[index]) {
                    return index to index
                }
            }
        }

        if (currentLines.size != predictedLines.size) {
            val index = minOf(cursorLineInWindow.coerceAtLeast(0), minOf(currentLines.size, predictedLines.size))
            return index to index
        }

        return null
    }

    private fun findResyncPoint(
        currentLines: List<String>,
        predictedLines: List<String>,
        currentStartLine: Int,
        predictedStartLine: Int,
    ): Pair<Int, Int>? {
        val maxSearchLines = 8

        for (distance in 1..maxSearchLines) {
            for (currentDelta in 0..distance) {
                val predictedDelta = distance - currentDelta
                val currentIndex = currentStartLine + currentDelta
                val predictedIndex = predictedStartLine + predictedDelta

                if (isResyncCandidate(currentLines, predictedLines, currentIndex, predictedIndex)) {
                    return currentIndex to predictedIndex
                }
            }
        }

        return null
    }

    private fun isResyncCandidate(
        currentLines: List<String>,
        predictedLines: List<String>,
        currentIndex: Int,
        predictedIndex: Int,
    ): Boolean {
        if (currentIndex > currentLines.size || predictedIndex > predictedLines.size) {
            return false
        }

        if (currentIndex == currentLines.size || predictedIndex == predictedLines.size) {
            return false
        }

        val remainingCurrent = currentLines.size - currentIndex
        val remainingPredicted = predictedLines.size - predictedIndex
        val anchorLength = minOf(2, remainingCurrent, remainingPredicted)
        if (anchorLength == 0) {
            return false
        }

        return (0 until anchorLength).all { delta ->
            currentLines[currentIndex + delta] == predictedLines[predictedIndex + delta]
        }
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

    private fun computeLineStartOffsets(text: String): List<Int> {
        val offsets = mutableListOf(0)
        text.forEachIndexed { index, ch ->
            if (ch == '\n') {
                offsets.add(index + 1)
            }
        }
        return offsets
    }

    private fun blockText(lines: List<String>, startLine: Int, endLine: Int): String {
        if (startLine >= endLine) return ""

        val text = lines.subList(startLine, endLine).joinToString("\n")
        return if (endLine < lines.size) "$text\n" else text
    }

    private fun publishDetectedEdits(editor: com.intellij.openapi.editor.Editor, edits: List<SweepEdit>) {
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            SweepSuggestedEditSupport.update(editor, edits)
        }
    }
}
