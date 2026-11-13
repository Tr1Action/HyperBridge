package com.d4viddf.hyperbridge.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.IBinder
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import java.util.concurrent.Executors

/**
 * "Orijinal açılır bildirimi gizle" (root).
 *
 * Orijinal bildirim kapatılmaz: sesi, titreşimi çalar ve bildirim panelinde kalır. Sadece ekranın
 * üstüne düşen açılır (heads-up / yüzen) kutusu kapanır; yerine ada görünür. Bunun için o bildirim
 * kanalının önemi "Yüksek"ten "Varsayılan"a indirilir (Ayarlar'daki "Yüzen bildirimler"i kapatmakla aynı).
 * Kanal düzeyinde olduğu için her kanalın yalnızca ilk bildirimi iki kez açılır, sonrakiler sadece adada.
 *
 * Aramalar, alarmlar ve tam ekran bildirimler hiç dokunulmaz.
 */
object PopupSuppressor {
    private const val TAG = "PopupSuppressor"
    private const val PREFS = "nova_popup"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CHANGED = "changed" // "pkg|uid|channelId"

    private val worker = Executors.newSingleThreadExecutor()
    private val done = HashSet<String>()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) restoreAll(context.applicationContext)
    }

    /** Ada gösterildi: orijinal bildirimin kanalı açılır kutu gösteriyorsa onu kapat. */
    fun onIslandShown(context: Context, sbn: StatusBarNotification) {
        if (!isEnabled(context)) return
        val n = sbn.notification
        val channelId = n.channelId ?: return
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM ||
            n.category == Notification.CATEGORY_STOPWATCH || n.fullScreenIntent != null
        ) return
        val pkg = sbn.packageName
        if (isExcludedPackage(context, pkg)) return
        val entry = "$pkg|${sbn.uid}|$channelId"
        synchronized(done) { if (!done.add(entry)) return }
        if (entry in prefs(context).getStringSet(KEY_CHANGED, emptySet())!!) return
        val app = context.applicationContext
        worker.execute {
            val ok = runTool(app, "lower", pkg, sbn.uid.toString(), channelId)
            Log.d(TAG, "lower $entry -> $ok")
            if (ok) {
                val p = prefs(app)
                val set = HashSet(p.getStringSet(KEY_CHANGED, emptySet())!!)
                set.add(entry)
                p.edit().putStringSet(KEY_CHANGED, set).apply()
            } else {
                // Root yoksa ya da başarısızsa tekrar tekrar denemeyelim
            }
        }
    }

    /** Ayar kapatıldı: indirilen kanalları eski haline (Yüksek) döndür. */
    fun restoreAll(context: Context) {
        worker.execute {
            val p = prefs(context)
            val set = p.getStringSet(KEY_CHANGED, emptySet())!!.toList()
            for (entry in set) {
                val parts = entry.split("|", limit = 3)
                if (parts.size != 3) continue
                runTool(context, "raise", parts[0], parts[1], parts[2])
            }
            p.edit().remove(KEY_CHANGED).apply()
            synchronized(done) { done.clear() }
        }
    }

    private fun isExcludedPackage(context: Context, pkg: String): Boolean {
        val dialer = runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        return pkg == dialer || pkg == context.packageName || pkg == "android" ||
            pkg.contains("incallui") || pkg.contains("deskclock") || pkg.contains("dialer") ||
            pkg == "com.android.server.telecom" || pkg == "com.android.phone"
    }

    /** Root ile sistem yetkisinde [ChannelTool] çalıştırır. */
    private fun runTool(context: Context, vararg args: String): Boolean = try {
        val apk = context.applicationInfo.sourceDir
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val cmd = "CLASSPATH='$apk' app_process /system/bin ${ChannelTool::class.java.name} $quoted"
        val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0 || !out.contains("OK")) Log.w(TAG, "tool failed ($code): $out")
        code == 0 && out.contains("OK")
    } catch (e: Exception) {
        Log.w(TAG, "su failed", e)
        false
    }
}

/**
 * Root altında `app_process` ile çalışır (uid 0 = sistem yetkisi). Başka bir uygulamanın bildirim
 * kanalının önemini değiştirir: `lower` → Varsayılan (açılır kutu yok, ses var), `raise` → Yüksek.
 */
object ChannelTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val (action, pkg, uidStr, channelId) = args
            val uid = uidStr.toInt()
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "notification") as IBinder
            val nm = Class.forName("android.app.INotificationManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val listSlice = nm.javaClass.getMethod(
                "getNotificationChannelsForPackage",
                String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            ).invoke(nm, pkg, uid, false)
            @Suppress("UNCHECKED_CAST")
            val channels = listSlice!!.javaClass.getMethod("getList").invoke(listSlice) as List<NotificationChannel>
            val ch = channels.firstOrNull { it.id == channelId } ?: run {
                println("NO_CHANNEL")
                System.exit(2)
                return
            }
            val target = if (action == "raise") NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            if (action == "lower" && ch.importance < NotificationManager.IMPORTANCE_HIGH) {
                println("OK already")
                System.exit(0)
                return
            }
            ch.importance = target
            // Ayarlar uygulaması gibi "kullanıcı değiştirdi" diye kilitle, uygulama geri almasın
            runCatching {
                val lockField = NotificationChannel::class.java.getField("USER_LOCKED_IMPORTANCE").getInt(null)
                NotificationChannel::class.java.getMethod("lockFields", Int::class.javaPrimitiveType).invoke(ch, lockField)
            }
            nm.javaClass.getMethod(
                "updateNotificationChannelForPackage",
                String::class.java, Int::class.javaPrimitiveType, NotificationChannel::class.java,
            ).invoke(nm, pkg, uid, ch)
            println("OK")
            System.exit(0)
        } catch (t: Throwable) {
            println("ERR " + (t.cause ?: t))
            System.exit(1)
        }
    }
}
