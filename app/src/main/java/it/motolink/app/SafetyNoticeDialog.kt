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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** MotoLink safety notice shown after onboarding and on later launches until suppressed. */
object SafetyNoticeDialog {
    private const val BG = "#020A06"
    private const val PANEL = "#07120B"
    private const val GREEN = "#5BFF2D"
    private const val GREEN_SOFT = "#2A7A28"
    private const val TEXT = "#F2F5F3"
    private const val MUTED = "#B7C0BB"

    fun show(activity: Activity, onAccepted: (suppressFuture: Boolean) -> Unit) {
        val dialog = Dialog(activity)
        dialog.setCancelable(false)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18))
            background = rounded(BG, GREEN_SOFT, 1, 24, activity)
        }

        root.addView(TextView(activity).apply {
            text = "ATTENZIONE ALLA GUIDA"
            setTextColor(Color.parseColor(GREEN))
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.035f
            gravity = Gravity.CENTER_HORIZONTAL
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(activity).apply {
            text = "MotoLink è progettata per facilitare l’accesso alle funzioni utili durante il viaggio.\n\n" +
                "Non utilizzare l’app per guardare video o altri contenuti che possano distrarre durante la guida.\n\n" +
                "Mantieni sempre l’attenzione sulla strada e utilizza il dispositivo solo quando le condizioni lo consentono."
            setTextColor(Color.parseColor(TEXT))
            textSize = 15f
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(activity, 14), 0, dp(activity, 12))
        })

        val dontShow = CheckBox(activity).apply {
            text = "Non visualizzare più questo messaggio"
            setTextColor(Color.parseColor(MUTED))
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(GREEN))
            setPadding(0, 0, 0, dp(activity, 14))
        }
        root.addView(dontShow)

        root.addView(TextView(activity).apply {
            text = "OK"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(GREEN))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(PANEL, GREEN, 1, 16, activity)
            setOnClickListener {
                val suppress = dontShow.isChecked
                dialog.dismiss()
                onAccepted(suppress)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 62)))

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.82f }
        }
        dialog.show()
        val screenW = activity.resources.displayMetrics.widthPixels
        dialog.window?.setLayout(min((screenW * .92f).toInt(), dp(activity, 560)), ViewGroup.LayoutParams.WRAP_CONTENT)
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
