package it.motolink.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object V16ConnectionModeDialog {
    fun show(
        activity: Activity,
        onAutomatic: () -> Unit,
        onHotspotQr: () -> Unit,
        onBle: () -> Unit,
        onCancel: () -> Unit
    ) {
        val dialog = Dialog(activity)
        var chosen = false
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18))
            background = rounded("#020A06", "#2A7A28", activity)
        }
        root.addView(TextView(activity).apply {
            text = "CONNESSIONE MOTO"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(activity).apply {
            text = "Scegli come MotoLink deve collegarsi a questo profilo. La scelta resta salvata e potrai modificarla in seguito."
            setTextColor(Color.parseColor("#A8B0AC"))
            textSize = 14f
            setPadding(0, dp(activity, 8), 0, dp(activity, 14))
        })
        fun add(label: String, detail: String, action: () -> Unit) {
            root.addView(TextView(activity).apply {
                text = "$label\n$detail"
                setTextColor(Color.parseColor("#5BFF2D"))
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
                background = rounded("#07120B", "#2A7A28", activity)
                setOnClickListener { chosen = true; dialog.dismiss(); action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 68)).apply { bottomMargin = dp(activity, 10) })
        }
        add("AUTOMATICA", "MotoLink prova il collegamento compatibile disponibile", onAutomatic)
        add("HOTSPOT / QR", "Collegamento EasyConn tramite rete della moto", onHotspotQr)
        add("BLE", "Bluetooth navigazione per TFT compatibili", onBle)
        root.addView(TextView(activity).apply {
            text = "ANNULLA"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#A8B0AC"))
            textSize = 15f
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(root)
        dialog.setOnDismissListener { if (!chosen) onCancel() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.78f }
        }
        dialog.show()
        val w = (activity.resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(dp(activity, 560))
        dialog.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun rounded(fill: String, stroke: String, activity: Activity) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.parseColor(fill))
        cornerRadius = dp(activity, 16).toFloat()
        setStroke(dp(activity, 1), Color.parseColor(stroke))
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
