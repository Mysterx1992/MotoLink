package it.motolink.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/**
 * Clean-room Wi-Fi Direct/P2P connector for QR-paired motorcycle TFTs.
 *
 * It deliberately handles only Android's public WifiP2pManager flow:
 * discover peer -> connect -> read group owner + local P2P IPv4.
 * It does not read/log peer MAC addresses or Wi-Fi passwords and does not modify TFT firmware.
 */
class WifiDirectBikeConnector(context: Context) {
    data class Link(
        val localAddress: InetAddress,
        val groupOwnerAddress: InetAddress,
        val peerName: String,
        val groupName: String?,
        val interfaceName: String?
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val p2p = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = p2p?.initialize(
        appContext,
        Looper.getMainLooper(),
        object : WifiP2pManager.ChannelListener {
            override fun onChannelDisconnected() {
                AppLog.add("P2P CHANNEL: perso")
            }
        }
    )

    private var receiver: BroadcastReceiver? = null
    private var timeoutRunnable: Runnable? = null
    private var generation = 0L
    private var targetName = ""
    private var connectRequested = false
    private var nextConnectAllowedAtMs = 0L
    private var connectionCreatedByUs = false
    private var completionDelivered = false
    private var onReady: ((Link) -> Unit)? = null
    private var onUnavailable: ((String) -> Unit)? = null

    @Volatile private var groupFormed = false
    @Volatile private var phoneIsGroupOwner = false
    @Volatile private var ownerAddress: InetAddress? = null
    @Volatile private var localAddress: InetAddress? = null
    @Volatile private var groupPeerName: String? = null
    @Volatile private var groupName: String? = null
    @Volatile private var interfaceName: String? = null
    @Volatile private var activeLink: Link? = null

    fun currentLink(): Link? = activeLink

    /**
     * Transport-level liveness only: the P2P group must still be formed and this phone must
     * remain the client. Video/EasyConn socket state is deliberately not part of this check.
     */
    fun isLinkAlive(): Boolean {
        val link = activeLink ?: return false
        if (!groupFormed || phoneIsGroupOwner) return false
        val iface = link.interfaceName?.takeIf { it.isNotBlank() } ?: return true
        return runCatching {
            NetworkInterface.getByName(iface)?.let { ni ->
                ni.isUp && Collections.list(ni.inetAddresses).any {
                    it is Inet4Address && !it.isLoopbackAddress && it == link.localAddress
                }
            } == true
        }.getOrDefault(false)
    }

    /**
     * Selects the Wi-Fi Direct path from protocol evidence, not from a motorcycle brand name.
     * Explicit SOFTAP/HOTSPOT profiles stay on the classic Wi-Fi path. Vendor QR profiles
     * with an SSID and unknown topology get a bounded exact-name P2P probe; MainActivity
     * falls back to the existing Wi-Fi/EasyConn path if that exact peer is not available.
     */
    fun shouldUse(profile: BikeProfile): Boolean {
        val topology = profile.topology.orEmpty().uppercase(Locale.ROOT)
        if (topology.contains("P2P") || topology.contains("WIFI_DIRECT") || topology.contains("WI-FI DIRECT")) return true
        if (topology.contains("SOFTAP") || topology.contains("HOTSPOT") || topology == "AP") return false
        if (profile.ssid.isNullOrBlank()) return false
        val format = profile.format.uppercase(Locale.ROOT)
        return format in setOf("VENDOR_WIFI", "MOTO_VENDOR_WIFI", "CFMOTO_MOTOPLAY", "THINKERRIDE", "OPAQUE")
    }

    fun isExplicitP2p(profile: BikeProfile): Boolean {
        val topology = profile.topology.orEmpty().uppercase(Locale.ROOT)
        return topology.contains("P2P") || topology.contains("WIFI_DIRECT") || topology.contains("WI-FI DIRECT")
    }

    fun connect(
        profile: BikeProfile,
        timeoutMs: Long = 22_000L,
        onReady: (Link) -> Unit,
        onUnavailable: (String) -> Unit
    ) {
        val manager = p2p
        val ch = channel
        if (manager == null || ch == null) {
            onUnavailable("Wi-Fi Direct non disponibile su questo telefono")
            return
        }
        if (!hasPermission()) {
            onUnavailable("Permesso Wi-Fi Direct non concesso")
            return
        }

        releaseInternal(removeGroup = false, unregister = true)
        generation++
        val myGeneration = generation
        targetName = profile.ssid?.trim().orEmpty()
        connectRequested = false
        nextConnectAllowedAtMs = 0L
        connectionCreatedByUs = false
        completionDelivered = false
        groupFormed = false
        phoneIsGroupOwner = false
        ownerAddress = null
        localAddress = null
        groupPeerName = null
        groupName = null
        interfaceName = null
        activeLink = null
        this.onReady = onReady
        this.onUnavailable = onUnavailable

        registerReceiver(myGeneration)
        AppLog.add(
            "P2P START: ricerca WLAN Direct moto" +
                if (targetName.isNotBlank()) " target=${safeName(targetName)}" else ""
        )
        requestSnapshot(myGeneration)
        discoverPeers(myGeneration)

        main.postDelayed({ if (isCurrent(myGeneration)) requestPeers(myGeneration, allowConnect = true) }, 2_500L)
        main.postDelayed({ if (isCurrent(myGeneration) && !groupFormed) discoverPeers(myGeneration) }, 6_000L)
        main.postDelayed({ if (isCurrent(myGeneration)) requestPeers(myGeneration, allowConnect = true) }, 8_500L)

        val timeout = Runnable {
            if (!isCurrent(myGeneration) || completionDelivered) return@Runnable
            fail(myGeneration, "WLAN Direct della moto non disponibile")
        }
        timeoutRunnable = timeout
        main.postDelayed(timeout, timeoutMs)
    }

    fun release(removeGroup: Boolean = true) {
        generation++
        releaseInternal(removeGroup = removeGroup, unregister = true)
    }

    private fun registerReceiver(myGeneration: Long) {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isCurrent(myGeneration)) return
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        AppLog.add(
                            "P2P STATE: " +
                                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ENABLED" else "DISABLED/UNKNOWN"
                        )
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers(myGeneration, allowConnect = true)
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        AppLog.add("P2P EVENT: stato connessione cambiato")
                        requestSnapshot(myGeneration)
                    }
                }
            }
        }
        receiver = r
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(r, filter)
        }
    }

    private fun discoverPeers(myGeneration: Long) {
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!isCurrent(myGeneration)) return
        try {
            manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isCurrent(myGeneration)) return
                    AppLog.add("P2P DISCOVERY: avviata")
                }

                override fun onFailure(reason: Int) {
                    if (isCurrent(myGeneration)) AppLog.add("P2P DISCOVERY: fallita reason=$reason")
                }
            })
        } catch (_: SecurityException) {
            fail(myGeneration, "Permesso Wi-Fi Direct mancante")
        }
    }

    private fun requestPeers(myGeneration: Long, allowConnect: Boolean) {
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!isCurrent(myGeneration)) return
        try {
            manager.requestPeers(ch) { peers ->
                if (!isCurrent(myGeneration)) return@requestPeers
                val peer = choosePeer(peers)
                AppLog.add(
                    "P2P PEERS: count=${peers.deviceList.size}; qrTarget=${safeName(targetName)}; " +
                        "exactMatch=${peer?.deviceName?.let(::safeName) ?: "-"}"
                )
                val connectAllowedNow = SystemClock.elapsedRealtime() >= nextConnectAllowedAtMs
                if (allowConnect && !groupFormed && !connectRequested && connectAllowedNow && peer != null) {
                    connectPeer(myGeneration, peer)
                }
            }
        } catch (_: SecurityException) {
            fail(myGeneration, "Permesso Wi-Fi Direct mancante")
        }
    }

    private fun choosePeer(peers: WifiP2pDeviceList): WifiP2pDevice? {
        if (peers.deviceList.isEmpty()) return null
        val target = targetName.trim()
        if (target.isBlank()) return null
        return peers.deviceList.firstOrNull {
            it.deviceName.orEmpty().trim().equals(target, ignoreCase = true)
        }
    }

    private fun connectPeer(myGeneration: Long, peer: WifiP2pDevice) {
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!isCurrent(myGeneration) || connectRequested) return
        connectRequested = true
        AppLog.add("P2P CONNECT: peer=${safeName(peer.deviceName)} (indirizzo non loggato)")
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.deviceAddress // RAM only, never logged
            wps.setup = WpsInfo.PBC
        }
        try {
            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isCurrent(myGeneration)) return
                    connectionCreatedByUs = true
                    AppLog.add("P2P CONNECT: richiesta accettata; attendo formazione gruppo senza connect duplicati")
                    // One connect() stays in flight. Poll only connection/group state while Android
                    // negotiates the P2P group; do not re-invite the same or another peer.
                    main.postDelayed({ if (isCurrent(myGeneration)) requestSnapshot(myGeneration) }, 1_250L)
                    main.postDelayed({ if (isCurrent(myGeneration)) requestSnapshot(myGeneration) }, 3_000L)
                    main.postDelayed({ if (isCurrent(myGeneration)) requestSnapshot(myGeneration) }, 6_000L)
                }

                override fun onFailure(reason: Int) {
                    if (!isCurrent(myGeneration)) return
                    connectRequested = false
                    nextConnectAllowedAtMs = SystemClock.elapsedRealtime() + 2_000L
                    AppLog.add("P2P CONNECT: fallita reason=$reason; retry exact-target dopo cooldown")
                    main.postDelayed({ if (isCurrent(myGeneration)) discoverPeers(myGeneration) }, 2_000L)
                }
            })
        } catch (_: SecurityException) {
            connectRequested = false
            fail(myGeneration, "Permesso Wi-Fi Direct mancante")
        }
    }

    private fun requestSnapshot(myGeneration: Long) {
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!isCurrent(myGeneration)) return
        try {
            manager.requestConnectionInfo(ch) { info ->
                if (isCurrent(myGeneration)) handleConnectionInfo(myGeneration, info)
            }
            manager.requestGroupInfo(ch) { group ->
                if (isCurrent(myGeneration)) handleGroupInfo(myGeneration, group)
            }
        } catch (_: SecurityException) {
            fail(myGeneration, "Permesso Wi-Fi Direct mancante")
        }
    }

    private fun handleConnectionInfo(myGeneration: Long, info: WifiP2pInfo?) {
        if (info == null) return
        groupFormed = info.groupFormed
        phoneIsGroupOwner = info.isGroupOwner
        ownerAddress = info.groupOwnerAddress
        if (!info.groupFormed) activeLink = null
        AppLog.add(
            "P2P INFO: formed=${info.groupFormed}; phoneGO=${info.isGroupOwner}; " +
                "groupOwner=${info.groupOwnerAddress?.hostAddress ?: "-"}"
        )
        if (info.groupFormed && info.isGroupOwner) {
            fail(myGeneration, "La moto non è Group Owner WLAN Direct")
            return
        }
        maybeReady(myGeneration)
    }

    private fun handleGroupInfo(myGeneration: Long, group: WifiP2pGroup?) {
        if (group == null) return
        groupName = group.networkName
        groupPeerName = group.owner?.deviceName
        interfaceName = group.`interface`
        if (!interfaceName.isNullOrBlank()) localAddress = findIpv4OnInterface(interfaceName!!)
        AppLog.add(
            "P2P GROUP: name=${safeName(group.networkName)}; owner=${safeName(group.owner?.deviceName)}; " +
                "interface=${group.`interface` ?: "-"}"
        )
        maybeReady(myGeneration)
    }

    private fun maybeReady(myGeneration: Long) {
        if (!isCurrent(myGeneration) || completionDelivered) return
        if (!groupFormed || phoneIsGroupOwner) return
        val owner = ownerAddress as? Inet4Address ?: return
        val local = (localAddress as? Inet4Address) ?: findLikelyP2pIpv4()?.also { localAddress = it } ?: return
        if (owner == local) return
        val peer = groupPeerName?.takeIf { it.isNotBlank() } ?: targetName.ifBlank { "VOGE WLAN Direct" }
        val link = Link(local, owner, peer, groupName, interfaceName)
        activeLink = link
        completionDelivered = true
        timeoutRunnable?.let(main::removeCallbacks)
        timeoutRunnable = null
        AppLog.add("P2P LINK READY: phone=${local.hostAddress}; bikeGO=${owner.hostAddress}; peer=${safeName(peer)}")
        onReady?.invoke(link)
    }

    private fun findIpv4OnInterface(name: String): InetAddress? = try {
        NetworkInterface.getByName(name)?.let { ni ->
            Collections.list(ni.inetAddresses).firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        }
    } catch (_: Throwable) {
        null
    }

    private fun findLikelyP2pIpv4(): InetAddress? {
        return try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return null
            Collections.list(all).firstNotNullOfOrNull { ni ->
                val n = ni.name.orEmpty().lowercase(Locale.ROOT)
                if (!(n.contains("p2p") || n.startsWith("swlan") || n.startsWith("wlan1"))) return@firstNotNullOfOrNull null
                Collections.list(ni.inetAddresses).firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fail(myGeneration: Long, reason: String) {
        if (!isCurrent(myGeneration) || completionDelivered) return
        completionDelivered = true
        timeoutRunnable?.let(main::removeCallbacks)
        timeoutRunnable = null
        AppLog.add("P2P FAIL: $reason")
        onUnavailable?.invoke(reason)
    }

    private fun releaseInternal(removeGroup: Boolean, unregister: Boolean) {
        timeoutRunnable?.let(main::removeCallbacks)
        timeoutRunnable = null
        onReady = null
        onUnavailable = null
        completionDelivered = false
        activeLink = null
        groupFormed = false
        phoneIsGroupOwner = false
        ownerAddress = null
        localAddress = null
        groupPeerName = null
        groupName = null
        interfaceName = null
        connectRequested = false
        nextConnectAllowedAtMs = 0L

        if (unregister) {
            receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
            receiver = null
        }

        if (removeGroup && connectionCreatedByUs) {
            val manager = p2p
            val ch = channel
            if (manager != null && ch != null && hasPermission()) {
                runCatching {
                    manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { AppLog.add("P2P STOP: gruppo WLAN Direct rilasciato") }
                        override fun onFailure(reason: Int) { AppLog.add("P2P STOP: removeGroup reason=$reason") }
                    })
                }
            }
        }
        connectionCreatedByUs = false
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isCurrent(myGeneration: Long): Boolean = generation == myGeneration

    private fun safeName(value: String?): String = value.orEmpty()
        .replace(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"), "<mac-redacted>")
        .ifBlank { "-" }
}
