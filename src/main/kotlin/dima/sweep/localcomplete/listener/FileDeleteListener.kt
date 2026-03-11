package dima.sweep.localcomplete.listener

import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dima.sweep.localcomplete.service.LineIndexService

class FileDeleteListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val service = LineIndexService.getInstance()
        events.filterIsInstance<VFileDeleteEvent>().forEach { event ->
            service.removeFile(event.path)
        }
    }
}