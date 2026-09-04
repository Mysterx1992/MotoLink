package it.motolink.app

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * Local-only custom launcher picker for MotoLink favorite apps.
 *
 * Clean-room UI: it queries only MAIN/LAUNCHER activities already visible through the
 * manifest <queries> rule. It does not use QUERY_ALL_PACKAGES and does not persist an
 * installed-app inventory. The only persisted value remains the favorite component/label.
 */
object FavoriteAppPickerDialog {
    data class Candidate(
        val component: ComponentName,
        val label: String,
        val icon: Drawable?,
        val group: Group
    )

    enum class Group { NAVIGATION, MEDIA, OTHER }

    private const val BG = "#020A06"
    private const val PANEL = "#07120B"
    private const val TILE = "#081A0E"
    private const val GREEN = "#5BFF2D"
    private const val GREEN_SOFT = "#2A7A28"
    private const val TEXT = "#F2F5F3"
    private const val MUTED = "#A8B0AC"

    fun show(activity: Activity, onSelected: (ComponentName, String) -> Unit) {
        val candidates = loadCandidates(activity)
        if (candidates.isEmpty()) {
            NeonDialogs.showInfo(
                activity = activity,
                title = "App preferite",
                message = "Android non ha restituito app avviabili. Chiudi e riapri MotoLink; se il problema resta, condividi il LOG."
            )
            return
        }

        AppLog.add(
            "APP PREFERITA: selettore custom aperto con ${candidates.size} app; " +
                "navigatori=${candidates.count { it.group == Group.NAVIGATION }}"
        )

        val dialog = Dialog(activity)
        dialog.setCancelable(true)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 14))
            background = rounded(BG, GREEN_SOFT, 1, 24, activity)
        }

        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(activity).apply {
            text = "SCEGLI UN'APP"
            setTextColor(Color.parseColor(TEXT))
            textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(activity).apply {
            text = "✕"
            contentDescription = "Chiudi"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(GREEN))
            textSize = 24f
            background = rounded(PANEL, GREEN_SOFT, 1, 18, activity)
            setOnClickListener { dialog.dismiss() }
        }
        titleRow.addView(close, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)))
        root.addView(titleRow)

        root.addView(TextView(activity).apply {
            text = "Scegli una scorciatoia per il mirroring"
            setTextColor(Color.parseColor(MUTED))
            textSize = 13f
            setPadding(0, dp(activity, 2), 0, dp(activity, 12))
        })

        val search = EditText(activity).apply {
            hint = "Cerca app"
            setHintTextColor(Color.parseColor("#65736B"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine(true)
            setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
            background = rounded(PANEL, GREEN_SOFT, 1, 16, activity)
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)).apply {
            bottomMargin = dp(activity, 12)
        })

        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.TRANSPARENT)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(activity, 8))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = TextView(activity).apply {
            text = "Solo app avviabili · max 4 preferite"
            setTextColor(Color.parseColor("#728077"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(activity, 8), 0, 0)
        }
        root.addView(footer)

        fun render(query: String) {
            content.removeAllViews()
            val q = query.trim().lowercase()
            val filtered = if (q.isBlank()) candidates else candidates.filter {
                it.label.lowercase().contains(q) || it.component.packageName.lowercase().contains(q)
            }

            if (filtered.isEmpty()) {
                content.addView(TextView(activity).apply {
                    text = "Nessuna app trovata"
                    setTextColor(Color.parseColor(MUTED))
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(activity, 36), 0, dp(activity, 36))
                })
                return
            }

            addSection(activity, content, "NAVIGAZIONE", filtered.filter { it.group == Group.NAVIGATION }, dialog, onSelected)
            addSection(activity, content, "MUSICA / AUDIO", filtered.filter { it.group == Group.MEDIA }, dialog, onSelected)
            addSection(activity, content, "LE TUE APP", filtered.filter { it.group == Group.OTHER }, dialog, onSelected)
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        render("")
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.78f }
        }
        dialog.show()

        val screenW = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val wantedW = min((screenW * 0.94f).toInt(), dp(activity, 560))
        val wantedH = min((screenH * 0.84f).toInt(), dp(activity, 760))
        dialog.window?.setLayout(wantedW, wantedH)
    }

    private fun addSection(
        activity: Activity,
        parent: LinearLayout,
        title: String,
        items: List<Candidate>,
        dialog: Dialog,
        onSelected: (ComponentName, String) -> Unit
    ) {
        if (items.isEmpty()) return

        parent.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.parseColor(GREEN))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(dp(activity, 2), dp(activity, 10), 0, dp(activity, 7))
        })

        val grid = GridLayout(activity).apply {
            columnCount = 4
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val dialogWidth = min((activity.resources.displayMetrics.widthPixels * 0.94f).toInt(), dp(activity, 560))
        val usable = dialogWidth - dp(activity, 48)
        val tileW = (usable / 4).coerceAtLeast(dp(activity, 70))

        items.forEach { item ->
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                contentDescription = item.label
                setPadding(dp(activity, 6), dp(activity, 8), dp(activity, 6), dp(activity, 7))
                background = rounded(TILE, GREEN_SOFT, 1, 16, activity)
                setOnClickListener {
                    AppLog.add("APP PREFERITA: scelta '${item.label}' dal selettore custom")
                    dialog.dismiss()
                    onSelected(item.component, item.label)
                }
            }
            val icon = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(item.icon)
                contentDescription = null
            }
            tile.addView(icon, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
            tile.addView(TextView(activity).apply {
                text = item.label
                setTextColor(Color.parseColor(TEXT))
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(activity, 5), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 38)))

            val lp = GridLayout.LayoutParams().apply {
                width = tileW - dp(activity, 8)
                height = dp(activity, 104)
                setMargins(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4))
            }
            grid.addView(tile, lp)
        }
        parent.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(activity, 6)
        })
    }

    private fun loadCandidates(activity: Activity): List<Candidate> {
        val pm = activity.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { resolved ->
                    val info = resolved.activityInfo ?: return@mapNotNull null
                    if (info.packageName == activity.packageName) return@mapNotNull null
                    val component = ComponentName(info.packageName, info.name)
                    val label = try {
                        resolved.loadLabel(pm).toString().trim()
                    } catch (_: Throwable) {
                        info.packageName.substringAfterLast('.')
                    }.ifBlank { info.packageName.substringAfterLast('.').ifBlank { "App" } }
                    val group = classify(info.packageName, label)
                    val flags = info.applicationInfo.flags
                    val systemApp = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                    // Keep the chooser useful: user-installed apps are shown; preinstalled
                    // system clutter is hidden except genuine navigation apps.
                    if (systemApp && group != Group.NAVIGATION) return@mapNotNull null

                    val icon = try { resolved.loadIcon(pm) } catch (_: Throwable) { null }
                    Candidate(component, label, icon, group)
                }
                .distinctBy { it.component.packageName }
                .sortedWith(compareBy<Candidate>({ it.group.ordinal }, { it.label.lowercase() }))
        } catch (t: Throwable) {
            AppLog.add("APP PREFERITA: lettura app avviabili fallita: ${t.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun classify(packageName: String, label: String): Group {
        val p = packageName.lowercase()
        val l = label.lowercase()

        val navigation = listOf(
            "com.google.android.apps.maps", "com.waze", "com.tomtom.", "com.sygic.",
            "net.osmand", "com.here.", "com.mapfactor.", "com.calimoto.", "com.kurviger.",
            "com.rever.", "com.detecht.", "com.roadlords", "com.generalmagic.magicearth"
        ).any { p == it || p.startsWith(it) } || listOf(
            "maps", "mappe", "waze", "navig", "tomtom", "sygic", "osmand", "here wego",
            "calimoto", "kurviger", "magic earth"
        ).any { l.contains(it) }
        if (navigation) return Group.NAVIGATION

        val media = listOf(
            "com.spotify.music", "com.google.android.apps.youtube.music", "com.amazon.mp3",
            "deezer.android.app", "com.aspiro.tidal", "com.maxmpz.audioplayer",
            "org.videolan.vlc", "com.audible.application", "com.soundcloud.android"
        ).any { p == it || p.startsWith(it) } || listOf(
            "spotify", "youtube music", "amazon music", "deezer", "tidal", "poweramp", "vlc",
            "audible", "soundcloud"
        ).any { l.contains(it) }
        return if (media) Group.MEDIA else Group.OTHER
    }

    private fun rounded(
        fill: String,
        stroke: String,
        strokeDp: Int,
        radiusDp: Int,
        activity: Activity
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.parseColor(fill))
        cornerRadius = dp(activity, radiusDp).toFloat()
        setStroke(dp(activity, strokeDp), Color.parseColor(stroke))
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
