package space.rolark.notificationdumper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val recorder get() = Recorder.get(this)
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val component get() = ComponentName(this, DumpListener::class.java)
    private lateinit var state: TextView
    private lateinit var counter: TextView
    private lateinit var record: Button
    private lateinit var files: LinearLayout
    private var displayed = ""
    private var exportFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 500) } }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 16f) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.rgb(30, 47, 40)); setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
        minHeight = dp(52)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportFile = savedInstanceState?.getString("exportFile")?.let { name -> recorder.files().find { it.name == name } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(18), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(root); isFillViewport = true }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom); insets
        }
        setContentView(scroll)
        root.addView(text("КОПИЛКА / ЛАБОРАТОРИЯ", 12f))
        root.addView(text("Notification\nDumper", 32f).apply { setTypeface(null, Typeface.BOLD) })
        root.addView(text("Включи запись перед оплатой. Вернись после покупки, останови запись и отправь файл.", 16f))
        state = text(""); root.addView(state)
        root.addView(button("1. Доступ к уведомлениям") {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (e: Exception) { toast("Не удалось открыть настройки: ${e.message}") }
        })
        root.addView(button("Переподключить слушатель") {
            if (manager.isNotificationListenerAccessGranted(component)) {
                NotificationListenerService.requestRebind(component); toast("Запрошено подключение")
            } else toast("Сначала разреши доступ к уведомлениям")
        })
        counter = text("", 20f); root.addView(counter)
        record = button("") {
            if (recorder.active) recorder.stop()
            else if (DumpListener.live != null && recorder.start()) DumpListener.live?.snapshot("start")
            refresh()
        }.apply {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(23, 107, 87)); cornerRadius = dp(16).toFloat() }
        }
        root.addView(record, LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(button("Тестовое уведомление") { testNotification() })
        root.addView(text("Записываются уведомления всех приложений, доступные Android. Файл может содержать личные сообщения. Сети у приложения нет; отправка — только вручную.", 13f))
        root.addView(text("Сохранённые записи", 23f).apply { setTypeface(null, Typeface.BOLD) })
        files = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(files)
        root.addView(text("Если Android блокирует доступ: Настройки → Приложения → Notification Dumper → ⋮ → Разрешить ограниченные настройки (если такой пункт есть).", 13f))
    }
    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("exportFile", exportFile?.name); super.onSaveInstanceState(out) }
    private fun refresh() {
        val connected = DumpListener.live != null
        state.text = when {
            !manager.isNotificationListenerAccessGranted(component) -> "○ Доступ ещё не разрешён"
            !connected -> "○ Доступ есть, слушатель отключён"
            else -> "● Слушатель подключён"
        }
        counter.text = when {
            recorder.error != null -> recorder.error
            recorder.busy -> "Сохраняю файл…"
            recorder.active -> "● Запись идёт · событий: ${recorder.count}"
            recorder.interrupted -> "Запись была прервана системой. Частичный файл сохранён."
            else -> "Запись выключена · событий: ${recorder.count}"
        }
        record.text = if (recorder.active) "Остановить и сохранить" else "Начать запись"
        record.isEnabled = !recorder.busy && (recorder.active || connected)
        val list = recorder.files()
        val signature = list.joinToString { "${it.name}:${it.length()}" }
        if (signature == displayed && files.childCount > 0) return
        displayed = signature; files.removeAllViews()
        if (list.isEmpty()) files.addView(text("Здесь появятся JSONL-файлы."))
        list.forEach { f ->
            files.addView(text("${f.name}\n${f.length()} байт", 14f))
            val row = LinearLayout(this)
            row.addView(button("Отправить") { share(f) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("Сохранить как…") {
                exportFile = f
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-ndjson"; putExtra(Intent.EXTRA_TITLE, f.name)
                }, 20)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            files.addView(row)
            files.addView(button("Удалить запись") {
                AlertDialog.Builder(this).setMessage("Удалить ${f.name}?").setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ -> f.delete(); refresh() }.show()
            })
        }
    }
    private fun share(f: File) {
        val uri = Uri.Builder().scheme("content").authority("$packageName.files").appendPath(f.name).build()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-ndjson"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Notification dump", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(intent, "Отправить JSONL")) }
        catch (e: Exception) { toast("Нет приложения для отправки. Используй «Сохранить как…»") }
    }
    @Deprecated("Legacy activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return; val source = exportFile ?: return
        thread {
            val result = runCatching {
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { out -> source.inputStream().use { it.copyTo(out) } }
            }
            runOnUiThread { toast(if (result.isSuccess) "Файл сохранён" else "Ошибка: ${result.exceptionOrNull()?.message}") }
        }
    }
    private fun testNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 30); return
        }
        manager.createNotificationChannel(NotificationChannel("test", "Проверка дампера", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(42, Notification.Builder(this, "test").setSmallIcon(R.drawable.ic_dump)
            .setContentTitle("Тестовая покупка — 123,45 ₽").setContentText("ТЕСТ МАГАЗИН · карта •1234")
            .setStyle(Notification.BigTextStyle().bigText("Тестовое уведомление. Покупка: 123,45 ₽. Магазин: ТЕСТ МАГАЗИН. Это не реальная операция."))
            .setExtras(Bundle().apply { putString("dumper.test", "Проверка вложенных extras"); putBundle("dumper.nested", Bundle().apply { putInt("amountMinor", 12345) }) })
            .setAutoCancel(true).build())
        toast("Тест отправлен. Во время записи счётчик должен увеличиться.")
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 30 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) testNotification()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
