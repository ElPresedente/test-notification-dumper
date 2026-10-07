package space.rolark.notificationdumper

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** All callbacks and controls enter on main; disk operations run in FIFO order. */
// commit() intentionally persists session state before capture; other commits run on the writer.
@SuppressLint("ApplySharedPref")
class Recorder private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("recorder", Context.MODE_PRIVATE)
    val directory = File(context.filesDir, "dumps").apply { mkdirs() }
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile var active = false; private set
    @Volatile var count = 0; private set
    @Volatile var error: String? = null; private set
    @Volatile var busy = false; private set
    @Volatile var current: File? = null; private set
    var interrupted = prefs.getString("activeFile", null) != null; private set
    private var seq = 0

    init {
        // Never silently resume recording after process death. Preserve the partial JSONL.
        prefs.edit().remove("activeFile").commit()
    }
    fun start(): Boolean {
        if (active || busy) return false
        error = null; count = 0; seq = 0; interrupted = false
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS").withZone(ZoneId.systemDefault()).format(Instant.now())
        current = File(directory, "dump-$stamp.jsonl")
        if (!prefs.edit().putString("activeFile", current!!.name).commit()) {
            error = "Не удалось сохранить состояние записи"; return false
        }
        active = true
        append("session_start", NotificationJson.obj("schemaVersion" to 1,
            "appVersion" to "0.1.0", "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
            "androidRelease" to Build.VERSION.RELEASE, "sdk" to Build.VERSION.SDK_INT,
            "timezone" to ZoneId.systemDefault().id,
            "scope" to "All notifications made available by Android; opaque objects represented as metadata"))
        return true
    }
    @Synchronized fun append(kind: String, data: JSONObject = JSONObject()) {
        if (!active) return
        val file = current ?: return
        data.put("seq", ++seq).put("event", kind).put("receivedAt", Instant.now().toString())
            .put("receivedAtEpochMs", System.currentTimeMillis()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        val bytes = (data.toString() + "\n").toByteArray(Charsets.UTF_8)
        val notificationEvent = kind == "posted" || kind == "removed" || kind == "snapshot"
        writer.execute {
            if (error != null) return@execute
            try {
                FileOutputStream(file, true).use { it.write(bytes); it.flush() }
                if (notificationEvent) count++
            } catch (e: Exception) {
                error = "Ошибка записи: ${e.message}"; active = false; busy = true
                writer.execute { prefs.edit().remove("activeFile").commit(); busy = false }
            }
        }
    }
    @Synchronized fun stop() {
        if (busy) return
        if (active) append("session_stop")
        active = false; busy = true
        writer.execute {
            try {
                current?.takeIf { it.exists() }?.let { FileOutputStream(it, true).use { out -> out.fd.sync() } }
            } catch (e: Exception) { error = "Ошибка сохранения: ${e.message}" }
            prefs.edit().remove("activeFile").commit()
            busy = false
        }
    }
    fun files(): List<File> = directory.listFiles()?.filter { it.extension == "jsonl" && (!active && !busy || it != current) }
        ?.sortedByDescending { it.name } ?: emptyList()
    companion object {
        @Volatile private var instance: Recorder? = null
        fun get(context: Context): Recorder = instance ?: synchronized(this) {
            instance ?: Recorder(context.applicationContext).also { instance = it }
        }
    }
}
