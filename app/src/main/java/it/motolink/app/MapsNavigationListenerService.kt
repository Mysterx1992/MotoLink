package it.motolink.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MapsNavigationListenerService : NotificationListenerService() {
    companion object {
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        if (item.packageName != MAPS_PACKAGE) return
        val notification = item.notification ?: return
        val category = notification.category.orEmpty()
        val isNavigation = category.equals(Notification.CATEGORY_NAVIGATION, ignoreCase = true) ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!isNavigation) return

        val model = MapsNavParser.parse(notification.extras)
        if (model.nextRoadDirection == 0) {
            AppLog.add("V1.6 MAPS: notifica navigazione letta ma manovra non riconosciuta; nessun dato inventato")
            return
        }
        if (!model.realDistance) {
            AppLog.add("V1.6 MAPS: manovra=${model.nextRoadDirection} ma distanza reale assente nella notifica; TX TFT sospeso")
            return
        }
        AppLog.add("V1.6 MAPS: update valido manovra=${model.nextRoadDirection} distanza=${model.curRoadRemainDistM}m")
        VogeNavService.submitModel(model)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == MAPS_PACKAGE) {
            AppLog.add("V1.6 MAPS: notifica navigazione rimossa")
        }
    }
}
