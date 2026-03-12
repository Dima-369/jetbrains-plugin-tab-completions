package dima.sweep.localcomplete.listener

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener

class CaretCompletionListener : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        if (project.isDisposed || editor.isDisposed) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || editor.isDisposed) return@invokeLater

            val handler = InlineCompletion.getHandlerOrNull(editor) ?: return@invokeLater
            val caret = event.caret ?: editor.caretModel.currentCaret
            val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
            handler.invoke(InlineCompletionEvent.DirectCall(editor, caret, dataContext))
        }
    }
}