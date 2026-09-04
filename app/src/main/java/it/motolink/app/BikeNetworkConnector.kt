package it.motolink.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

/**
 * App-scoped connection to a motorcycle/TFT Wi-Fi network learned from a QR code.
 * It never changes the user's global saved Wi-Fi configuration. The process is bound
 * only while a mirror session is active, then unbound on STOP/destroy.
 */
class BikeNetworkConnector(context: Context) {
    companion object {
        const val DEFAULT_EASYCONN_INIT_PORT = 10930
        const val WIFI_DIRECT_DEFAULT_HOST = "192.168.49.1"
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var boundNetwork: Network? = null
    @Volatile private var linkProperties: LinkProperties? = null

    fun isBound(): Boolean = boundNetwork != null

    /**
     * True only while Android still exposes the bound motorcycle network as a Wi-Fi transport.
     * This is intentionally independent from EasyConn/H264 socket state.
     */
    fun isLinkAlive(): Boolean {
        val network = boundNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun connect(
        profile: BikeProfile,
        timeoutMs: Int = 20_000,
        onReady: (Network) -> Unit,
        onUnavailable: (String) -> Unit
    ) {
        val ssid = profile.ssid?.trim().orEmpty()
        if (ssid.isEmpty()) {
            onUnavailable("SSID assente")
            return
        }

        // If Android already has the phone on this bike network, bind without asking again.
        findExistingNetwork(ssid)?.let { existing ->
            bind(existing)
            AppLog.add("QR WIFI: rete moto già disponibile; processo associato senza nuova richiesta")
            onReady(existing)
            return
        }

        if (!profile.canAutoJoinWifi()) {
            onUnavailable("Credenziali Wi-Fi non sufficienti per la connessione automatica")
            return
        }

        release()
        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        profile.bssid?.let { raw ->
            runCatching { specBuilder.setBssid(MacAddress.fromString(raw)) }
                .onFailure { AppLog.add("QR WIFI: BSSID ignorato perché non valido") }
        }
        if (profile.hiddenSsid) specBuilder.setIsHiddenSsid(true)

        val security = profile.wifiSecurity?.uppercase().orEmpty()
        val password = profile.wifiPassword
        try {
            when {
                security.contains("WEP") -> {
                    onUnavailable("Rete WEP non supportata dalla connessione QR automatica")
                    return
                }
                security.contains("WPA3") || security.contains("SAE") -> {
                    if (password.isNullOrEmpty()) {
                        onUnavailable("Password WPA3 assente")
                        return
                    }
                    specBuilder.setWpa3Passphrase(password)
                }
                !password.isNullOrEmpty() -> specBuilder.setWpa2Passphrase(password)
                else -> Unit // open/nopass network
            }
        } catch (t: IllegalArgumentException) {
            onUnavailable("Credenziali Wi-Fi non accettate da Android")
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback !== this) return
                bind(network)
                AppLog.add("QR WIFI: rete moto disponibile e processo associato")
                onReady(network)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (network == boundNetwork || callback === this) linkProperties = lp
            }

            override fun onLost(network: Network) {
                if (network == boundNetwork) {
                    runCatching { cm.bindProcessToNetwork(null) }
                    boundNetwork = null
                    linkProperties = null
                    AppLog.add("QR WIFI: rete moto persa; binding processo rilasciato")
                }
            }

            override fun onUnavailable() {
                if (callback === this) callback = null
                AppLog.add("QR WIFI: Android non ha reso disponibile la rete moto")
                onUnavailable("Rete moto non disponibile o richiesta annullata")
            }
        }
        callback = cb
        try {
            AppLog.add("QR WIFI: richiesta Android per rete moto salvata (credenziali non loggate)")
            cm.requestNetwork(request, cb, timeoutMs)
        } catch (t: Throwable) {
            callback = null
            AppLog.add("QR WIFI: richiesta rete non riuscita: ${t.javaClass.simpleName}")
            onUnavailable("Impossibile richiedere la rete moto")
        }
    }

    fun candidateGatewayHosts(profile: BikeProfile): List<String> {
        val out = linkedSetOf<String>()
        profile.host?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        val lp = linkProperties ?: boundNetwork?.let { cm.getLinkProperties(it) }
        lp?.routes?.forEach { route -> route.gateway?.hostAddress?.let(out::add) }
        if (profile.topology?.contains("P2P", ignoreCase = true) == true) {
            out += WIFI_DIRECT_DEFAULT_HOST
        }
        return out.toList()
    }

    fun release() {
        val cb = callback
        callback = null
        if (cb != null) runCatching { cm.unregisterNetworkCallback(cb) }
        runCatching { cm.bindProcessToNetwork(null) }
        boundNetwork = null
        linkProperties = null
    }

    private fun bind(network: Network) {
        boundNetwork = network
        linkProperties = cm.getLinkProperties(network)
        cm.bindProcessToNetwork(network)
    }

    private fun findExistingNetwork(targetSsid: String): Network? {
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val wifiInfo = if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null
            val current = wifiInfo?.ssid?.trim()?.trim('"')
            if (!current.isNullOrBlank() && current != WifiManagerCompat.UNKNOWN_SSID && current == targetSsid) {
                return network
            }
        }
        return null
    }

    private object WifiManagerCompat {
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
