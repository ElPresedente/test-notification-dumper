package space.rolark.notificationdumper

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class DumpListener : NotificationListenerService() {
    private val recorder get() = Recorder.get(this)
    override fun onListenerConnected() {
        live = this
        recorder.append("listener_connected")
        snapshot("reconnect")
    }
    override fun onListenerDisconnected() {
        recorder.append("listener_disconnected")
        live = null
    }
    override fun onDestroy() { if (live === this) live = null; super.onDestroy() }
    fun snapshot(reason: String) {
        if (!recorder.active) return
        try {
            activeNotifications?.forEach { s ->
                capture("snapshot", s, currentRanking, null, reason)
            }
        } catch (e: Exception) { recorder.append("snapshot_error", NotificationJson.obj("error" to e.toString())) }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        capture("posted", sbn, rankingMap)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        capture("removed", sbn, rankingMap, reason)
    }
    private fun capture(kind: String, sbn: StatusBarNotification, ranks: RankingMap?, reason: Int? = null, snapshot: String? = null) {
        if (!recorder.active) return
        try {
            val json = NotificationJson.event(sbn, ranks)
            reason?.let { json.put("removalReason", it) }
            snapshot?.let { json.put("snapshotReason", it) }
            recorder.append(kind, json)
        } catch (e: Exception) {
            recorder.append("serialization_error", NotificationJson.obj("package" to sbn.packageName,
                "key" to sbn.key, "originalEvent" to kind, "error" to e.toString()))
        }
    }
    companion object { @Volatile var live: DumpListener? = null; private set }
}
