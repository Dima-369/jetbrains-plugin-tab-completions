package dima.sweep.localcomplete.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import dima.sweep.localcomplete.service.LineIndexService

class LocalCompleteStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        LineIndexService.getInstance().ensureLoadedInBackground()
    }
}