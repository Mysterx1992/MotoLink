package it.motolink.app

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ordered, local-only storage for up to four rider-selected launcher shortcuts.
 *
 * Only the explicit launcher component and the label chosen by the user are stored.
 * No installed-app inventory is collected or queried.
 */
data class FavoriteAppEntry(
    val componentName: String,
    val label: String
) {
    fun component(): ComponentName? = ComponentName.unflattenFromString(componentName)
}

object FavoriteAppsStore {
    private const val PREFS = "trofeolink_favorite_apps"
    private const val KEY = "favorites_json_v1"
    const val MAX_FAVORITES = 4

    fun load(context: Context): MutableList<FavoriteAppEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val out = mutableListOf<FavoriteAppEntry>()
            val seen = HashSet<String>()
            for (i in 0 until array.length()) {
                if (out.size >= MAX_FAVORITES) break
                val obj = array.optJSONObject(i) ?: continue
                val component = obj.optString("component").trim()
                val label = obj.optString("label").trim()
                if (component.isNotEmpty() && ComponentName.unflattenFromString(component) != null && seen.add(component)) {
                    out += FavoriteAppEntry(component, label.ifEmpty { "App" })
                }
            }
            // Normalize legacy duplicated entries without changing the order of valid favorites.
            if (out.size != array.length().coerceAtMost(MAX_FAVORITES)) save(context, out)
            out
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    fun put(context: Context, index: Int, entry: FavoriteAppEntry): Boolean {
        val list = load(context)
        val cleanIndex = index.coerceIn(0, MAX_FAVORITES - 1)
        val duplicateIndex = list.indexOfFirst { it.componentName == entry.componentName }
        if (duplicateIndex >= 0 && duplicateIndex != cleanIndex) return false
        if (cleanIndex < list.size) {
            list[cleanIndex] = entry
        } else if (list.size < MAX_FAVORITES) {
            list += entry
        } else {
            return false
        }
        save(context, list)
        return true
    }

    fun remove(context: Context, index: Int) {
        val list = load(context)
        if (index in list.indices) {
            list.removeAt(index)
            save(context, list)
        }
    }

    private fun save(context: Context, list: List<FavoriteAppEntry>) {
        val array = JSONArray()
        list.take(MAX_FAVORITES).forEach { entry ->
            array.put(JSONObject().apply {
                put("component", entry.componentName)
                put("label", entry.label)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
