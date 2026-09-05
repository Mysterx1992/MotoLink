package it.motolink.app

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone diagnostic launcher. It does not alter the current EasyConn/mirroring flow.
 * Its only purpose is to verify what Google Maps exposes in its ongoing navigation notification.
 */
class MapsNotificationDiagnosticActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var resultView: TextView

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.install(this)
        title = "MotoLink Maps Test"
        setContentView(buildUi())
        AppLog.add("MAPS NAV DIAG: schermata diagnostica aperta")
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(30))
        }

        content.addView(TextView(this).apply {
            text = "MotoLink · Google Maps Navigation Test"
            setTextColor(Color.WHITE)
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
        }, lpMatchWrap())

        content.addView(TextView(this).apply {
            text = "Test locale: MotoLink ascolta esclusivamente le notifiche di Google Maps per capire quali dati turn-by-turn Android rende disponibili. Nessun dato viene inviato alla moto e nessun dato viene caricato online."
            setTextColor(Color.LTGRAY)
            textSize = 16f
            setPadding(0, dp(12), 0, dp(18))
        }, lpMatchWrap())

        statusView = TextView(this).apply {
            textSize = 17f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }
        content.addView(statusView, lpMatchWrap())

        content.addView(button("1 · Apri accesso notifiche") {
            runCatching {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.onFailure {
                Toast.makeText(this, "Apri Impostazioni Android > Accesso notifiche e abilita MotoLink Maps Test.", Toast.LENGTH_LONG).show()
            }
        }, lpButton())

        content.addView(button("2 · Apri Google Maps") {
            val launch = packageManager.getLaunchIntentForPackage(MapsNotificationListenerService.MAPS_PACKAGE)
            if (launch != null) {
                startActivity(launch)
            } else {
                Toast.makeText(this, "Google Maps non risulta installato.", Toast.LENGTH_LONG).show()
            }
        }, lpButton())

        content.addView(TextView(this).apply {
            text = "PROVA: abilita l'accesso, apri Google Maps, avvia una navigazione reale e attendi una nuova indicazione. Poi torna qui. I campi qui sotto si aggiornano automaticamente."
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, dp(18), 0, dp(10))
        }, lpMatchWrap())

        resultView = TextView(this).apply {
            setTextColor(Color.rgb(91, 255, 45))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(7, 10, 8))
        }
        content.addView(resultView, lpMatchWrap())

        content.addView(button("Aggiorna") { refresh() }, lpButton())
        content.addView(button("Cancella acquisizione") {
            MapsNotificationCaptureStore.clear(this)
            AppLog.add("MAPS NAV DIAG: acquisizione locale cancellata")
            refresh()
        }, lpButton())

        content.addView(TextView(this).apply {
            text = "Campi osservati: TITLE, TEXT, BIG_TEXT, SUB_TEXT, INFO_TEXT, SUMMARY_TEXT, TICKER e TEXT_LINES. In questa fase non tentiamo ancora di interpretare frecce o inviare BLE al TFT."
            setTextColor(Color.GRAY)
            textSize = 14f
            setPadding(0, dp(18), 0, 0)
        }, lpMatchWrap())

        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return scroll
    }

    private fun refresh() {
        if (!::statusView.isInitialized || !::resultView.isInitialized) return
        val granted = hasNotificationListenerAccess()
        statusView.text = if (granted) {
            "ACCESSO NOTIFICHE: ON\nGoogle Maps: ${if (isMapsInstalled()) "installato" else "non trovato"}"
        } else {
            "ACCESSO NOTIFICHE: OFF\nTocca il primo pulsante e abilita MotoLink Maps Test."
        }
        statusView.setTextColor(if (granted) Color.rgb(91, 255, 45) else Color.rgb(232, 179, 79))

        val updatedAt = MapsNotificationCaptureStore.updatedAt(this)
        val stamp = if (updatedAt > 0L) {
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALY).format(Date(updatedAt))
        } else {
            "mai"
        }
        resultView.text = "Ultimo aggiornamento: $stamp\n\n${MapsNotificationCaptureStore.lastCapture(this)}"
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        val component = ComponentName(this, MapsNotificationListenerService::class.java)
        return manager?.isNotificationListenerAccessGranted(component) == true
    }

    private fun isMapsInstalled(): Boolean =
        packageManager.getLaunchIntentForPackage(MapsNotificationListenerService.MAPS_PACKAGE) != null

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun lpMatchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun lpButton() = lpMatchWrap().apply { topMargin = dp(12) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
