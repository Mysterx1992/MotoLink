package it.motolink.app

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/**
 * MotoLink-styled management UI for favorite apps.
 * It replaces the older platform gray AlertDialogs.
 */
object FavoriteAppsManageDialog {
    private const val BG = "#020A06"
    private const val PANEL = "#07120B"
    private const val TILE = "#081A0E"
    private const val GREEN = "#5BFF2D"
    private const val GREEN_SOFT = "#2A7A28"
    private const val TEXT = "#F2F5F3"
    private const val MUTED = "#A8B0AC"
    private const val DANGER = "#FF6A6A"

    fun showManage(
        activity: Activity,
        favorites: List<FavoriteAppEntry>,
        onAdd: () -> Unit,
        onReplace: () -> Unit,
        onRemove: () -> Unit
    ) {
        val dialog = buildDialog(activity, "APP PREFERITE", "Gestisci le scorciatoie del mirroring")
        val body = dialog.root

        if (favorites.size < FavoriteAppsStore.MAX_FAVORITES) {
            body.addView(actionCard(activity, "＋", "AGGIUNGI", "Aggiungi una nuova app preferita", GREEN) {
                dialog.dialog.dismiss(); onAdd()
            })
        }
        body.addView(actionCard(activity, "↻", "SOSTITUISCI", "Scegli quale preferita cambiare", GREEN) {
            dialog.dialog.dismiss(); onReplace()
        })
        body.addView(actionCard(activity, "−", "RIMUOVI", "Rimuovi una preferita dalla barra", DANGER) {
            dialog.dialog.dismiss(); onRemove()
        })

        showBuilt(activity, dialog.dialog)
    }

    fun showFavoriteChoice(
        activity: Activity,
        title: String,
        subtitle: String,
        favorites: List<FavoriteAppEntry>,
        destructive: Boolean,
        onSelected: (Int) -> Unit
    ) {
        val built = buildDialog(activity, title, subtitle)
        val pm = activity.packageManager
        val accent = if (destructive) DANGER else GREEN

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        favorites.forEachIndexed { index, entry ->
            val icon: Drawable? = try {
                entry.component()?.let { pm.getActivityIcon(it) }
            } catch (_: Throwable) { null }

            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(activity, 8), dp(activity, 12), dp(activity, 8), dp(activity, 10))
                background = rounded(TILE, if (destructive) "#7A2A2A" else GREEN_SOFT, 1, 18, activity)
                setOnClickListener {
                    built.dialog.dismiss()
                    onSelected(index)
                }
            }
            val image = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(icon)
                contentDescription = null
            }
            tile.addView(image, LinearLayout.LayoutParams(dp(activity, 54), dp(activity, 54)))
            tile.addView(TextView(activity).apply {
                text = entry.label
                setTextColor(Color.parseColor(TEXT))
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(activity, 7), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 42)))

            row.addView(tile, LinearLayout.LayoutParams(0, dp(activity, 126), 1f).apply {
                val m = dp(activity, 5)
                setMargins(m, m, m, m)
            })
        }
        built.root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        built.root.addView(TextView(activity).apply {
            text = if (destructive) "Tocca l'app da rimuovere" else "Tocca l'app da sostituire"
            setTextColor(Color.parseColor(if (destructive) DANGER else MUTED))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 9), 0, 0)
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

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        outer.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(outer)
        return BuiltDialog(dialog, body)
    }

    private fun actionCard(
        activity: Activity,
        glyph: String,
        title: String,
        subtitle: String,
        accent: String,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(activity).apply {
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
            textSize = 29f
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
        val wantedW = min((screenW * 0.92f).toInt(), dp(activity, 520))
        dialog.window?.setLayout(wantedW, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun rounded(fill: String, stroke: String, strokeDp: Int, radiusDp: Int, activity: Activity): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(fill))
            cornerRadius = dp(activity, radiusDp).toFloat()
            setStroke(dp(activity, strokeDp), Color.parseColor(stroke))
        }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
