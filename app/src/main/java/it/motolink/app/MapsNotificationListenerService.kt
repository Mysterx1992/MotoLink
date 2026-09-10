package it.motolink.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Research-only listener used to verify which turn-by-turn fields Google Maps exposes through
 * Android notifications. It never writes to the motorcycle and ignores notifications from every
 * package except Google Maps.
 */
class MapsNotificationListenerService : NotificationListenerService() {
    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }

    private var lastFingerprint: String? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.add("MAPS NAV DIAG: accesso notifiche connesso")
        runCatching {
            activeNotifications
                ?.filter { it.packageName == MAPS_PACKAGE }
                ?.forEach { capture("ACTIVE", it) }
        }.onFailure {
            AppLog.add("MAPS NAV DIAG: lettura notifiche attive fallita ${it.javaClass.simpleName}: ${it.message ?: "-"}")
        }
    }

    override fun onListenerDisconnected() {
        AppLog.add("MAPS NAV DIAG: accesso notifiche disconnesso")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != MAPS_PACKAGE) return
        capture("POSTED", sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != MAPS_PACKAGE) return
        AppLog.add("MAPS NAV DIAG: notifica Google Maps rimossa id=${sbn.id}")
    }

    private fun capture(source: String, sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras

        val title = clean(extras?.getCharSequence(Notification.EXTRA_TITLE))
        val text = clean(extras?.getCharSequence(Notification.EXTRA_TEXT))
        val bigText = clean(extras?.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val subText = clean(extras?.getCharSequence(Notification.EXTRA_SUB_TEXT))
        val infoText = clean(extras?.getCharSequence(Notification.EXTRA_INFO_TEXT))
        val summaryText = clean(extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
        val ticker = clean(notification.tickerText)
        val textLines = extras
            ?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map(::clean)
            ?.filter { it.isNotBlank() }
            ?.joinToString(" || ")
            ?.take(600)
            .orEmpty()

        val fingerprint = listOf(title, text, bigText, subText, infoText, summaryText, ticker, textLines)
            .joinToString("|")
        if (fingerprint == lastFingerprint) return
        lastFingerprint = fingerprint

        val capture = buildString {
            appendLine("Google Maps notification")
            appendLine("source=$source")
            appendLine("postTime=${sbn.postTime}")
            appendLine("id=${sbn.id}")
            appendLine("category=${notification.category.orEmpty()}")
            appendLine("ongoing=${(notification.flags and Notification.FLAG_ONGOING_EVENT) != 0}")
            appendLine("TITLE=$title")
            appendLine("TEXT=$text")
            appendLine("BIG_TEXT=$bigText")
            appendLine("SUB_TEXT=$subText")
            appendLine("INFO_TEXT=$infoText")
            appendLine("SUMMARY_TEXT=$summaryText")
            appendLine("TICKER=$ticker")
            append("TEXT_LINES=$textLines")
        }

        MapsNotificationCaptureStore.save(this, capture)
        AppLog.add(
            "MAPS NAV DIAG: TITLE='$title' TEXT='$text' BIG_TEXT='$bigText' " +
                "SUB_TEXT='$subText' INFO_TEXT='$infoText' SUMMARY_TEXT='$summaryText' " +
                "TEXT_LINES='$textLines'"
        )
    }

    private fun clean(value: CharSequence?): String = value
        ?.toString()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(300)
        .orEmpty()
}

object MapsNotificationCaptureStore {
    private const val PREFS = "maps_notification_diagnostic"
    private const val KEY_CAPTURE = "last_capture"
    private const val KEY_UPDATED_AT = "updated_at"

    fun save(context: android.content.Context, capture: String) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CAPTURE, capture)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun lastCapture(context: android.content.Context): String =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(KEY_CAPTURE, null)
            ?: "Nessuna notifica Google Maps acquisita."

    fun updatedAt(context: android.content.Context): Long =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getLong(KEY_UPDATED_AT, 0L)

    fun clear(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
