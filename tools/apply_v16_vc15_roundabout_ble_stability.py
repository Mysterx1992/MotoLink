from pathlib import Path

p = Path("app/src/main/java/it/motolink/app/V16Navigation.kt")
s = p.read_text(encoding="utf-8")


def replace_exact(old: str, new: str, label: str, count: int = 1):
    global s
    found = s.count(old)
    if found != count:
        raise SystemExit(f"VC15 PATCH FAIL {label}: expected {count}, found {found}")
    s = s.replace(old, new, count)
    print(f"VC15 PATCH OK {label}")

# ---------------------------------------------------------------------------
# A) Google Maps roundabouts.
# The Voge encoder already has NavManeuver.ROUNDABOUT -> direction 1 + roadFlag 2.
# What was missing in vc14 was recognition of a roundabout glyph when Maps exposes
# the maneuver only as a RemoteViews image. Detect a *closed central loop* only;
# ambiguous graphics remain UNKNOWN. Also log a privacy-safe 8x8 fingerprint for
# unknown maneuver icons so future physical tests can be mapped without storing the
# notification bitmap or route text.
# ---------------------------------------------------------------------------
replace_exact(
    '    private const val ICON_SAMPLE = 48\n',
    '    private const val ICON_SAMPLE = 48\n    private var lastUnknownIconFingerprint = ""\n    private var lastUnknownIconLogAt = 0L\n',
    'maps_unknown_fingerprint_state',
)

old = '''        val maneuver = textManeuver ?: maneuverFromIcon(visual.maneuverIcon)
        val sourceKind = if (textManeuver != null) "TEXT" else if (maneuver != NavManeuver.UNKNOWN) "ICON" else "NONE"
'''
new = '''        val maneuver = textManeuver ?: maneuverFromIcon(visual.maneuverIcon)
        if (maneuver == NavManeuver.UNKNOWN && distance != null && visual.maneuverIcon != null) {
            maybeLogUnknownManeuverIcon(visual.maneuverIcon, distance)
        }
        val sourceKind = if (textManeuver != null) "TEXT" else if (maneuver != NavManeuver.UNKNOWN) "ICON" else "NONE"
'''
replace_exact(old, new, 'maps_log_unknown_icon')

old = '''        return try {
            val bg = scaled.getPixel(0, 0)
            val full = measureInk(scaled, bg, ICON_SAMPLE)
'''
new = '''        return try {
            val bg = scaled.getPixel(0, 0)
            if (looksLikeRoundaboutGlyph(scaled, bg)) return NavManeuver.ROUNDABOUT
            val full = measureInk(scaled, bg, ICON_SAMPLE)
'''
replace_exact(old, new, 'maps_roundabout_before_direction_heuristic')

anchor = '''    private fun measureInk(bitmap: Bitmap, bg: Int, endY: Int): Pair<Long, Long> {
'''
helpers = r'''    /**
     * Conservative roundabout detector for Maps' rendered notification glyph.
     * A roundabout icon contains a closed loop near the centre; ordinary turn,
     * slight-turn and U-turn arrows do not. We dilate by one pixel to tolerate
     * antialiasing gaps, flood-fill outside background, then look for a central
     * enclosed background component. If the evidence is weak, return false.
     */
    private fun looksLikeRoundaboutGlyph(bitmap: Bitmap, bg: Int): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return false
        val ink = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) ink[y][x] = isInk(bitmap.getPixel(x, y), bg)
        }

        // One-pixel dilation closes tiny antialiasing gaps without turning a normal
        // open arrow into a large central enclosed area.
        val solid = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!ink[y][x]) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) solid[ny][nx] = true
                }
            }
        }

        val outside = Array(h) { BooleanArray(w) }
        val q = java.util.ArrayDeque<Int>()
        fun push(x: Int, y: Int) {
            if (x !in 0 until w || y !in 0 until h || solid[y][x] || outside[y][x]) return
            outside[y][x] = true
            q.addLast(y * w + x)
        }
        for (x in 0 until w) { push(x, 0); push(x, h - 1) }
        for (y in 0 until h) { push(0, y); push(w - 1, y) }
        while (q.isNotEmpty()) {
            val v = q.removeFirst()
            val x = v % w
            val y = v / w
            push(x - 1, y); push(x + 1, y); push(x, y - 1); push(x, y + 1)
        }

        val visited = Array(h) { BooleanArray(w) }
        var bestArea = 0
        var bestCx = 0.0
        var bestCy = 0.0
        for (sy in 1 until h - 1) {
            for (sx in 1 until w - 1) {
                if (solid[sy][sx] || outside[sy][sx] || visited[sy][sx]) continue
                var area = 0
                var sumX = 0L
                var sumY = 0L
                val holes = java.util.ArrayDeque<Int>()
                visited[sy][sx] = true
                holes.addLast(sy * w + sx)
                while (holes.isNotEmpty()) {
                    val v = holes.removeFirst()
                    val x = v % w
                    val y = v / w
                    area++
                    sumX += x
                    sumY += y
                    fun holePush(nx: Int, ny: Int) {
                        if (nx !in 1 until w - 1 || ny !in 1 until h - 1) return
                        if (solid[ny][nx] || outside[ny][nx] || visited[ny][nx]) return
                        visited[ny][nx] = true
                        holes.addLast(ny * w + nx)
                    }
                    holePush(x - 1, y); holePush(x + 1, y); holePush(x, y - 1); holePush(x, y + 1)
                }
                if (area > bestArea) {
                    bestArea = area
                    bestCx = sumX.toDouble() / area.coerceAtLeast(1)
                    bestCy = sumY.toDouble() / area.coerceAtLeast(1)
                }
            }
        }

        if (bestArea !in 18..260) return false
        val dx = kotlin.math.abs(bestCx - (w - 1) / 2.0)
        val dy = kotlin.math.abs(bestCy - (h - 1) / 2.0)
        if (dx > w * 0.18 || dy > h * 0.18) return false

        // Avoid treating a tiny circular badge as a maneuver. A real roundabout
        // glyph needs substantial ink around the central enclosed area.
        val full = measureInk(bitmap, bg, h)
        return (full.first + full.second) >= 90
    }

    @Synchronized
    private fun maybeLogUnknownManeuverIcon(bitmap: Bitmap, distanceMeters: Int) {
        val fp = iconFingerprint(bitmap) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (fp == lastUnknownIconFingerprint && now - lastUnknownIconLogAt < 15_000L) return
        lastUnknownIconFingerprint = fp
        lastUnknownIconLogAt = now
        AppLog.add("MAPS NAV V1.6 ICON DIAG: manovra non classificata fp=$fp distance=${distanceMeters}m")
    }

    /** 8x8 binary fingerprint of the maneuver glyph only; no route text/image is stored. */
    private fun iconFingerprint(bitmap: Bitmap): String? {
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, ICON_SAMPLE, ICON_SAMPLE, true) }.getOrNull()
            ?: return null
        return try {
            val bg = scaled.getPixel(0, 0)
            var bits = 0UL
            val cell = ICON_SAMPLE / 8
            for (gy in 0 until 8) {
                for (gx in 0 until 8) {
                    var inkCount = 0
                    var total = 0
                    for (y in gy * cell until (gy + 1) * cell) {
                        for (x in gx * cell until (gx + 1) * cell) {
                            total++
                            if (isInk(scaled.getPixel(x, y), bg)) inkCount++
                        }
                    }
                    if (inkCount * 5 >= total) bits = bits or (1UL shl (gy * 8 + gx))
                }
            }
            bits.toString(16).padStart(16, '0')
        } catch (_: Throwable) {
            null
        } finally {
            if (scaled !== bitmap) runCatching { scaled.recycle() }
        }
    }

'''
if s.count(anchor) != 1:
    raise SystemExit(f"VC15 PATCH FAIL maps_helper_anchor: expected 1, found {s.count(anchor)}")
s = s.replace(anchor, helpers + anchor, 1)
print("VC15 PATCH OK maps_roundabout_helpers")

# ---------------------------------------------------------------------------
# B) BLE long-road stability.
# Physical 625DSX log: after minutes of successful writes, one missing GATT write
# callback was followed by repeated 30 s status=147 connect timeouts. Fix the
# recovery path without changing UUIDs, packet format, write type or normal pacing:
# - ignore callbacks from a GATT instance already superseded/closed;
# - never queue navigation before heartbeat verification after reconnect;
# - after a missing callback or failed reconnect, allow controller/TFT cooldown
#   and reacquire the device through a fresh scan instead of hammering cached GATT;
# - bounded backoff, reset only after a verified heartbeat ACK.
# ---------------------------------------------------------------------------
replace_exact(
    '    private var automaticFallbackSent = false\n',
    '    private var automaticFallbackSent = false\n    private var reconnectAttempt = 0\n',
    'ble_reconnect_attempt_state',
)

replace_exact(
    '    val isReady: Boolean get() = synchronized(this) { gatt != null && txChar != null }\n',
    '    val isReady: Boolean get() = synchronized(this) { gatt != null && txChar != null && transportVerified }\n',
    'ble_isready_requires_verified_transport',
)

old = '''    fun sendNavigation(model: TurnByTurnInstruction) {
        if (!model.hasRealDistance || model.maneuver == NavManeuver.UNKNOWN) {
'''
new = '''    fun sendNavigation(model: TurnByTurnInstruction) {
        if (!synchronized(this) { transportVerified }) {
            AppLog.add("BLE NAV V1.6: istruzione reale disponibile ma canale TFT non ancora verificato")
            return
        }
        if (!model.hasRealDistance || model.maneuver == NavManeuver.UNKNOWN) {
'''
replace_exact(old, new, 'ble_gate_nav_until_heartbeat_verified')

old = '''        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            AppLog.add("BLE NAV V1.6: connection state status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
'''
new = '''        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (synchronized(this@VogeBleNavigationManager) { gatt !== g }) {
                AppLog.add("BLE NAV V1.6: callback GATT obsoleto ignorato status=$status state=$newState")
                runCatching { g.close() }
                return
            }
            AppLog.add("BLE NAV V1.6: connection state status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
'''
replace_exact(old, new, 'ble_ignore_stale_gatt_callbacks')

replace_exact(
    '                if (wanted) scheduleReconnect("BLE disconnesso")\n',
    '                if (wanted) scheduleReconnect("BLE disconnesso status=$status", forceFreshScan = status != BluetoothGatt.GATT_SUCCESS)\n',
    'ble_failed_disconnect_uses_fresh_scan',
)

replace_exact(
    '                            heartbeatActive = true\n                            becameReady = true\n',
    '                            heartbeatActive = true\n                            reconnectAttempt = 0\n                            becameReady = true\n',
    'ble_reset_backoff_on_verified_ready',
)

replace_exact(
    '                scheduleReconnect("Timeout callback GATT")\n',
    '                scheduleReconnect("Timeout callback GATT", forceFreshScan = true, minDelayMs = 6000L)\n',
    'ble_watchdog_cooldown_fresh_scan',
)

old = '''    private fun scheduleReconnect(reason: String) {
        if (!wanted) return
        callback?.onState("Riconnessione Bluetooth…")
        AppLog.add("BLE NAV V1.6: $reason; riconnessione programmata")
        synchronized(this) {
            heartbeatActive = false
            transportVerified = false
            main.removeCallbacks(heartbeat)
            queue.clear()
            inFlight = null
            writeGeneration++
            txChar = null
            rxChar = null
        }
        closeGattOnly()
        val generation = ++reconnectGeneration
        main.postDelayed({
            if (!wanted || generation != reconnectGeneration) return@postDelayed
            val context = app ?: return@postDelayed
            val address = selectedAddress
            if (!address.isNullOrBlank() && hasConnectPermission(context)) {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
                if (device != null) {
                    connectDevice(device, selectedName)
                    return@postDelayed
                }
            }
            if (automatic && !automaticFallbackSent) unavailable(reason) else startScan()
        }, 1800L)
    }
'''
new = '''    private fun scheduleReconnect(reason: String, forceFreshScan: Boolean = false, minDelayMs: Long = 0L) {
        if (!wanted) return
        callback?.onState("Riconnessione Bluetooth…")
        val attempt = synchronized(this) {
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(6)
            reconnectAttempt
        }
        val backoffMs = when (attempt) {
            1 -> 2500L
            2 -> 4000L
            3 -> 6000L
            else -> 10000L
        }
        val delayMs = maxOf(minDelayMs, backoffMs)
        AppLog.add("BLE NAV V1.6: $reason; recovery attempt=$attempt delay=${delayMs}ms freshScan=$forceFreshScan")
        synchronized(this) {
            heartbeatActive = false
            transportVerified = false
            main.removeCallbacks(heartbeat)
            queue.clear()
            inFlight = null
            writeGeneration++
            txChar = null
            rxChar = null
        }
        closeGattOnly()
        val generation = ++reconnectGeneration
        main.postDelayed({
            if (!wanted || generation != reconnectGeneration) return@postDelayed
            val context = app ?: return@postDelayed

            // A lost write callback or a failed connection can leave the Android/TFT
            // link state stale for a few seconds. Re-scanning reacquires a currently
            // advertising device instead of starting another 30 s connect on a stale
            // cached path. From the second consecutive recovery attempt we always scan.
            if (forceFreshScan || attempt >= 2) {
                AppLog.add("BLE NAV V1.6: recovery -> nuova scansione BLE prima di connectGatt")
                if (automatic && !automaticFallbackSent && attempt >= 4) unavailable(reason) else startScan()
                return@postDelayed
            }

            val address = selectedAddress
            if (!address.isNullOrBlank() && hasConnectPermission(context)) {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
                if (device != null) {
                    connectDevice(device, selectedName)
                    return@postDelayed
                }
            }
            if (automatic && !automaticFallbackSent) unavailable(reason) else startScan()
        }, delayMs)
    }
'''
replace_exact(old, new, 'ble_recovery_backoff_fresh_scan')

# stopInternal must clear the recovery counter for a new user START.
replace_exact(
    '        reconnectGeneration++\n        scanGeneration++\n',
    '        reconnectGeneration++\n        reconnectAttempt = 0\n        scanGeneration++\n',
    'ble_reset_backoff_on_stop',
)

# Assertions: never change protocol identity or packet layout.
required = [
    'looksLikeRoundaboutGlyph',
    'MAPS NAV V1.6 ICON DIAG: manovra non classificata fp=',
    'roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 2 else 0',
    'NavManeuver.STRAIGHT, NavManeuver.ROUNDABOUT -> 1',
    'private var reconnectAttempt = 0',
    'callback GATT obsoleto ignorato',
    'recovery -> nuova scansione BLE prima di connectGatt',
    'scheduleReconnect("Timeout callback GATT", forceFreshScan = true, minDelayMs = 6000L)',
    'val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT',
    'UUID.fromString("5fe695f1-fd7b-4f9b-98cc-ee6cf57a776e")',
]
for marker in required:
    if marker not in s:
        raise SystemExit(f"VC15 PATCH VERIFY FAIL: {marker}")

p.write_text(s, encoding="utf-8")
print("VC15 ROUNDABOUT + BLE ROAD-STABILITY PATCH COMPLETE")
