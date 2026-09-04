package it.motolink.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

object NeonDialogs {
    private const val BG = "#020A06"
    private const val PANEL = "#07120B"
    private const val GREEN = "#5BFF2D"
    private const val GREEN_SOFT = "#2A7A28"
    private const val TEXT = "#F2F5F3"
    private const val MUTED = "#A8B0AC"
    private const val DANGER = "#E06060"
    private const val DANGER_SOFT = "#7A2A2A"

    fun showInfo(activity: Activity, title: String, message: String, positiveText: String = "OK", onPositive: (() -> Unit)? = null) {
        val built = baseDialog(activity, title, message)
        built.root.addView(buttonRow(activity, positiveText to {
            built.dialog.dismiss(); onPositive?.invoke()
        }))
        show(built.dialog, activity)
    }

    fun showConfirm(
        activity: Activity,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String = "ANNULLA",
        danger: Boolean = false,
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null,
    ) {
        val built = baseDialog(activity, title, message)
        built.root.addView(buttonRow(activity,
            negativeText to { built.dialog.dismiss(); onNegative?.invoke() },
            positiveText to { built.dialog.dismiss(); onPositive() },
            danger = danger
        ))
        show(built.dialog, activity)
    }

    fun showCustom(
        activity: Activity,
        title: String,
        message: String? = null,
        contentView: View? = null,
        positiveText: String,
        negativeText: String = "ANNULLA",
        danger: Boolean = false,
        onPositive: () -> Unit,
        onNegative: (() -> Unit)? = null,
    ): Dialog {
        val built = baseDialog(activity, title, message)
        contentView?.let {
            built.root.addView(it, built.root.childCount - 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(activity, 14) })
        }
        if (negativeText.isBlank()) {
            built.root.addView(centeredSingleButtonRow(activity, positiveText, danger) {
                built.dialog.dismiss(); onPositive()
            })
        } else {
            built.root.addView(buttonRow(activity,
                negativeText to { built.dialog.dismiss(); onNegative?.invoke() },
                positiveText to { built.dialog.dismiss(); onPositive() },
                danger = danger
            ))
        }
        show(built.dialog, activity)
        return built.dialog
    }

    private fun baseDialog(activity: Activity, title: String, message: String?): BuiltDialog {
        val dialog = Dialog(activity)
        dialog.setCancelable(true)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16))
            background = rounded(BG, GREEN_SOFT, 1, 24, activity)
        }
        val titleRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(activity).apply {
            text = title.uppercase()
            setTextColor(Color.parseColor(TEXT))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(activity).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(GREEN))
            textSize = 23f
            contentDescription = "Chiudi"
            background = rounded(PANEL, GREEN_SOFT, 1, 18, activity)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)))
        outer.addView(titleRow)
        if (!message.isNullOrBlank()) {
            val scroll = ScrollView(activity)
            scroll.addView(TextView(activity).apply {
                text = message
                setTextColor(Color.parseColor(MUTED))
                textSize = 14f
                setPadding(0, dp(activity, 8), 0, dp(activity, 8))
            })
            outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(activity, 12) })
        }
        dialog.setContentView(outer)
        return BuiltDialog(dialog, outer)
    }

    private data class BuiltDialog(val dialog: Dialog, val root: LinearLayout)

    private fun buttonRow(activity: Activity, vararg buttons: Pair<String, () -> Unit>, danger: Boolean = false): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEachIndexed { index, pair ->
                addView(actionButton(activity, pair.first, index == buttons.lastIndex, danger).apply { setOnClickListener { pair.second.invoke() } },
                    LinearLayout.LayoutParams(if (buttons.size == 1) dp(activity, 230) else 0, dp(activity, 60), if (buttons.size == 1) 0f else 1f).apply {
                        if (index < buttons.lastIndex) marginEnd = dp(activity, 10)
                    })
            }
        }


    private fun centeredSingleButtonRow(activity: Activity, label: String, danger: Boolean, onClick: () -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(actionButton(activity, label, true, danger).apply { setOnClickListener { onClick() } },
                LinearLayout.LayoutParams(dp(activity, 250), dp(activity, 60)))
        }

    private fun actionButton(activity: Activity, label: String, primary: Boolean, danger: Boolean): TextView = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        minWidth = dp(activity, 150)
        minHeight = dp(activity, 56)
        setPadding(dp(activity, 22), 0, dp(activity, 22), 0)
        setTextColor(Color.parseColor(if (danger && primary) DANGER else GREEN))
        background = rounded(if (primary) PANEL else BG, if (danger && primary) DANGER_SOFT else GREEN_SOFT, 1, 16, activity)
    }

    fun rounded(fill: String, stroke: String, strokeDp: Int, radiusDp: Int, activity: Activity): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.parseColor(fill))
        cornerRadius = dp(activity, radiusDp).toFloat()
        setStroke(dp(activity, strokeDp), Color.parseColor(stroke))
    }

    private fun show(dialog: Dialog, activity: Activity) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.78f }
        }
        dialog.show()
        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val landscape = screenW > screenH
        val wantedW = min((screenW * if (landscape) 0.78f else 0.92f).toInt(), dp(activity, if (landscape) 680 else 560))
        dialog.window?.setLayout(wantedW, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun dp(activity: Activity, v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
