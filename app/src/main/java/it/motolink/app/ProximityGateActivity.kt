package it.motolink.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * Tiny 1x1 transparent Activity used only when the native proximity wake lock
 * is not framework-effective in background. It stays TOP_RESUMED while NEAR so
 * Android/HyperOS can keep the physical display off. No brightness change, black
 * overlay or synthetic screen-off is used.
 */
class ProximityGateActivity : Activity() {
    companion object {
        @Volatile private var current: WeakReference<ProximityGateActivity>? = null

        fun isActive(): Boolean = current?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

        fun finishIfActive(reason: String) {
            val activity = current?.get() ?: return
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    AppLog.add("PROX GATE FINISH: $reason -> chiudo Activity trasparente e torno all'app sottostante")
                    activity.finishAndRemoveTask()
                    activity.overridePendingTransition(0, 0)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.install(this)
        current = WeakReference(this)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // La Tiny Gate deve restare focusable/TOP_RESUMED per HyperOS, ma non deve
        // coprire l'app sottostante. Non usare FLAG_NOT_FOCUSABLE: toglierebbe
        // proprio il requisito che rende efficace il proximity wake-lock.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.attributes = window.attributes.apply {
            dimAmount = 0f
            gravity = Gravity.TOP or Gravity.START
            width = 1
            height = 1
            x = 0
            y = 0
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        })
        window.setLayout(1, 1)

        AppLog.add("PROX GATE ACTIVITY: CREATA Tiny Gate 1x1 trasparente")
    }

    override fun onResume() {
        super.onResume()
        AppLog.add("PROX GATE ACTIVITY: RESUMED")
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        AppLog.add("PROX GATE TOP RESUMED: $isTopResumedActivity")
        requestPowerSnapshot("GATE_TOP_RESUMED_${if (isTopResumedActivity) "TRUE" else "FALSE"}")
    }


    override fun onDestroy() {
        if (current?.get() === this) current = null
        AppLog.add("PROX GATE ACTIVITY: DISTRUTTA")
        super.onDestroy()
    }

    private fun requestPowerSnapshot(stage: String) {
        try {
            startService(Intent(this, MirrorService::class.java).apply {
                action = MirrorService.ACTION_PROX_SNAPSHOT
                putExtra(MirrorService.EXTRA_PROX_STAGE, stage)
            })
        } catch (t: Throwable) {
            AppLog.add("PROX GATE snapshot fallito [$stage]: ${t.javaClass.simpleName}: ${t.message ?: "-"}")
        }
    }
}
