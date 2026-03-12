package dima.sweep.localcomplete.listener

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.VirtualFile
import dima.sweep.localcomplete.service.LineIndexService

class FileOpenListener : FileEditorManagerListener {
    private val logger = Logger.getInstance(FileOpenListener::class.java)

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file.isDirectory) return
        if (file.fileType.isBinary) return
        LineIndexService.getInstance().ensureLoadedInBackground()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val content = String(file.contentsToByteArray(), file.charset)
                LineIndexService.getInstance().indexFile(file.path, content, file.extension.orEmpty(), trackSessionChanges = false)
            } catch (t: Throwable) {
                logger.warn("Failed to index opened file: ${file.path}", t)
            }
        }
    }
}