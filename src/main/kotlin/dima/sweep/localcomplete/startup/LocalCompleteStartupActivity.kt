package dima.sweep.localcomplete.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import dima.sweep.localcomplete.listener.CaretCompletionListener
import dima.sweep.localcomplete.service.LineIndexService

class LocalCompleteStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val service = LineIndexService.getInstance()
        service.ensureLoadedInBackground()
        service.registerDocumentListener()
        service.registerCaretListener(CaretCompletionListener())
    }
}