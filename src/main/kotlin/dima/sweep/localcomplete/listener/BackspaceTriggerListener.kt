package dima.sweep.localcomplete.listener

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager

class BackspaceTriggerListener : AnActionListener {
    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: com.intellij.openapi.actionSystem.AnActionResult) {
        val actionId = ActionManager.getInstance().getId(action) ?: return
        if (actionId !in retriggerActionIds) return

        val editor = CommonDataKeys.EDITOR.getData(event.dataContext) ?: return
        val project = editor.project ?: return
        if (project.isDisposed) return

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || editor.isDisposed) return@invokeLater
            val caret = editor.caretModel.currentCaret
            InlineCompletion.getHandlerOrNull(editor)?.invoke(
                InlineCompletionEvent.DirectCall(editor, caret, event.dataContext)
            )
        }
    }

    companion object {
        private val retriggerActionIds = setOf(
            IdeActions.ACTION_EDITOR_BACKSPACE,
            IdeActions.ACTION_EDITOR_DELETE,
            IdeActions.ACTION_EDITOR_DELETE_TO_WORD_START,
            IdeActions.ACTION_EDITOR_DELETE_TO_WORD_END,
        )
    }
}