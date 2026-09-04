package it.motolink.app

import android.net.Uri
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Independent, conservative parser for motorcycle/TFT provisioning QR codes.
 * It recognizes common Wi-Fi/provisioning shapes without assuming undocumented secrets.
 * Unknown payloads are preserved encrypted so a later parser can reinterpret them.
 */
object QrPairing {
    private val carbitToken = Regex("(?i)^CARBIT([0-9A-F]{12})$")
    private val cfmotoSsid = Regex("(?i)\\b(CFMOTO-[A-Z0-9_-]{3,})\\b")

    fun parse(rawInput: String): BikeProfile {
        val raw = rawInput.trim()
        require(raw.isNotEmpty()) { "QR vuoto" }

        parseWifi(raw)?.let { return normalize(it) }
        parseLegacyPairingToken(raw)?.let { return normalize(it) }
        parseKnownVendorPayload(raw)?.let { return normalize(it) }
        parseJson(raw)?.let { return normalize(it) }
        parseUrl(raw)?.let { return normalize(it) }
        parseKeyValue(raw)?.let { return normalize(it) }

        val inferredCfmoto = cfmotoSsid.find(raw)?.groupValues?.getOrNull(1)
        return normalize(
            BikeProfile(
                displayName = inferredCfmoto?.let { "CFMOTO $it" } ?: "Moto QR ${fingerprint(raw)}",
                format = "OPAQUE",
                brand = if (inferredCfmoto != null) "CFMOTO" else null,
                ssid = inferredCfmoto,
                rawPayload = raw
            )
        )
    }

    fun manualWifiProfile(ssidInput: String, passwordInput: String?, displayNameInput: String? = null): BikeProfile {
        val ssid = ssidInput.trim()
        require(ssid.isNotEmpty()) { "SSID vuoto" }
        val password = passwordInput?.takeIf { it.isNotEmpty() }
        val brand = inferBrand(ssid, null)
        return normalize(
            BikeProfile(
                displayName = displayNameInput?.trim()?.takeIf { it.isNotEmpty() }
                    ?: defaultDisplayName(brand, null, ssid),
                format = "MANUAL_WIFI",
                brand = brand,
                ssid = ssid,
                wifiPassword = password,
                wifiSecurity = if (password == null) "OPEN" else "WPA2",
                rawPayload = "MANUAL_WIFI"
            )
        )
    }

    private fun parseWifi(raw: String): BikeProfile? {
        if (!raw.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = parseEscapedFields(raw.substringAfter(':'), ';')
        val ssid = fields["S"]?.takeIf { it.isNotBlank() }
        val password = fields["P"]?.takeIf { it.isNotBlank() }
        val security = fields["T"]?.trim()?.takeIf { it.isNotEmpty() }
        val hidden = fields["H"]?.equals("true", true) == true
        val bssid = normalizeMac(fields["B"] ?: fields["BSSID"])
        val brand = inferBrand(ssid, null)
        return BikeProfile(
            displayName = defaultDisplayName(brand, null, ssid),
            format = "WIFI",
            brand = brand,
            ssid = ssid,
            wifiPassword = password,
            wifiSecurity = security,
            hiddenSsid = hidden,
            bssid = bssid,
            topology = "SOFTAP",
            rawPayload = raw
        )
    }

    private fun parseLegacyPairingToken(raw: String): BikeProfile? {
        val match = carbitToken.matchEntire(raw) ?: return null
        return BikeProfile(
            displayName = "QR compatibile",
            format = "CARBIT_TOKEN",
            brand = "Compatibile",
            pairingToken = match.groupValues[1].uppercase(Locale.ROOT),
            rawPayload = raw
        )
    }

    /**
     * Handles vendor provisioning payloads that are not necessarily valid URI query strings.
     * Examples include fields such as Wifi, MachineID, ProductID, SSID/PWD and topology hints.
     */
    private fun parseKnownVendorPayload(raw: String): BikeProfile? {
        fun extract(key: String): String? {
            val r = Regex("(?i)(?:^|[?&;|\\s])${Regex.escape(key)}\\s*[=:]\\s*([^&#;|\\r\\n]+)")
            return r.find(raw)?.groupValues?.getOrNull(1)?.trim()?.let(::decode)?.takeIf { it.isNotEmpty() }
        }

        val ssid = extract("SSID") ?: extract("Wifi") ?: cfmotoSsid.find(raw)?.groupValues?.getOrNull(1)
        val password = extract("PWD") ?: extract("password") ?: extract("passphrase") ?: extract("psk")
        val machineId = extract("MachineID")
        val productId = extract("ProductID")
        val bssid = normalizeMac(extract("BSSID") ?: extract("MAC") ?: extract("carWifiBssid"))
        val host = extract("host") ?: extract("ip") ?: extract("tbox_ip")
        val port = (extract("easyconn_port") ?: extract("port"))?.toIntOrNull()?.takeIf { it in 1..65535 }
        val topology = topologyFrom(extract("topology"), extract("mode"), extract("ap"), raw)
        if (ssid == null && machineId == null && productId == null && bssid == null && host == null) return null

        val brand = inferBrand(ssid, raw)
        val model = extract("model") ?: extract("bike")
        val format = when {
            brand == "CFMOTO" -> "CFMOTO_MOTOPLAY"
            machineId != null || productId != null -> "MOTO_VENDOR_WIFI"
            raw.contains("thinkerride", true) -> "THINKERRIDE"
            else -> "VENDOR_WIFI"
        }
        return BikeProfile(
            displayName = defaultDisplayName(brand, model, ssid),
            format = format,
            brand = brand,
            model = model,
            ssid = ssid,
            wifiPassword = password,
            wifiSecurity = if (password != null) "WPA2" else null,
            bssid = bssid,
            topology = topology,
            host = host,
            port = port,
            machineId = machineId,
            productId = productId,
            rawPayload = raw
        )
    }

    private fun parseJson(raw: String): BikeProfile? {
        if (!(raw.startsWith("{") && raw.endsWith("}"))) return null
        return try {
            val obj = JSONObject(raw)
            val name = firstString(obj, "name", "deviceName", "bikeName", "tboxName")
            val model = firstString(obj, "model", "bike", "motorcycle")
            val ssid = firstString(obj, "ssid", "wifiSsid", "wifi_ssid", "wifi", "networkName")
            val password = firstString(obj, "password", "pwd", "passphrase", "wifiPassword", "wifi_password", "psk")
            val security = firstString(obj, "security", "wifiSecurity", "auth")
            val bssid = normalizeMac(firstString(obj, "bssid", "mac", "carWifiBssid", "car_wifi_bssid"))
            val topology = topologyFrom(
                firstString(obj, "topology", "networkTopology"),
                firstString(obj, "mode", "networkMode"),
                firstString(obj, "ap"),
                raw
            )
            val host = firstString(obj, "host", "ip", "address", "tboxIp", "tbox_ip")
            val port = firstPort(obj, "port", "easyConnPort", "easyconn_port")
            val service = firstString(obj, "service", "serviceName", "mdns", "mdnsName")
            val machineId = firstString(obj, "machineId", "MachineID", "machine_id")
            val productId = firstString(obj, "productId", "ProductID", "product_id")
            val token = firstString(obj, "token", "pairingToken", "carbitId")
            val brand = firstString(obj, "brand", "manufacturer", "make") ?: inferBrand(ssid, raw)
            if (ssid == null && host == null && service == null && token == null && machineId == null) return null
            BikeProfile(
                displayName = name ?: defaultDisplayName(brand, model, ssid),
                format = "JSON",
                brand = brand,
                model = model,
                ssid = ssid,
                wifiPassword = password,
                wifiSecurity = security ?: if (password != null) "WPA2" else null,
                hiddenSsid = obj.optBoolean("hidden", false),
                bssid = bssid,
                topology = topology,
                host = host,
                port = port,
                serviceName = service,
                machineId = machineId,
                productId = productId,
                pairingToken = token,
                rawPayload = raw
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseUrl(raw: String): BikeProfile? {
        val uri = try { Uri.parse(raw) } catch (_: Throwable) { return null }
        if (uri.scheme.isNullOrBlank()) return null
        val names = try { uri.queryParameterNames } catch (_: Throwable) { emptySet() }
        if (names.isEmpty()) return null

        fun query(vararg keys: String): String? {
            for (key in keys) {
                val actual = names.firstOrNull { it.equals(key, ignoreCase = true) } ?: continue
                val value = uri.getQueryParameter(actual)?.trim()
                if (!value.isNullOrEmpty()) return value
            }
            return null
        }

        val ssid = query("ssid", "wifi", "wifi_ssid", "network", "networkName")
        val password = query("password", "pwd", "pass", "passphrase", "psk")
        val explicitHost = query("host", "ip", "address", "tbox_ip")
        // Never mistake a provisioning website (e.g. https://...) for the motorcycle endpoint.
        val directScheme = uri.scheme.equals("easyconn", true) || uri.scheme.equals("tcp", true) || uri.scheme.equals("socket", true)
        val host = explicitHost ?: if (directScheme) uri.host else null
        val port = query("port", "easyconn_port")?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: if (directScheme) uri.port.takeIf { it in 1..65535 } else null
        val name = query("name", "device", "bikeName")
        val model = query("model", "bike")
        val service = query("service", "service_name", "mdns")
        val bssid = normalizeMac(query("bssid", "mac", "carWifiBssid"))
        val machineId = query("MachineID", "machineId", "machine_id")
        val productId = query("ProductID", "productId", "product_id")
        val topology = topologyFrom(query("topology"), query("mode"), query("ap"), raw)
        val brand = query("brand", "make") ?: inferBrand(ssid, raw)
        if (ssid == null && host == null && service == null && machineId == null && productId == null) return null

        return BikeProfile(
            displayName = name ?: defaultDisplayName(brand, model, ssid),
            format = when {
                brand == "CFMOTO" -> "CFMOTO_MOTOPLAY_URL"
                raw.contains("thinkerride", true) -> "THINKERRIDE_URL"
                else -> "URL"
            },
            brand = brand,
            model = model,
            ssid = ssid,
            wifiPassword = password,
            wifiSecurity = if (password != null) "WPA2" else null,
            bssid = bssid,
            topology = topology,
            host = host,
            port = port,
            serviceName = service,
            machineId = machineId,
            productId = productId,
            rawPayload = raw
        )
    }

    private fun parseKeyValue(raw: String): BikeProfile? {
        if (!raw.contains('=') && !raw.contains(':')) return null
        val map = linkedMapOf<String, String>()
        raw.split(';', '\n', '\r', '|', '&').forEach { token ->
            val part = token.trim()
            if (part.isEmpty()) return@forEach
            val eq = part.indexOf('=')
            val colon = part.indexOf(':')
            val cut = when {
                eq > 0 -> eq
                colon > 0 -> colon
                else -> -1
            }
            if (cut > 0) {
                val key = part.substring(0, cut).trim().lowercase(Locale.ROOT)
                val value = decode(part.substring(cut + 1).trim())
                if (key.isNotBlank() && value.isNotBlank()) map[key] = value
            }
        }
        if (map.isEmpty()) return null
        fun get(vararg keys: String): String? = keys.firstNotNullOfOrNull { map[it.lowercase(Locale.ROOT)] }
        val ssid = get("ssid", "wifi", "wifi_ssid", "network", "networkname")
        val password = get("password", "pwd", "pass", "passphrase", "psk")
        val host = get("host", "ip", "address", "tbox_ip")
        val port = get("port", "easyconn_port")?.toIntOrNull()?.takeIf { it in 1..65535 }
        val model = get("model", "bike")
        val name = get("name", "device", "bikename", "tbox")
        val service = get("service", "service_name", "mdns")
        val bssid = normalizeMac(get("bssid", "mac", "carwifibssid", "car_wifi_bssid"))
        val machineId = get("machineid", "machine_id")
        val productId = get("productid", "product_id")
        val token = get("token", "pairingtoken", "carbitid")
        val topology = topologyFrom(get("topology"), get("mode"), get("ap"), raw)
        val brand = get("brand", "make", "manufacturer") ?: inferBrand(ssid, raw)
        if (ssid == null && host == null && name == null && service == null && machineId == null && token == null) return null
        return BikeProfile(
            displayName = name ?: defaultDisplayName(brand, model, ssid),
            format = if (brand == "CFMOTO") "CFMOTO_MOTOPLAY_KV" else "KEY_VALUE",
            brand = brand,
            model = model,
            ssid = ssid,
            wifiPassword = password,
            wifiSecurity = get("security", "auth") ?: if (password != null) "WPA2" else null,
            bssid = bssid,
            topology = topology,
            host = host,
            port = port,
            serviceName = service,
            machineId = machineId,
            productId = productId,
            pairingToken = token,
            rawPayload = raw
        )
    }

    private fun normalize(profile: BikeProfile): BikeProfile {
        val brand = profile.brand ?: inferBrand(profile.ssid, profile.rawPayload)
        val displayName = when {
            profile.displayName.isNotBlank() && !profile.displayName.startsWith("Moto QR") -> profile.displayName
            else -> defaultDisplayName(brand, profile.model, profile.ssid)
        }
        return profile.copy(
            displayName = displayName,
            brand = brand,
            bssid = normalizeMac(profile.bssid),
            topology = profile.topology?.uppercase(Locale.ROOT)
        )
    }

    private fun topologyFrom(topology: String?, mode: String?, ap: String?, raw: String): String? {
        val values = listOfNotNull(topology, mode).joinToString(" ").uppercase(Locale.ROOT)
        return when {
            values.contains("P2P") || values.contains("WIFI_DIRECT") || values.contains("WI-FI DIRECT") -> "P2P"
            values.contains("SOFTAP") || values.contains("HOTSPOT") || values == "AP" -> "SOFTAP"
            ap == "1" || raw.contains("ap=1", true) -> "SOFTAP"
            else -> null
        }
    }

    private fun inferBrand(ssid: String?, raw: String?): String? {
        val s = (ssid.orEmpty() + " " + raw.orEmpty()).uppercase(Locale.ROOT)
        return when {
            "CFMOTO-" in s || "CFMOTO" in s -> "CFMOTO"
            "MOTOMORINI" in s || "MOTO MORINI" in s -> "Moto Morini"
            "THINKERRIDE" in s -> "ThinkerRide"
            "KOVE" in s -> "Kove"
            "CARBIT" in s -> "Compatibile"
            "VOGE" in s -> "Voge"
            else -> null
        }
    }

    private fun defaultDisplayName(brand: String?, model: String?, ssid: String?): String = when {
        !brand.isNullOrBlank() && !model.isNullOrBlank() -> "$brand $model"
        !brand.isNullOrBlank() && !ssid.isNullOrBlank() -> "$brand · $ssid"
        !brand.isNullOrBlank() -> brand
        !model.isNullOrBlank() -> model
        !ssid.isNullOrBlank() -> "Moto · $ssid"
        else -> "Moto QR"
    }

    private fun normalizeMac(raw: String?): String? {
        val clean = raw?.trim()?.replace("-", "")?.replace(":", "") ?: return null
        if (!clean.matches(Regex("(?i)[0-9a-f]{12}"))) return null
        return clean.chunked(2).joinToString(":") { it.uppercase(Locale.ROOT) }
    }

    private fun decode(value: String): String = try { Uri.decode(value) } catch (_: Throwable) { value }

    private fun parseEscapedFields(body: String, separator: Char): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val token = StringBuilder()
        var escaped = false
        fun consume() {
            val s = token.toString()
            token.setLength(0)
            val idx = s.indexOf(':')
            if (idx > 0) result[s.substring(0, idx).uppercase(Locale.ROOT)] = s.substring(idx + 1)
        }
        for (c in body) {
            when {
                escaped -> { token.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == separator -> consume()
                else -> token.append(c)
            }
        }
        if (token.isNotEmpty()) consume()
        return result
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.optString(key).trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun firstPort(obj: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = when (val v = obj.opt(key)) {
                is Number -> v.toInt()
                else -> v?.toString()?.toIntOrNull()
            }
            if (value != null && value in 1..65535) return value
        }
        return null
    }

    private fun fingerprint(raw: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.take(3).joinToString("") { "%02X".format(it) }
    }
}
