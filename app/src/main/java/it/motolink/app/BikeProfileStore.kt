package it.motolink.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

/**
 * Local motorcycle pairing profile. Secrets remain app-private and are encrypted with
 * Android Keystore when available. No QR payload/password is ever written to AppLog.
 */
data class BikeProfile(
    val displayName: String,
    val format: String,
    val brand: String? = null,
    val model: String? = null,
    val ssid: String? = null,
    val wifiPassword: String? = null,
    val wifiSecurity: String? = null,
    val hiddenSsid: Boolean = false,
    val bssid: String? = null,
    val topology: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val serviceName: String? = null,
    val machineId: String? = null,
    val productId: String? = null,
    val pairingToken: String? = null,
    val rawPayload: String,
    val description: String? = null,
    val photoUri: String? = null,
    val catalogLabel: String? = null,
    val savedAtMs: Long = System.currentTimeMillis(),
    val profileId: String = UUID.randomUUID().toString()
) {
    fun endpointLabel(): String? = if (!host.isNullOrBlank() && port != null) "$host:$port" else null
    fun hasWifiIdentity(): Boolean = !ssid.isNullOrBlank()
    fun canAutoJoinWifi(): Boolean {
        if (ssid.isNullOrBlank()) return false
        val sec = wifiSecurity?.uppercase().orEmpty()
        if (sec.contains("WEP")) return false
        return !wifiPassword.isNullOrBlank() || sec in setOf("", "NOPASS", "OPEN", "NONE")
    }
}

object BikeProfileStore {
    private const val PREFS = "trofeolink_bike_profile"
    private const val KEY_ACTIVE = "active_profile_json"
    private const val KEY_PROFILES = "profiles_json_v2"
    private const val KEY_ACTIVE_INDEX = "active_profile_index_v2"
    const val MAX_PROFILES = 3
    private const val KEY_ALIAS = "TrofeoLinkBikeProfileKeyV1"
    private const val ENC_PREFIX = "enc1:"
    private const val PLAIN_PREFIX = "plain1:" // legacy migration only

    fun save(context: Context, profile: BikeProfile): Boolean {
        if (profile.displayName.trim().isEmpty()) return false
        val list = loadAll(context).toMutableList()
        val same = list.indexOfFirst { existing -> sameIdentity(existing, profile) }
        val target = when {
            same >= 0 -> same
            list.size < MAX_PROFILES -> list.size
            else -> return false // V1.0: never overwrite a saved bike implicitly; the user must delete one first.
        }
        val storedProfile = if (same >= 0) {
            // Keep the stable internal identity when a QR/profile is refreshed or renamed.
            profile.copy(profileId = list[same].profileId, savedAtMs = list[same].savedAtMs)
        } else profile
        if (target < list.size) list[target] = storedProfile else list.add(storedProfile)
        if (!saveAll(context, list)) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ACTIVE_INDEX, target).apply()
        // Remove the legacy single-profile slot after successful V2 persistence.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ACTIVE).apply()
        return true
    }

    fun load(context: Context): BikeProfile? {
        val all = loadAll(context)
        if (all.isEmpty()) return null
        val idx = activeIndex(context).coerceIn(0, all.lastIndex)
        return all[idx]
    }

    fun loadAll(context: Context): List<BikeProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedV2 = prefs.getString(KEY_PROFILES, null)
        if (!storedV2.isNullOrBlank()) {
            return try {
                val raw = decrypt(storedV2)
                val arr = org.json.JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        add(fromJson(obj))
                        if (size >= MAX_PROFILES) break
                    }
                }
            } catch (t: Throwable) {
                AppLog.add("QR PROFILE V2 lettura non riuscita: ${t.javaClass.simpleName}")
                emptyList()
            }
        }

        // One-time migration from the original single encrypted profile.
        val legacy = prefs.getString(KEY_ACTIVE, null) ?: return emptyList()
        val profile = try {
            val json = when {
                legacy.startsWith(ENC_PREFIX) -> decrypt(legacy)
                legacy.startsWith(PLAIN_PREFIX) -> legacy.removePrefix(PLAIN_PREFIX)
                legacy.trimStart().startsWith("{") -> legacy
                else -> return emptyList()
            }
            fromJson(JSONObject(json))
        } catch (t: Throwable) {
            AppLog.add("QR PROFILE legacy lettura non riuscita: ${t.javaClass.simpleName}")
            return emptyList()
        }
        if (saveAll(context, listOf(profile))) {
            prefs.edit().putInt(KEY_ACTIVE_INDEX, 0).remove(KEY_ACTIVE).apply()
        }
        return listOf(profile)
    }

    fun setActive(context: Context, index: Int): Boolean {
        val all = loadAll(context)
        if (index !in all.indices) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ACTIVE_INDEX, index).apply()
        return true
    }

    fun delete(context: Context, index: Int): Boolean {
        val list = loadAll(context).toMutableList()
        if (index !in list.indices) return false
        val removed = list.removeAt(index)
        if (!saveAll(context, list)) return false
        MirrorAdaptationConfig.clearProfile(context, removed.profileId)
        val newIdx = if (list.isEmpty()) 0 else activeIndex(context).coerceAtMost(list.lastIndex)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_ACTIVE_INDEX, newIdx).apply()
        return true
    }

    fun updateMetadata(context: Context, index: Int, displayName: String? = null, description: String? = null, catalogLabel: String? = null, photoUri: String? = null): Boolean {
        val list = loadAll(context).toMutableList()
        val old = list.getOrNull(index) ?: return false
        val requestedName = displayName?.trim()
        if (displayName != null && requestedName.isNullOrEmpty()) return false
        list[index] = old.copy(
            displayName = requestedName ?: old.displayName,
            description = description ?: old.description,
            catalogLabel = catalogLabel ?: old.catalogLabel,
            photoUri = photoUri ?: old.photoUri
        )
        return saveAll(context, list)
    }

    fun clearPhoto(context: Context, index: Int): Boolean {
        val list = loadAll(context).toMutableList()
        val old = list.getOrNull(index) ?: return false
        list[index] = old.copy(photoUri = null)
        return saveAll(context, list)
    }

    fun activeIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ACTIVE_INDEX, 0)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_ACTIVE).remove(KEY_PROFILES).remove(KEY_ACTIVE_INDEX).apply()
    }

    private fun saveAll(context: Context, profiles: List<BikeProfile>): Boolean {
        return try {
            val arr = org.json.JSONArray()
            profiles.take(MAX_PROFILES).forEach { arr.put(toJson(it)) }
            val stored = encrypt(arr.toString())
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PROFILES, stored).apply()
            true
        } catch (t: Throwable) {
            AppLog.add("QR PROFILE non salvato: archivio cifrato non disponibile (${t.javaClass.simpleName})")
            false
        }
    }

    private fun sameIdentity(a: BikeProfile, b: BikeProfile): Boolean {
        if (a.machineId != null && b.machineId != null && a.machineId == b.machineId) return true
        if (a.ssid != null && b.ssid != null && a.ssid == b.ssid) return true
        if (a.host != null && b.host != null && a.port == b.port && a.host == b.host) return true
        return a.rawPayload.isNotBlank() && a.rawPayload == b.rawPayload
    }

    private fun toJson(profile: BikeProfile): JSONObject = JSONObject()
        .put("displayName", profile.displayName)
        .put("format", profile.format)
        .put("brand", profile.brand)
        .put("model", profile.model)
        .put("ssid", profile.ssid)
        .put("wifiPassword", profile.wifiPassword)
        .put("wifiSecurity", profile.wifiSecurity)
        .put("hiddenSsid", profile.hiddenSsid)
        .put("bssid", profile.bssid)
        .put("topology", profile.topology)
        .put("host", profile.host)
        .put("port", profile.port)
        .put("serviceName", profile.serviceName)
        .put("machineId", profile.machineId)
        .put("productId", profile.productId)
        .put("pairingToken", profile.pairingToken)
        .put("rawPayload", profile.rawPayload)
        .put("description", profile.description)
        .put("photoUri", profile.photoUri)
        .put("catalogLabel", profile.catalogLabel)
        .put("savedAtMs", profile.savedAtMs)
        .put("profileId", profile.profileId)

    private fun fromJson(obj: JSONObject): BikeProfile = BikeProfile(
        displayName = obj.optString("displayName").ifBlank { "Moto QR" },
        format = obj.optString("format").ifBlank { "QR" },
        brand = obj.optNullableString("brand"),
        model = obj.optNullableString("model"),
        ssid = obj.optNullableString("ssid"),
        wifiPassword = obj.optNullableString("wifiPassword"),
        wifiSecurity = obj.optNullableString("wifiSecurity"),
        hiddenSsid = obj.optBoolean("hiddenSsid", false),
        bssid = obj.optNullableString("bssid"),
        topology = obj.optNullableString("topology"),
        host = obj.optNullableString("host"),
        port = obj.optInt("port", -1).takeIf { it in 1..65535 },
        serviceName = obj.optNullableString("serviceName"),
        machineId = obj.optNullableString("machineId"),
        productId = obj.optNullableString("productId"),
        pairingToken = obj.optNullableString("pairingToken"),
        rawPayload = obj.optString("rawPayload"),
        description = obj.optNullableString("description"),
        photoUri = obj.optNullableString("photoUri"),
        catalogLabel = obj.optNullableString("catalogLabel"),
        savedAtMs = obj.optLong("savedAtMs", 0L),
        profileId = obj.optString("profileId").trim().takeIf { it.isNotEmpty() }
            ?: legacyProfileId(obj)
    )

    private fun legacyProfileId(obj: JSONObject): String {
        // Existing V16-and-earlier profiles had no explicit id. Build a deterministic UUID
        // from connection identity so the per-bike adaptation key remains stable after upgrade.
        val material = listOf(
            obj.optString("machineId"),
            obj.optString("ssid"),
            obj.optString("host"),
            obj.optString("port"),
            obj.optString("rawPayload"),
            obj.optString("savedAtMs")
        ).joinToString("|")
        return UUID.nameUUIDFromBytes(("motolink-bike-profile|" + material).toByteArray(Charsets.UTF_8)).toString()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$ENC_PREFIX$iv:$body"
    }

    private fun decrypt(stored: String): String {
        val parts = stored.removePrefix(ENC_PREFIX).split(':', limit = 2)
        require(parts.size == 2) { "profilo cifrato non valido" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val body = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body).toString(Charsets.UTF_8)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", true) }
    }
}
