package dima.sweep.localcomplete.provider

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import dima.sweep.localcomplete.LocalCompleteKeys
import dima.sweep.localcomplete.index.ContextHash
import dima.sweep.localcomplete.index.LinePrefixMatcher
import dima.sweep.localcomplete.model.CompletionContextKind
import dima.sweep.localcomplete.model.CursorContext
import dima.sweep.localcomplete.model.IndexedLine
import dima.sweep.localcomplete.service.LineIndexService
import dima.sweep.localcomplete.settings.LocalCompleteSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class LocalLineCompletionProvider : DebouncedInlineCompletionProvider() {
    private val logger = Logger.getInstance(LocalLineCompletionProvider::class.java)

    @Volatile private var lastSuggestion: StableSuggestion? = null

    private data class StableSuggestion(
        val filePath: String,
        val lineNumber: Int,
        val fullCompletedLine: String,
        val timestamp: Long,
    )

    private data class SuggestionSnapshot(
        val cursorContext: CursorContext,
        val documentText: String,
        val activeLineNumbers: Set<Int>,
    )

    override val id = InlineCompletionProviderID("dima.sweep.localcomplete.LocalLineCompletionProvider")

    override val insertHandler: InlineCompletionInsertHandler = object : DefaultInlineCompletionInsertHandler() {
        override fun afterInsertion(environment: InlineCompletionInsertEnvironment, elements: List<InlineCompletionElement>) {
            try {
                super.afterInsertion(environment, elements)

                val editor = environment.editor
                val document = editor.document
                val offset = editor.caretModel.offset
                val lineNumber = document.getLineNumber(offset)
                val lineStart = document.getLineStartOffset(lineNumber)
                val lineEnd = document.getLineEndOffset(lineNumber)
                val fullLine = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
                val file = FileDocumentManager.getInstance().getFile(document)

                // Record the accepted line for future scoring
                if (file != null) {
                    val indexedLine = IndexedLine(
                        normalizedContent = LinePrefixMatcher.normalizeForLookup(fullLine),
                        originalContent = fullLine,
                        leadingWhitespace = fullLine.takeWhile { it == ' ' || it == '\t' },
                        sourceFilePath = file.path,
                        lineNumber = lineNumber + 1,
                        prefixContextHashes = emptyList(),
                        suffixContextHashes = emptyList(),
                    )
                    LineIndexService.getInstance().acceptLine(indexedLine)
                }

                val settings = LocalCompleteSettings.getInstance()
                if (!settings.moveCaretDownOnTabAccept) return
                if (editor.getUserData(LocalCompleteKeys.TAB_ACCEPT_IN_PROGRESS) != true) return

                if (offset != document.getLineEndOffset(lineNumber)) return

                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater

                    val caret = editor.caretModel.currentCaret
                    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
                    runCatching {
                        EditorActionManager.getInstance().getActionHandler(IdeActions.ACTION_EDITOR_ENTER)
                            .execute(editor, caret, dataContext)
                    }.onFailure {
                        logger.warn("Failed to move caret to next line after inline completion insertion", it)
                    }
                }
            } finally {
                environment.editor.putUserData(LocalCompleteKeys.TAB_ACCEPT_IN_PROGRESS, null)
            }
        }
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return LocalCompleteSettings.getInstance().enabled &&
            (event is InlineCompletionEvent.DocumentChange || event is InlineCompletionEvent.DirectCall)
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return LocalCompleteSettings.getInstance().debounceMs.milliseconds
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val result = readAction {
            val document = request.document
            val fullText = document.text
            val offset = request.endOffset
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@readAction null
            val completionContextKind = detectCompletionContext(request, document, offset)
            if (completionContextKind == CompletionContextKind.STRING) return@readAction null
            val lineIndex = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(lineIndex)
            val lineEnd = document.getLineEndOffset(lineIndex)
            val lineText = fullText.substring(lineStart, lineEnd)
            val caretColumn = offset - lineStart

            val prefixText = lineText.substring(0, caretColumn)
            val suffixText = lineText.substring(caretColumn)
            val normalizedPrefix = prefixText.trimStart()
            val settings = LocalCompleteSettings.getInstance()
            val allowBlankLineCompletion = normalizedPrefix.isEmpty() && lineText.isBlank()
            if (!allowBlankLineCompletion && normalizedPrefix.length < settings.minPrefixLength) return@readAction null
            val activeLineNumbers = request.editor.caretModel.allCarets
                .map { caret -> document.getLineNumber(caret.offset) + 1 }
                .toSet()

            val allLines = fullText.split('\n').map { it.removeSuffix("\r") }
            val prefixHashes = ContextHash.prefixHashesForLine(allLines, lineIndex)
            val suffixHashes = ContextHash.suffixHashesForLine(allLines, lineIndex)
            val nextNonBlankLineNormalized = if (allowBlankLineCompletion) {
                findNextNonBlankLineNormalized(allLines, lineIndex)
            } else {
                ""
            }
            val nearbyNormalizedLines = collectNearbyNormalizedLines(allLines, lineIndex)
            SuggestionSnapshot(
                cursorContext = CursorContext(
                normalizedPrefix = normalizedPrefix,
                leadingWhitespace = prefixText.takeWhile { it == ' ' || it == '\t' },
                completionContextKind = completionContextKind,
                fileExtension = file.extension.orEmpty(),
                filePath = file.path,
                projectBasePath = request.editor.project?.basePath,
                prefixContextHashes = prefixHashes,
                suffixContextHashes = suffixHashes,
                lineNumber = lineIndex + 1,
                rawPrefixText = prefixText,
                rawSuffixText = suffixText,
                nextNonBlankLineNormalized = nextNonBlankLineNormalized,
                nearbyNormalizedLines = nearbyNormalizedLines,
                ),
                documentText = fullText,
                activeLineNumbers = activeLineNumbers,
            )
        } ?: return InlineCompletionSuggestion.Empty

        val snapshot = result
        val cursorContext = snapshot.cursorContext

        // Check if cached suggestion is still valid before re-querying
        val cached = lastSuggestion
        if (cached != null &&
            cached.filePath == cursorContext.filePath &&
            cached.lineNumber == cursorContext.lineNumber
        ) {
            val prefixMatchEnd = LinePrefixMatcher.findMatchEnd(cached.fullCompletedLine, cursorContext.rawPrefixText)
            if (prefixMatchEnd != null) {
                val remaining = cached.fullCompletedLine.substring(prefixMatchEnd)
                val adjusted = LinePrefixMatcher.removeSuffixOverlap(remaining, cursorContext.rawSuffixText)
                if (adjusted != null && adjusted.isNotEmpty() && !adjusted.isBlank()) {
                    return InlineCompletionSingleSuggestion.build {
                        emit(InlineCompletionGrayTextElement(adjusted))
                    }
                }
            }
        }

        LineIndexService.getInstance().indexFile(
            cursorContext.filePath,
            snapshot.documentText,
            cursorContext.fileExtension,
            trackSessionChanges = true,
            activeLineNumbers = snapshot.activeLineNumbers,
        )

        val completionText = LineIndexService.getInstance()
            .query(cursorContext.normalizedPrefix, cursorContext)
            .firstNotNullOfOrNull { rankedCompletion ->
                buildCompletionText(rankedCompletion.indexedLine, cursorContext)
                    ?.takeIf { it.isNotEmpty() }
            } ?: return InlineCompletionSuggestion.Empty
        if (completionText.isBlank()) return InlineCompletionSuggestion.Empty

        // Update cache with the new suggestion
        lastSuggestion = StableSuggestion(
            filePath = cursorContext.filePath,
            lineNumber = cursorContext.lineNumber,
            fullCompletedLine = cursorContext.rawPrefixText + completionText,
            timestamp = System.currentTimeMillis(),
        )

        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(completionText))
        }
    }

    private fun buildCompletionText(indexedLine: IndexedLine, context: CursorContext): String? {
        val reindentedLine = context.leadingWhitespace + indexedLine.originalContent.trimStart()

        // Try full-line prefix match first (existing logic)
        val prefixMatchEnd = LinePrefixMatcher.findMatchEnd(reindentedLine, context.rawPrefixText)
        if (prefixMatchEnd != null) {
            val remaining = reindentedLine.substring(prefixMatchEnd)
            return LinePrefixMatcher.removeSuffixOverlap(remaining, context.rawSuffixText)
        }

        // Mid-line fallback: find where the user's trailing token appears in the candidate
        return buildMidLineCompletion(reindentedLine, context)
    }

    private fun buildMidLineCompletion(candidateLine: String, context: CursorContext): String? {
        // Extract the trailing typed fragment (e.g., "user.getName" from "val x = user.getName")
        val prefix = context.rawPrefixText.trimEnd()
        if (prefix.length < 4) return null

        // Try progressively shorter suffixes of what the user typed
        // to find where it aligns within the candidate line
        val maxSuffixScan = minOf(prefix.length, 60)
        for (len in maxSuffixScan downTo 4) {
            val tail = prefix.substring(prefix.length - len)
            val matchIndex = candidateLine.indexOf(tail, ignoreCase = true)
            if (matchIndex >= 0) {
                val completionStart = matchIndex + tail.length
                if (completionStart >= candidateLine.length) return null
                val remaining = candidateLine.substring(completionStart)
                if (remaining.isBlank()) return null
                return LinePrefixMatcher.removeSuffixOverlap(remaining, context.rawSuffixText)
            }
        }
        return null
    }

    private fun findNextNonBlankLineNormalized(allLines: List<String>, lineIndex: Int): String {
        for (nextIndex in (lineIndex + 1) until allLines.size) {
            val normalized = LinePrefixMatcher.normalizeForLookup(allLines[nextIndex])
            if (normalized.isNotEmpty()) {
                return normalized
            }
        }
        return ""
    }

    private fun collectNearbyNormalizedLines(allLines: List<String>, lineIndex: Int): Set<String> {
        val result = mutableSetOf<String>()
        val windowSize = 3

        for (i in (lineIndex - windowSize) until lineIndex) {
            if (i >= 0) {
                val normalized = LinePrefixMatcher.normalizeForLookup(allLines[i])
                if (normalized.isNotEmpty()) {
                    result.add(normalized)
                }
            }
        }

        for (i in (lineIndex + 1) until minOf(lineIndex + 1 + windowSize, allLines.size)) {
            val normalized = LinePrefixMatcher.normalizeForLookup(allLines[i])
            if (normalized.isNotEmpty()) {
                result.add(normalized)
            }
        }

        return result
    }

    private fun detectCompletionContext(
        request: InlineCompletionRequest,
        document: com.intellij.openapi.editor.Document,
        offset: Int,
    ): CompletionContextKind {
        val project = request.editor.project ?: return CompletionContextKind.UNKNOWN
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return CompletionContextKind.UNKNOWN
        val safeOffset = when {
            offset < document.textLength -> offset
            offset > 0 -> offset - 1
            else -> 0
        }
        val elementAtCaret = psiFile.findElementAt(safeOffset) ?: return CompletionContextKind.UNKNOWN
        if (elementAtCaret is PsiComment || PsiTreeUtil.getParentOfType(elementAtCaret, PsiComment::class.java, false) != null) {
            return CompletionContextKind.COMMENT
        }

        val elementType = elementAtCaret.node?.elementType?.toString()?.uppercase().orEmpty()
        return when {
            "COMMENT" in elementType -> CompletionContextKind.COMMENT
            "STRING" in elementType || "CHARACTER" in elementType -> CompletionContextKind.STRING
            else -> CompletionContextKind.CODE
        }
    }
}