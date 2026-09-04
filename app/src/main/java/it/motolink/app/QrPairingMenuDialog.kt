package it.motolink.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * MotoLink-styled QR pairing menu.
 * Replaces the platform gray AlertDialog shown by the MOTO QR button.
 */
object QrPairingMenuDialog {
    private const val BG = "#020A06"
    private const val PANEL = "#07120B"
    private const val TILE = "#081A0E"
    private const val GREEN = "#5BFF2D"
    private const val GREEN_SOFT = "#2A7A28"
    private const val TEXT = "#F2F5F3"
    private const val MUTED = "#A8B0AC"
    private const val DANGER = "#FF6A6A"

    fun show(
        activity: Activity,
        hasProfile: Boolean,
        onScan: () -> Unit,
        onImport: () -> Unit,
        onManual: () -> Unit,
        onLocalProfile: () -> Unit,
        onOpenWifi: () -> Unit,
        onShowProfile: (() -> Unit)? = null,
        onRemoveProfile: (() -> Unit)? = null,
    ) {
        val built = buildDialog(
            activity,
            "AGGIUNGI MOTO",
            if (hasProfile) "Aggiungi o gestisci i profili. QR quando disponibile, profilo locale per Trofeo/EasyConn standard." else "Configura la moto una sola volta: QR quando disponibile oppure profilo locale; poi basta START."
        )
        val body = built.root

        body.addView(actionCard(activity, "⌁", "SCANSIONA QR", "Scansiona il QR mostrato dal TFT", GREEN) {
            built.dialog.dismiss(); onScan()
        })
        body.addView(actionCard(activity, "▣", "IMPORTA DA IMMAGINE", "Leggi un QR da una foto già presente sul telefono", GREEN) {
            built.dialog.dismiss(); onImport()
        })
        body.addView(actionCard(activity, "≋", "WI-FI MANUALE", "Inserisci manualmente SSID e password della moto", GREEN) {
            built.dialog.dismiss(); onManual()
        })
        body.addView(actionCard(activity, "+", "PROFILO SENZA QR", "Per Trofeo e moto che usano la discovery EasyConn standard", GREEN) {
            built.dialog.dismiss(); onLocalProfile()
        })
        if (hasProfile && onShowProfile != null) {
            body.addView(actionCard(activity, "✓", "PROFILO SALVATO", "Visualizza i dati della moto già configurata", GREEN) {
                built.dialog.dismiss(); onShowProfile()
            })
        }
        body.addView(actionCard(activity, "⌘", "IMPOSTAZIONI WI-FI", "Apri rapidamente le impostazioni Wi‑Fi del telefono", GREEN) {
            built.dialog.dismiss(); onOpenWifi()
        })

        showBuilt(activity, built.dialog)
    }

    private data class BuiltDialog(val dialog: Dialog, val root: LinearLayout)

    private fun buildDialog(activity: Activity, titleText: String, subtitleText: String): BuiltDialog {
        val dialog = Dialog(activity)
        dialog.setCancelable(true)

        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16))
            background = rounded(BG, GREEN_SOFT, 1, 24, activity)
        }

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(activity).apply {
            text = titleText
            setTextColor(Color.parseColor(TEXT))
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        titleRow.addView(TextView(activity).apply {
            text = "✕"
            contentDescription = "Chiudi"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(GREEN))
            textSize = 23f
            background = rounded(PANEL, GREEN_SOFT, 1, 18, activity)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)))
        outer.addView(titleRow)

        outer.addView(TextView(activity).apply {
            text = subtitleText
            setTextColor(Color.parseColor(MUTED))
            textSize = 13f
            setPadding(0, dp(activity, 3), 0, dp(activity, 13))
        })

        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(outer)
        return BuiltDialog(dialog, body)
    }

    private fun actionCard(activity: Activity, glyph: String, title: String, subtitle: String, accent: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            background = rounded(PANEL, if (accent == DANGER) "#7A2A2A" else GREEN_SOFT, 1, 17, activity)
            setOnClickListener { onClick() }

            addView(TextView(activity).apply {
                text = glyph
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(accent))
                textSize = 27f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                background = rounded(TILE, if (accent == DANGER) "#7A2A2A" else GREEN_SOFT, 1, 16, activity)
            }, LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 52)))

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(activity, 13), 0, 0, 0)
                addView(TextView(activity).apply {
                    text = title
                    setTextColor(Color.parseColor(accent))
                    textSize = 16f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(activity).apply {
                    text = subtitle
                    setTextColor(Color.parseColor(MUTED))
                    textSize = 12f
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }.also {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 76))
            lp.bottomMargin = dp(activity, 9)
            it.layoutParams = lp
        }

    private fun showBuilt(activity: Activity, dialog: Dialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.78f }
        }
        dialog.show()
        val screenW = activity.resources.displayMetrics.widthPixels
        val wantedW = min((screenW * 0.92f).toInt(), dp(activity, 540))
        dialog.window?.setLayout(wantedW, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun rounded(fill: String, stroke: String, strokeDp: Int, radiusDp: Int, activity: Activity): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(fill))
            cornerRadius = dp(activity, radiusDp).toFloat()
            setStroke(dp(activity, strokeDp), Color.parseColor(stroke))
        }

    private fun dp(activity: Activity, v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
