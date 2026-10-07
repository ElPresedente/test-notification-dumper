package space.rolark.notificationdumper

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Array as ReflectArray

/** Public API data only; no hidden-API reflection or invocation of PendingIntents. */
object NotificationJson {
    fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun text(value: CharSequence): Any = if (value.length <= 262144) value.toString()
        else obj("text" to value.take(262144).toString(), "truncated" to true, "originalLength" to value.length)

    @Suppress("DEPRECATION")
    fun value(v: Any?, depth: Int = 0): Any {
        if (v == null) return JSONObject.NULL
        if (depth > 12) return obj("class" to v.javaClass.name, "truncated" to "depth")
        return try {
            when (v) {
                is Bundle -> JSONObject().apply {
                    v.keySet().sorted().forEach { key ->
                        put(key, try {
                            val item = v.get(key)
                            obj("class" to item?.javaClass?.name, "value" to value(item, depth + 1))
                        } catch (e: Exception) { obj("error" to e.toString()) })
                    }
                }
                is CharSequence -> text(v)
                is Boolean, is Byte, is Short, is Int, is Long -> v
                is Float -> if (v.isFinite()) v else v.toString()
                is Double -> if (v.isFinite()) v else v.toString()
                is Bitmap -> obj("class" to v.javaClass.name, "width" to v.width, "height" to v.height, "pixelsOmitted" to true)
                is PendingIntent -> obj("class" to v.javaClass.name, "creatorPackage" to v.creatorPackage, "creatorUid" to v.creatorUid)
                is Person -> obj("name" to value(v.name), "uri" to v.uri, "key" to v.key, "bot" to v.isBot, "important" to v.isImportant)
                is Iterable<*> -> JSONArray().apply {
                    val iterator = v.iterator(); var n = 0
                    while (iterator.hasNext() && n++ < 1000) put(value(iterator.next(), depth + 1))
                    if (iterator.hasNext()) put(obj("truncated" to "items"))
                }
                else -> if (v.javaClass.isArray) JSONArray().apply {
                    val length = ReflectArray.getLength(v)
                    for (i in 0 until minOf(length, 1000)) put(value(ReflectArray.get(v, i), depth + 1))
                    if (length > 1000) put(obj("truncated" to "items", "originalLength" to length))
                } else obj("class" to v.javaClass.name, "representation" to text(v.toString()), "opaque" to true)
            }
        } catch (e: Exception) { obj("class" to v.javaClass.name, "error" to e.toString()) }
    }

    private fun input(r: RemoteInput) = obj("resultKey" to r.resultKey, "label" to value(r.label),
        "choices" to value(r.choices), "allowFreeFormInput" to r.allowFreeFormInput,
        "allowedDataTypes" to value(r.allowedDataTypes), "extras" to value(r.extras))

    @Suppress("DEPRECATION")
    fun notification(n: Notification): JSONObject = obj(
        "when" to n.`when`, "category" to n.category, "channelId" to n.channelId,
        "group" to n.group, "sortKey" to n.sortKey, "flags" to n.flags,
        "priority" to n.priority, "visibility" to n.visibility, "number" to n.number,
        "color" to n.color, "defaults" to n.defaults, "tickerText" to value(n.tickerText),
        "timeoutAfter" to n.timeoutAfter, "shortcutId" to n.shortcutId,
        "badgeIconType" to n.badgeIconType, "groupAlertBehavior" to n.groupAlertBehavior,
        "sound" to n.sound?.toString(), "vibrate" to value(n.vibrate),
        "smallIcon" to value(n.smallIcon), "largeIcon" to value(n.getLargeIcon()),
        "contentIntent" to value(n.contentIntent), "deleteIntent" to value(n.deleteIntent),
        "fullScreenIntent" to value(n.fullScreenIntent),
        "contentView" to value(n.contentView), "bigContentView" to value(n.bigContentView),
        "headsUpContentView" to value(n.headsUpContentView), "extras" to value(n.extras),
        "actions" to JSONArray().apply { n.actions?.forEach { a -> put(obj(
            "title" to value(a.title), "icon" to value(a.getIcon()),
            "intent" to value(a.actionIntent), "extras" to value(a.extras),
            "allowGeneratedReplies" to a.allowGeneratedReplies,
            "remoteInputs" to JSONArray().apply { a.remoteInputs?.forEach { put(input(it)) } }
        )) } }
    ).apply { n.publicVersion?.let { put("publicVersionExtras", value(it.extras)) } }

    fun event(s: StatusBarNotification, ranks: RankingMap?): JSONObject = obj(
        "package" to s.packageName, "id" to s.id, "tag" to s.tag, "key" to s.key,
        "uid" to (if (Build.VERSION.SDK_INT >= 29) s.uid else null), "userId" to s.userId, "postTime" to s.postTime,
        "groupKey" to s.groupKey, "overrideGroupKey" to s.overrideGroupKey,
        "isGroup" to s.isGroup, "isOngoing" to s.isOngoing, "isClearable" to s.isClearable,
        "notification" to notification(s.notification)
    ).apply {
        val r = Ranking()
        if (ranks?.getRanking(s.key, r) == true) put("ranking", obj(
            "rank" to r.rank, "importance" to r.importance, "isAmbient" to r.isAmbient,
            "matchesInterruptionFilter" to r.matchesInterruptionFilter(),
            "suppressedVisualEffects" to r.suppressedVisualEffects,
            "channel" to r.channel?.let { c -> obj("id" to c.id, "name" to value(c.name),
                "description" to c.description, "importance" to c.importance, "group" to c.group) }
        ))
    }
}
