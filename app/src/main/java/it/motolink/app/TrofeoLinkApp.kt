package it.motolink.app

import android.app.Application

class TrofeoLinkApp : Application() {
    /**
     * One process-wide EasyConn listener owner.
     *
     * Keeping the listener set only inside MainActivity can cause a new
     * MainActivity while the previous EasyConn transport was intentionally kept
     * alive (for example by the lock placeholder path), the new Activity could
     * create a second listener set and hit EADDRINUSE on 10920/10921/10922.
     * Keeping the owner at Application scope makes STOP/START operate on the same
     * sockets across Activity recreation.
     */
    val easyConnServers: EasyConnServers by lazy { EasyConnServers(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
    }
}
