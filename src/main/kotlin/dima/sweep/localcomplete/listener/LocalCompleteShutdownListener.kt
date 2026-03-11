package dima.sweep.localcomplete.listener

import com.intellij.ide.AppLifecycleListener
import dima.sweep.localcomplete.service.LineIndexService

class LocalCompleteShutdownListener : AppLifecycleListener {
    override fun appWillBeClosed(isRestart: Boolean) {
        LineIndexService.getInstance().flushNow()
    }
}