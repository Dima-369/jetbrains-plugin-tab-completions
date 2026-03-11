package dima.sweep.localcomplete.provider

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import dima.sweep.localcomplete.index.ContextHash
import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.IndexedLine
import dima.sweep.localcomplete.service.LineIndexService
import dima.sweep.localcomplete.settings.LocalCompleteSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LocalLineCompletionProvider : DebouncedInlineCompletionProvider() {
    override val id = InlineCompletionProviderID("dima.sweep.localcomplete.LocalLineCompletionProvider")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return LocalCompleteSettings.getInstance().enabled &&
            (event is InlineCompletionEvent.DocumentChange || event is InlineCompletionEvent.DirectCall)
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return LocalCompleteSettings.getInstance().debounceMs.milliseconds
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val snapshot = readAction {
            val document = request.document
            val offset = request.endOffset
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@readAction null
            val lineIndex = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)
            val lineText = document.text.substring(lineStart, lineEnd)
            val caretColumn = offset - lineStart
            if (caretColumn != lineText.trimEnd().length) return@readAction null

            val prefixText = lineText.substring(0, caretColumn)
            val normalizedPrefix = prefixText.trimStart()
            val settings = LocalCompleteSettings.getInstance()
            if (normalizedPrefix.length < settings.minPrefixLength) return@readAction null

            val allLines = document.text.split('\n').map { it.removeSuffix("\r") }
            CursorContext(
                normalizedPrefix = normalizedPrefix,
                leadingWhitespace = prefixText.takeWhile { it == ' ' || it == '\t' },
                fileExtension = file.extension.orEmpty(),
                filePath = file.path,
                projectBasePath = request.editor.project?.basePath,
                contextHash = ContextHash.forLine(allLines, lineIndex),
                lineNumber = lineIndex + 1,
                rawPrefixText = prefixText,
            )
        } ?: return InlineCompletionSuggestion.Empty

        val result = LineIndexService.getInstance().query(snapshot.normalizedPrefix, snapshot).firstOrNull()
            ?: return InlineCompletionSuggestion.Empty
        val completionText = buildCompletionText(result.indexedLine, snapshot)
        if (completionText.isBlank()) return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(completionText))
        }
    }

    private fun buildCompletionText(indexedLine: IndexedLine, context: CursorContext): String {
        val reindentedLine = context.leadingWhitespace + indexedLine.originalContent.trimStart()
        if (!reindentedLine.startsWith(context.rawPrefixText)) return ""
        return reindentedLine.removePrefix(context.rawPrefixText)
    }
}