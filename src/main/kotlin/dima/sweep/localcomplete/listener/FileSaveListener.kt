package dima.sweep.localcomplete.listener

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import dima.sweep.localcomplete.service.LineIndexService

class FileSaveListener : FileDocumentManagerListener {
    private val logger = Logger.getInstance(FileSaveListener::class.java)

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.fileType.isBinary) return
        val text = document.text

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                LineIndexService.getInstance().indexFile(file.path, text, file.extension.orEmpty(), trackSessionChanges = true)
            } catch (t: Throwable) {
                logger.warn("Failed to index saved file: ${file.path}", t)
            }
        }
    }
}