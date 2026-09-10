from pathlib import Path
import re

MAIN = Path("app/src/main/java/it/motolink/app/MainActivity.kt")
NAV = Path("app/src/main/java/it/motolink/app/V16Navigation.kt")
main = MAIN.read_text(encoding="utf-8")
nav = NAV.read_text(encoding="utf-8")


def replace_exact(text: str, old: str, new: str, label: str, count: int = 1) -> str:
    found = text.count(old)
    if found != count:
        raise SystemExit(f"V1.6 FIX FAIL {label}: expected {count}, found {found}")
    out = text.replace(old, new, count)
    print(f"V1.6 FIX OK {label}")
    return out

# ---------------------------------------------------------------------------
# 1) Remove Automatic from all user-facing profile connection selectors.
# Keep the AUTOMATIC enum/parser internally only so already-saved V1.6 profiles
# can be migrated explicitly on their first START.
# ---------------------------------------------------------------------------
main = replace_exact(
    main,
    'listOf("Automatico", "Hotspot", "QrCode", "BLE")',
    'listOf("Hotspot", "QrCode", "BLE")',
    "remove_automatic_from_connection_lists",
    count=2,
)

# Remove unreachable label mappings for the removed UI option.
auto_mapping_pattern = re.compile(r'^\s*"Automatico" -> "AUTOMATIC"\s*\n', re.M)
main, n = auto_mapping_pattern.subn('', main)
if n != 2:
    raise SystemExit(f"V1.6 FIX FAIL remove_automatic_label_mappings: expected 2, found {n}")
print("V1.6 FIX OK remove_automatic_label_mappings")

# Re-index Garage selectors after removing the first item. AUTOMATIC remains -1
# so Garage does not silently display Hotspot for a legacy Automatic profile.
old_index = '''        return when {
            type == "BLE_NAV" -> 3
            type == "AUTOMATIC" -> 0
            format == "HOTSPOT" || format.contains("MANUAL") -> 1
            v16GarageHasQrIdentity(profile) -> 2
            type == "HOTSPOT_EASYCONN" -> 1
            else -> 0
        }
'''
new_index = '''        return when {
            type == "BLE_NAV" -> 2
            type == "AUTOMATIC" -> -1
            format == "HOTSPOT" || format.contains("MANUAL") -> 0
            v16GarageHasQrIdentity(profile) -> 1
            type == "HOTSPOT_EASYCONN" -> 0
            else -> 0
        }
'''
main = replace_exact(main, old_index, new_index, "garage_connection_reindex")

main = replace_exact(
    main,
    '            setSelection(v16GarageConnectionIndex(baseProfile))\n',
    '            setSelection(v16GarageConnectionIndex(baseProfile))\n',
    "garage_creation_selection_anchor",
)
main = replace_exact(
    main,
    '            setSelection(v16GarageConnectionIndex(profile))\n',
    '''            val connectionIndex = v16GarageConnectionIndex(profile)
            if (connectionIndex >= 0) setSelection(connectionIndex) else setSelection(-1)
''',
    "garage_edit_legacy_automatic_blank",
)

# Guard the legacy blank selection in Garage edit before indexing the list.
old_edit_select = '''            val chosenCatalog = catalogOptions[catalog.selectedItemPosition]
            val selectedConnection = connectionOptions[connection.selectedItemPosition]

            if (selectedConnection == "QrCode" && !v16GarageHasQrIdentity(profile)) {
'''
new_edit_select = '''            val chosenCatalog = catalogOptions[catalog.selectedItemPosition]
            val selectedConnection = connection.selectedItem?.toString()
            if (selectedConnection.isNullOrBlank()) {
                showQrError("Scegli la connessione: Hotspot, QrCode oppure BLE.")
                return
            }

            if (selectedConnection == "QrCode" && !v16GarageHasQrIdentity(profile)) {
'''
main = replace_exact(main, old_edit_select, new_edit_select, "garage_edit_requires_explicit_legacy_choice")

# Existing AUTOMATIC profile: do not run the old BLE->Hotspot fallback. Ask once
# and persist the user's explicit choice. QR reuses the existing in-place Garage
# QR merge path so it does not create a duplicate profile.
main = replace_exact(
    main,
    '            BikeConnectionType.AUTOMATIC -> startV16BleNavigation(profile, automatic = true)\n',
    '            BikeConnectionType.AUTOMATIC -> showV16LegacyAutomaticMigrationChoice(profile)\n',
    "automatic_start_becomes_explicit_migration",
)

migration_fun = r'''    private fun showV16LegacyAutomaticMigrationChoice(profile: BikeProfile) {
        if (runSelection != RunSelection.START) return
        val options = listOf("Hotspot", "QrCode", "BLE")
        val label = TextView(this).apply {
            text = "Questo profilo era impostato su Automatico. Scegli la connessione da usare con questa moto."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
        }
        val choice = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(-1)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(choice, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * resources.displayMetrics.density).toInt()))
        }
        var handled = false
        val dialog = NeonDialogs.showCustom(
            activity = this,
            title = "Scegli connessione",
            message = "Hotspot, QrCode oppure Bluetooth BLE.",
            contentView = box,
            positiveText = "CONTINUA",
            negativeText = "ANNULLA",
            onPositive = {
                handled = true
                val selected = choice.selectedItem?.toString()
                if (selected.isNullOrBlank()) {
                    NeonDialogs.showInfo(
                        activity = this,
                        title = "Connessione richiesta",
                        message = "Scegli Hotspot, QrCode oppure BLE.",
                        onPositive = { showV16LegacyAutomaticMigrationChoice(profile) }
                    )
                    return@showCustom
                }
                val activeIndex = BikeProfileStore.activeIndex(this)
                when (selected) {
                    "Hotspot" -> {
                        BikeProfileStore.updateConnection(this, activeIndex, "HOTSPOT_EASYCONN", clearBle = true)
                        AppLog.add("PROFILO V1.6: migrazione AUTOMATIC -> HOTSPOT_EASYCONN scelta dall'utente")
                        continueV16HotspotStart()
                    }
                    "QrCode" -> {
                        pendingV16GarageQrProfileName = profile.displayName
                        pendingV16GarageQrCatalogLabel = profile.catalogLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Altro modello"
                        pendingV16GarageQrEditIndex = activeIndex
                        startInProgress = false
                        setRunSelection(RunSelection.NONE)
                        AppLog.add("PROFILO V1.6: migrazione AUTOMATIC -> QRCODE scelta dall'utente; apro scanner")
                        startQrCameraScan()
                    }
                    "BLE" -> {
                        BikeProfileStore.updateConnection(this, activeIndex, "BLE_NAV", formatOverride = "BLE_NAV")
                        AppLog.add("PROFILO V1.6: migrazione AUTOMATIC -> BLE_NAV scelta dall'utente")
                        val updated = BikeProfileStore.load(this) ?: profile.copy(connectionType = "BLE_NAV")
                        startV16BleNavigation(updated, automatic = false)
                    }
                }
            },
            onNegative = {
                handled = true
                startInProgress = false
                setRunSelection(RunSelection.NONE)
                setState("Connessione non scelta", "Scegli Hotspot, QrCode oppure BLE al prossimo START", C_AMBER, "!")
            }
        )
        dialog.setOnDismissListener {
            mainHandler.post {
                if (!handled) {
                    startInProgress = false
                    setRunSelection(RunSelection.NONE)
                }
            }
        }
    }

'''
anchor = '    private fun continueV16HotspotStart() {\n'
if main.count(anchor) != 1:
    raise SystemExit(f"V1.6 FIX FAIL insert_legacy_migration_function: anchor count={main.count(anchor)}")
main = main.replace(anchor, migration_fun + anchor, 1)
print("V1.6 FIX OK insert_legacy_migration_function")

# Release note must no longer advertise Automatic.
old_note = "Nuova configurazione del profilo con scelta tra Automatica, Hotspot / QR e Bluetooth BLE."
new_note = "Nuova configurazione del profilo con scelta tra Hotspot, QrCode e Bluetooth BLE."
if old_note in main:
    main = main.replace(old_note, new_note, 1)
    print("V1.6 FIX OK release_note_remove_automatic")
else:
    print("V1.6 FIX NOTE release_note phrase not found; no replacement needed")

# ---------------------------------------------------------------------------
# 2) BLE transport: preserve exact Voge UUID/protocol and WRITE_TYPE_DEFAULT.
# Match the physically successful bridge by waiting 750 ms after CCCD setup,
# and do not declare READY until the first 0x5A write callback is GATT_SUCCESS.
# ---------------------------------------------------------------------------
nav = replace_exact(
    nav,
    '    private var heartbeatActive = false\n',
    '    private var heartbeatActive = false\n    private var transportVerified = false\n',
    "ble_add_transport_verified",
)

# Reset verification anywhere the active heartbeat is torn down.
nav = nav.replace('            heartbeatActive = false\n            main.removeCallbacks(heartbeat)\n',
                  '            heartbeatActive = false\n            transportVerified = false\n            main.removeCallbacks(heartbeat)\n')
nav = nav.replace('        heartbeatActive = false\n        main.removeCallbacks(heartbeat)\n',
                  '        heartbeatActive = false\n        transportVerified = false\n        main.removeCallbacks(heartbeat)\n')

# Descriptor success is the transport-prepared point, not user READY.
old_desc = '''        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD) {
                AppLog.add("BLE NAV V1.6: notify CCCD status=$status")
                markReady()
            }
        }
'''
new_desc = '''        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD) {
                AppLog.add("BLE NAV V1.6: notify CCCD status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    armInitialHeartbeatProbe("CCCD confermato")
                } else {
                    recoverOrFallback("Abilitazione notifiche BLE fallita ($status)")
                }
            }
        }
'''
nav = replace_exact(nav, old_desc, new_desc, "ble_cccd_success_gate")

# Fallback paths (no descriptor / descriptor request not accepted) may still
# verify TX, but with the same 750 ms stabilization delay.
nav = replace_exact(nav, '                if (!accepted) markReady()\n',
                    '                if (!accepted) armInitialHeartbeatProbe("CCCD write non accettata; verifico TX")\n',
                    "ble_descriptor_immediate_failure_probe")
nav = replace_exact(nav, '                markReady()\n',
                    '                armInitialHeartbeatProbe("RX notify non disponibile; verifico TX")\n',
                    "ble_no_notify_probe")

old_ready = '''    @Synchronized
    private fun markReady() {
        if (!wanted || gatt == null || txChar == null) return
        heartbeatActive = true
        main.removeCallbacks(heartbeat)
        main.post(heartbeat)
        val address = selectedAddress.orEmpty()
        callback?.onState("Bluetooth navigazione connesso")
        callback?.onReady(selectedName, address)
        AppLog.add("BLE NAV V1.6: READY service/TX/RX esatti; heartbeat 0x5A attivo")
    }
'''
new_ready = '''    @Synchronized
    private fun armInitialHeartbeatProbe(reason: String) {
        if (!wanted || gatt == null || txChar == null || transportVerified) return
        heartbeatActive = false
        main.removeCallbacks(heartbeat)
        callback?.onState("Bluetooth collegato, verifico canale navigazione…")
        AppLog.add("BLE NAV V1.6: GATT preparato ($reason); attendo 750 ms prima del heartbeat 0x5A di verifica")
        main.postDelayed({
            val context = app ?: return@postDelayed
            synchronized(this) {
                if (!wanted || transportVerified || gatt == null || txChar == null || inFlight != null) return@postDelayed
                val navPending = queue.any { it.kind == Kind.NAV }
                val heartbeatPending = queue.any { it.kind == Kind.HEARTBEAT }
                if (!navPending && !heartbeatPending) {
                    queue.addLast(Tx(VogeNavPacketEncoder.heartbeatFrame(context), Kind.HEARTBEAT))
                }
            }
            sendNext()
        }, 750L)
    }
'''
nav = replace_exact(nav, old_ready, new_ready, "ble_ready_after_probe_not_cccd")

old_write_callback = '''            if (sent == null) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                main.postDelayed(::sendNext, if (sent.kind == Kind.NAV) NAV_PACE_MS else 0L)
            } else {
                AppLog.add("BLE NAV V1.6: write callback status=$status; recupero connessione senza sovrapporre scritture")
                scheduleReconnect("Write GATT fallita ($status)")
            }
'''
new_write_callback = '''            if (sent == null) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                var becameReady = false
                if (sent.kind == Kind.HEARTBEAT) {
                    synchronized(this@VogeBleNavigationManager) {
                        if (!transportVerified && wanted && gatt != null && txChar != null) {
                            transportVerified = true
                            heartbeatActive = true
                            becameReady = true
                        }
                    }
                }
                if (becameReady) {
                    val address = selectedAddress.orEmpty()
                    callback?.onState("Bluetooth navigazione connesso")
                    callback?.onReady(selectedName, address)
                    AppLog.add("BLE NAV V1.6: READY verificato dopo ACK heartbeat 0x5A status=0")
                    main.removeCallbacks(heartbeat)
                    main.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
                }
                main.postDelayed(::sendNext, if (sent.kind == Kind.NAV) NAV_PACE_MS else 0L)
            } else if (status == 3) {
                failWithoutReconnect("Il TFT ha rifiutato la scrittura BLE di verifica (status 3)")
            } else {
                AppLog.add("BLE NAV V1.6: write callback status=$status; recupero connessione senza sovrapporre scritture")
                scheduleReconnect("Write GATT fallita ($status)")
            }
'''
nav = replace_exact(nav, old_write_callback, new_write_callback, "ble_ready_only_after_heartbeat_ack")

# Never let a Maps instruction enter the transport before heartbeat verification.
m = re.search(r'(\n    fun sendInstruction\([^\n]+\) \{\n)', nav)
if not m:
    raise SystemExit("V1.6 FIX FAIL ble_gate_navigation_before_verified: sendInstruction not found")
insert = m.group(1) + '''        if (!transportVerified) {
            AppLog.add("BLE NAV V1.6: istruzione Maps ignorata finché heartbeat 0x5A non è verificato")
            return
        }
'''
nav = nav[:m.start()] + insert + nav[m.end():]
print("V1.6 FIX OK ble_gate_navigation_before_verified")

# A deterministic status=3 must not create the observed reconnect storm and
# subsequent status=147 cascade. Stop this session once and let the UI report it.
fail_helper = r'''    private fun failWithoutReconnect(reason: String) {
        if (!wanted) return
        AppLog.add("BLE NAV V1.6: $reason; sessione fermata senza loop di riconnessione")
        callback?.onUnavailable(reason)
        stopInternal(notify = false)
    }

'''
anchor2 = '    private fun scheduleReconnect(reason: String) {\n'
if nav.count(anchor2) != 1:
    raise SystemExit(f"V1.6 FIX FAIL ble_insert_no_loop_failure: anchor count={nav.count(anchor2)}")
nav = nav.replace(anchor2, fail_helper + anchor2, 1)
print("V1.6 FIX OK ble_insert_no_loop_failure")

# ---------------------------------------------------------------------------
# Final fail-fast assertions.
# ---------------------------------------------------------------------------
if 'listOf("Automatico", "Hotspot", "QrCode", "BLE")' in main:
    raise SystemExit("V1.6 FIX FINAL FAIL: Automatico still present in user option lists")
if 'BikeConnectionType.AUTOMATIC -> startV16BleNavigation(profile, automatic = true)' in main:
    raise SystemExit("V1.6 FIX FINAL FAIL: old Automatic runtime branch still active")
for required in [
    'listOf("Hotspot", "QrCode", "BLE")',
    'BikeConnectionType.AUTOMATIC -> showV16LegacyAutomaticMigrationChoice(profile)',
    'PROFILO V1.6: migrazione AUTOMATIC -> BLE_NAV scelta dall\'utente',
]:
    if required not in main:
        raise SystemExit(f"V1.6 FIX FINAL FAIL MainActivity missing: {required}")
for required in [
    'attendo 750 ms prima del heartbeat 0x5A di verifica',
    'READY verificato dopo ACK heartbeat 0x5A status=0',
    'sessione fermata senza loop di riconnessione',
    'val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT',
]:
    if required not in nav:
        raise SystemExit(f"V1.6 FIX FINAL FAIL V16Navigation missing: {required}")

MAIN.write_text(main, encoding="utf-8")
NAV.write_text(nav, encoding="utf-8")
print("V1.6 BLE TRANSPORT + AUTOMATIC REMOVAL FIX COMPLETE")
