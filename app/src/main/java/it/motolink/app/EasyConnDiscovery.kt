package it.motolink.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.InetAddress

class EasyConnDiscovery(
    context: Context,
    private val onResolved: (ResolvedEasyConn) -> Unit
) {
    data class ResolvedEasyConn(
        val name: String,
        val host: InetAddress,
        val port: Int,
        val attributes: Map<String, String>
    )

    companion object {
        const val SERVICE_TYPE = "_EasyConn._tcp."
    }

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var running = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var generation = 0L

    @Synchronized
    fun start() {
        if (running) return
        running = true
        generation++
        val myGeneration = generation

        multicastLock = wifi.createMulticastLock("VogeMirror-mdns-$myGeneration").apply {
            setReferenceCounted(false)
            acquire()
        }

        // Android NSD listeners have lifecycle/state attached to the listener instance.
        // Create a fresh instance for every discovery cycle so a rapid stop/start cannot
        // reuse a listener that NsdManager still considers registered.
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (isCurrent(myGeneration, this)) AppLog.add("mDNS avviato: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!isCurrent(myGeneration, this)) return
                AppLog.add("EasyConn trovato: ${serviceInfo.serviceName}")
                if (!serviceInfo.serviceType.equals(SERVICE_TYPE, ignoreCase = true)) return
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(serviceInfo, newResolveListener(myGeneration))
                } catch (t: Throwable) {
                    AppLog.add("Resolve mDNS fallito: ${t.javaClass.simpleName}")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (isCurrent(myGeneration, this)) AppLog.add("EasyConn non più visibile: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (isCurrent(myGeneration, this)) AppLog.add("mDNS fermato")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (isCurrent(myGeneration, this)) {
                    AppLog.add("mDNS start error=$errorCode")
                    stop()
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (isCurrent(myGeneration, this)) AppLog.add("mDNS stop error=$errorCode")
            }
        }
        discoveryListener = listener

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            AppLog.add("Impossibile avviare mDNS: ${t.javaClass.simpleName}")
            stop()
        }
    }

    private fun newResolveListener(myGeneration: Long) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            if (isCurrentGeneration(myGeneration)) AppLog.add("Resolve EasyConn error=$errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            if (!isCurrentGeneration(myGeneration)) return
            @Suppress("DEPRECATION")
            val host = serviceInfo.host ?: return AppLog.add("EasyConn risolto senza IP")
            val attrs = linkedMapOf<String, String>()
            try {
                serviceInfo.attributes.forEach { (k, v) ->
                    attrs[k] = String(v, Charsets.UTF_8).take(120)
                }
            } catch (_: Throwable) {
            }
            AppLog.add("Voge/EasyConn: ${host.hostAddress}:${serviceInfo.port}")
            if (attrs.isNotEmpty()) AppLog.add("TXT mDNS keys: ${attrs.keys.sorted().joinToString()}")
            onResolved(ResolvedEasyConn(serviceInfo.serviceName, host, serviceInfo.port, attrs))
        }
    }

    @Synchronized
    private fun isCurrent(myGeneration: Long, listener: NsdManager.DiscoveryListener): Boolean =
        running && generation == myGeneration && discoveryListener === listener

    @Synchronized
    private fun isCurrentGeneration(myGeneration: Long): Boolean = running && generation == myGeneration

    @Synchronized
    fun stop() {
        val listener = discoveryListener
        discoveryListener = null
        running = false
        generation++ // invalidate late callbacks before asking NsdManager to stop
        if (listener != null) {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Throwable) {}
        }
        try { multicastLock?.release() } catch (_: Throwable) {}
        multicastLock = null
    }
}
