package it.motolink.app

import android.content.Context
import kotlin.math.roundToInt

/**
 * V15 universal adaptation model.
 *
 * The user's final physical Trofeo calibration is frozen as a normalized landscape reference
 * viewport at the 800x480 EasyConn canvas. It is then scaled from the runtime T-Box geometry
 * (MEDIA_INIT / live view area) instead of being tied to a motorcycle model name.
 *
 * Reference frame proven on 800x480:
 *   viewport = 830x428 @ x=-35, y=46
 * which corresponds to the final V14 settings L40/T105/R0/B110.
 *
 * LANDSCAPE prefs now store only extra edge corrections relative to that automatic reference.
 * PORTRAIT remains independent and starts from the untouched full-target FIT behavior.
 */
object MirrorAdaptationConfig {
    private const val PREFS_NAME = "trofeolink_prefs"
    private const val KEY_ENABLED = "mirror_adaptation_enabled"

    private const val KEY_LAND_LEFT = "mirror_adaptation_landscape_left_px"
    private const val KEY_LAND_TOP = "mirror_adaptation_landscape_top_px"
    private const val KEY_LAND_RIGHT = "mirror_adaptation_landscape_right_px"
    private const val KEY_LAND_BOTTOM = "mirror_adaptation_landscape_bottom_px"

    private const val KEY_PORT_LEFT = "mirror_adaptation_portrait_left_px"
    private const val KEY_PORT_TOP = "mirror_adaptation_portrait_top_px"
    private const val KEY_PORT_RIGHT = "mirror_adaptation_portrait_right_px"
    private const val KEY_PORT_BOTTOM = "mirror_adaptation_portrait_bottom_px"

    private const val KEY_PANEL_X = "mirror_adaptation_panel_x"
    private const val KEY_PANEL_Y = "mirror_adaptation_panel_y"
    private const val KEY_SEMANTICS_VERSION = "mirror_adaptation_semantics_version"
    private const val KEY_PROFILE_STORAGE_VERSION = "mirror_adaptation_profile_storage_version"

    private const val CURRENT_SEMANTICS_VERSION = 15
    private const val CURRENT_PROFILE_STORAGE_VERSION = 17

    // Physical reference captured from the user's accepted V14 Maps calibration.
    const val REFERENCE_TARGET_WIDTH = 800
    const val REFERENCE_TARGET_HEIGHT = 480
    const val REFERENCE_VIEWPORT_X = -35
    const val REFERENCE_VIEWPORT_Y = 46
    const val REFERENCE_VIEWPORT_WIDTH = 830
    const val REFERENCE_VIEWPORT_HEIGHT = 428

    // Historical V14 absolute calibration that produced the reference viewport above.
    const val V14_FINAL_LEFT = 40
    const val V14_FINAL_TOP = 105
    const val V14_FINAL_RIGHT = 0
    const val V14_FINAL_BOTTOM = 110

    const val SETTINGS_DESCRIPTION = "Adatta manualmente il display"

    val USER_HELP_TEXT: String
        get() = """
            Adattamento regola il mirroring in base al display della moto.

            FRECCE
            ↑ regola il bordo superiore
            ↓ regola il bordo inferiore
            ← regola il bordo sinistro
            → regola il bordo destro

            Ogni pressione modifica quel bordo di ${STEP_PX} px.

            TASTO CENTRALE
            + allarga l’area dell’immagine
            − restringe l’area dell’immagine

            SPOSTA IL PANNELLO
            Trascina la scritta “Adattamento”.

            SALVATAGGIO
            Le regolazioni si salvano automaticamente e restano separate per ogni profilo moto e per orientamento verticale/orizzontale. Se una moto non è mai stata regolata, MotoLink usa la base automatica predefinita. Rinominare la moto non fa perdere le sue regolazioni.

            COMANDI
            ⓘ apre o chiude queste istruzioni.
            × chiude il pannello e disattiva Adattamento.

            Adattamento è OFF al primo accesso. Se lo attivi, resta ON finché non lo disattivi.
        """.trimIndent()

    const val STEP_PX = 5
    const val MIN_EDGE_PX = -8192
    const val MAX_EDGE_PX = 8192

    enum class Profile { LANDSCAPE, PORTRAIT }

    data class AutoFrame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    data class Snapshot(
        val enabled: Boolean,
        val profile: Profile,
        val leftPx: Int,
        val topPx: Int,
        val rightPx: Int,
        val bottomPx: Int,
        val panelX: Int,
        val panelY: Int,
    ) {
        val netHorizontalPx: Int get() = leftPx + rightPx
        val netVerticalPx: Int get() = topPx + bottomPx
        val totalNetPx: Int get() = leftPx + topPx + rightPx + bottomPx
        val totalPositiveExpansionPx: Int get() =
            leftPx.coerceAtLeast(0) + topPx.coerceAtLeast(0) + rightPx.coerceAtLeast(0) + bottomPx.coerceAtLeast(0)

        val label: String
            get() = if (!enabled) {
                "OFF"
            } else {
                val p = if (profile == Profile.LANDSCAPE) "LAND" else "PORT"
                "ON • $p • extra L${signed(leftPx)} T${signed(topPx)} R${signed(rightPx)} B${signed(bottomPx)}"
            }
    }

    /**
     * Scale the accepted 800x480 landscape geometry to the runtime T-Box canvas.
     * No motorcycle model, app name or fixed Valico/Trofeo branch is used here.
     */
    fun landscapeAutoFrameFor(targetWidth: Int, targetHeight: Int): AutoFrame {
        val safeW = targetWidth.coerceAtLeast(16)
        val safeH = targetHeight.coerceAtLeast(16)

        fun sx(value: Int): Int = (value.toDouble() * safeW.toDouble() / REFERENCE_TARGET_WIDTH.toDouble()).roundToInt()
        fun sy(value: Int): Int = (value.toDouble() * safeH.toDouble() / REFERENCE_TARGET_HEIGHT.toDouble()).roundToInt()

        return AutoFrame(
            x = sx(REFERENCE_VIEWPORT_X),
            y = sy(REFERENCE_VIEWPORT_Y),
            width = sx(REFERENCE_VIEWPORT_WIDTH).coerceAtLeast(16),
            height = sy(REFERENCE_VIEWPORT_HEIGHT).coerceAtLeast(16),
        )
    }

    private fun migrateIfNeeded(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldVersion = p.getInt(KEY_SEMANTICS_VERSION, 9)
        if (oldVersion >= CURRENT_SEMANTICS_VERSION &&
            p.contains(KEY_LAND_LEFT) && p.contains(KEY_PORT_LEFT)) return

        // Preserve the already-correct portrait profile. Landscape becomes zero EXTRA because
        // the user's final V14 calibration is now the automatic base and must not be applied twice.
        val portLeft = p.getInt(KEY_PORT_LEFT, 0).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX)
        val portTop = p.getInt(KEY_PORT_TOP, 0).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX)
        val portRight = p.getInt(KEY_PORT_RIGHT, 0).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX)
        val portBottom = p.getInt(KEY_PORT_BOTTOM, 0).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX)

        p.edit()
            .putInt(KEY_LAND_LEFT, 0)
            .putInt(KEY_LAND_TOP, 0)
            .putInt(KEY_LAND_RIGHT, 0)
            .putInt(KEY_LAND_BOTTOM, 0)
            .putInt(KEY_PORT_LEFT, portLeft)
            .putInt(KEY_PORT_TOP, portTop)
            .putInt(KEY_PORT_RIGHT, portRight)
            .putInt(KEY_PORT_BOTTOM, portBottom)
            .putInt(KEY_SEMANTICS_VERSION, CURRENT_SEMANTICS_VERSION)
            .apply()

        AppLog.add(
            "ADATTAMENTO V15 MIGRAZIONE: fissata come base automatica la misura finale Maps V14 " +
                "(800x480 -> viewport ${REFERENCE_VIEWPORT_WIDTH}x${REFERENCE_VIEWPORT_HEIGHT}@${REFERENCE_VIEWPORT_X},${REFERENCE_VIEWPORT_Y}; " +
                "equivalente L$V14_FINAL_LEFT/T$V14_FINAL_TOP/R$V14_FINAL_RIGHT/B$V14_FINAL_BOTTOM); " +
                "extra LANDSCAPE azzerati; PORTRAIT conservato"
        )
    }

    private fun legacyProfileKeys(profile: Profile): Array<String> = if (profile == Profile.LANDSCAPE) {
        arrayOf(KEY_LAND_LEFT, KEY_LAND_TOP, KEY_LAND_RIGHT, KEY_LAND_BOTTOM)
    } else {
        arrayOf(KEY_PORT_LEFT, KEY_PORT_TOP, KEY_PORT_RIGHT, KEY_PORT_BOTTOM)
    }

    private fun activeBikeId(context: Context): String =
        BikeProfileStore.load(context)?.profileId?.trim()?.takeIf { it.isNotEmpty() } ?: "NO_PROFILE"

    private fun profileKeyPrefix(profileId: String, profile: Profile): String {
        val orientation = if (profile == Profile.LANDSCAPE) "landscape" else "portrait"
        return "mirror_adaptation_bike_${profileId}_${orientation}"
    }

    private fun profileKeys(context: Context, profile: Profile): Array<String> {
        val prefix = profileKeyPrefix(activeBikeId(context), profile)
        return arrayOf("${prefix}_left_px", "${prefix}_top_px", "${prefix}_right_px", "${prefix}_bottom_px")
    }

    private fun migratePerBikeIfNeeded(context: Context) {
        val bike = BikeProfileStore.load(context) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_PROFILE_STORAGE_VERSION, 0) >= CURRENT_PROFILE_STORAGE_VERSION) return

        // V16 stored one shared LAND/PORT correction set. On the V17 upgrade it belongs only
        // to the bike that is active at migration time. Every other bike starts from zero extras
        // and therefore uses the already validated V15 automatic base.
        val edit = prefs.edit()
        for (orientation in Profile.values()) {
            val oldKeys = legacyProfileKeys(orientation)
            val newPrefix = profileKeyPrefix(bike.profileId, orientation)
            val newKeys = arrayOf("${newPrefix}_left_px", "${newPrefix}_top_px", "${newPrefix}_right_px", "${newPrefix}_bottom_px")
            for (i in 0..3) {
                if (!prefs.contains(newKeys[i])) edit.putInt(newKeys[i], prefs.getInt(oldKeys[i], 0))
            }
        }
        edit.putInt(KEY_PROFILE_STORAGE_VERSION, CURRENT_PROFILE_STORAGE_VERSION).apply()
        AppLog.add("ADATTAMENTO V17 MIGRAZIONE PROFILO: regolazioni V16 assegnate solo al profilo moto attivo; altri profili useranno base automatica V15")
    }

    private fun ensureEnabledDefault(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_ENABLED)) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            AppLog.add("ADATTAMENTO V19 DEFAULT: primo accesso -> OFF")
        }
    }

    fun load(context: Context, profile: Profile): Snapshot {
        migrateIfNeeded(context)
        migratePerBikeIfNeeded(context)
        ensureEnabledDefault(context)
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keys = profileKeys(context, profile)
        return Snapshot(
            enabled = p.getBoolean(KEY_ENABLED, false),
            profile = profile,
            leftPx = p.getInt(keys[0], 0).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX),
            topPx = p.getInt(keys[1], 0).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX),
            rightPx = p.getInt(keys[2], 0).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX),
            bottomPx = p.getInt(keys[3], 0).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX),
            panelX = p.getInt(KEY_PANEL_X, 24),
            panelY = p.getInt(KEY_PANEL_Y, 220),
        )
    }

    fun load(context: Context, landscape: Boolean): Snapshot =
        load(context, if (landscape) Profile.LANDSCAPE else Profile.PORTRAIT)

    fun load(context: Context): Snapshot = load(context, Profile.LANDSCAPE)

    fun setEnabled(context: Context, enabled: Boolean) {
        migrateIfNeeded(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun adjustEdge(context: Context, profile: Profile, edge: String, deltaPx: Int): Snapshot {
        val old = load(context, profile)
        var left = old.leftPx
        var top = old.topPx
        var right = old.rightPx
        var bottom = old.bottomPx
        when (edge.uppercase()) {
            "LEFT" -> left = (left + deltaPx).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX)
            "TOP" -> top = (top + deltaPx).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX)
            "RIGHT" -> right = (right + deltaPx).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX)
            "BOTTOM" -> bottom = (bottom + deltaPx).coerceIn(MIN_EDGE_PX, MAX_EDGE_PX)
        }
        val keys = profileKeys(context, profile)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(keys[0], left)
            .putInt(keys[1], top)
            .putInt(keys[2], right)
            .putInt(keys[3], bottom)
            .apply()
        return load(context, profile)
    }

    fun adjustEdge(context: Context, landscape: Boolean, edge: String, deltaPx: Int): Snapshot =
        adjustEdge(context, if (landscape) Profile.LANDSCAPE else Profile.PORTRAIT, edge, deltaPx)

    fun resetEdges(context: Context, profile: Profile): Snapshot {
        migrateIfNeeded(context)
        val keys = profileKeys(context, profile)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(keys[0], 0)
            .putInt(keys[1], 0)
            .putInt(keys[2], 0)
            .putInt(keys[3], 0)
            .apply()
        return load(context, profile)
    }

    fun savePanelPosition(context: Context, x: Int, y: Int) {
        migrateIfNeeded(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_PANEL_X, x)
            .putInt(KEY_PANEL_Y, y)
            .apply()
    }

    fun dashboardLabel(context: Context): String {
        val land = load(context, Profile.LANDSCAPE)
        if (!land.enabled) return "OFF"
        val port = load(context, Profile.PORTRAIT)
        val bikeName = BikeProfileStore.load(context)?.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "profilo corrente"
        return "ON • $bikeName • AUTO TFT • LAND extra L${signed(land.leftPx)} T${signed(land.topPx)} R${signed(land.rightPx)} B${signed(land.bottomPx)} " +
            "• PORT L${signed(port.leftPx)} T${signed(port.topPx)} R${signed(port.rightPx)} B${signed(port.bottomPx)}"
    }

    fun clearProfile(context: Context, profileId: String) {
        val id = profileId.trim()
        if (id.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        for (orientation in Profile.values()) {
            val prefix = profileKeyPrefix(id, orientation)
            edit.remove("${prefix}_left_px")
                .remove("${prefix}_top_px")
                .remove("${prefix}_right_px")
                .remove("${prefix}_bottom_px")
        }
        edit.apply()
    }

    fun summary(context: Context): String = dashboardLabel(context)

    fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()
}
