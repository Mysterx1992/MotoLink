from pathlib import Path

p = Path("app/src/main/java/it/motolink/app/V16Navigation.kt")
s = p.read_text(encoding='utf-8')

def rep(old,new,label,count=1):
    global s
    found=s.count(old)
    if found!=count:
        raise SystemExit(f'{label}: expected {count}, found {found}')
    s=s.replace(old,new,count)
    print('OK',label)

rep(
'''    private var reconnectAttempt = 0\n''',
'''    private var reconnectAttempt = 0\n    private var writeTypeMode = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT\n    private var noResponseFallbackTried = false\n''',
'write mode state')

rep(
'''            synchronized(this@VogeBleNavigationManager) {\n                txChar = tx\n                rxChar = rx\n            }\n            val localNotify = runCatching { g.setCharacteristicNotification(rx, true) }.getOrDefault(false)\n''',
'''            synchronized(this@VogeBleNavigationManager) {\n                txChar = tx\n                rxChar = rx\n            }\n            val supportsWrite = (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0\n            val supportsNoResponse = (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0\n            AppLog.add("BLE NAV V1.6: TX properties=0x${tx.properties.toString(16)} write=$supportsWrite noResponse=$supportsNoResponse mode=DEFAULT")\n            val localNotify = runCatching { g.setCharacteristicNotification(rx, true) }.getOrDefault(false)\n''',
'log tx properties')

rep(
'''            } else if (status == 3) {\n                failWithoutReconnect("Il TFT ha rifiutato la scrittura BLE di verifica (status 3)")\n            } else {\n''',
'''            } else if (status == 3 && sent.kind == Kind.HEARTBEAT && !transportVerified && tryEnableNoResponseFallback()) {\n                AppLog.add("BLE NAV V1.6 VC17: heartbeat DEFAULT rifiutato status=3; ritento una volta con WRITE_TYPE_NO_RESPONSE")\n                synchronized(this@VogeBleNavigationManager) { queue.addFirst(sent.copy(attempts = sent.attempts + 1)) }\n                main.postDelayed(::sendNext, 180L)\n            } else if (status == 3) {\n                failWithoutReconnect("Il TFT ha rifiutato la scrittura BLE (status 3)")\n            } else {\n''',
'status3 adaptive fallback')

rep(
'''        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT\n        val result = runCatching {\n''',
'''        val writeType = synchronized(this) { writeTypeMode }\n        val result = runCatching {\n''',
'dynamic write type')

rep(
'''        if (result == 0) {\n            val generation: Long\n            synchronized(this) {\n                inFlight = tx\n                generation = ++writeGeneration\n            }\n            main.postDelayed({\n                synchronized(this) {\n                    if (generation != writeGeneration || inFlight == null || !wanted) return@postDelayed\n                }\n                AppLog.add("BLE NAV V1.6: callback write assente; riconnetto senza liberare artificialmente writeInFlight")\n                scheduleReconnect("Timeout callback GATT", forceFreshScan = true, minDelayMs = 6000L)\n            }, WRITE_WATCHDOG_MS)\n''',
'''        if (result == 0) {\n            val generation: Long\n            synchronized(this) {\n                inFlight = tx\n                generation = ++writeGeneration\n            }\n            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {\n                // A Write Command has no ATT response by design. Some Android stacks still emit\n                // onCharacteristicWrite, others do not. Keep the one-write-in-flight invariant,\n                // but release it locally after a short settle window if no callback arrived.\n                main.postDelayed({\n                    var becameReady = false\n                    synchronized(this) {\n                        if (generation != writeGeneration || inFlight == null || !wanted) return@postDelayed\n                        inFlight = null\n                        writeGeneration++\n                        if (tx.kind == Kind.HEARTBEAT && !transportVerified) {\n                            transportVerified = true\n                            heartbeatActive = true\n                            reconnectAttempt = 0\n                            becameReady = true\n                        }\n                    }\n                    if (becameReady) {\n                        val address = selectedAddress.orEmpty()\n                        callback?.onState("Bluetooth navigazione connesso")\n                        callback?.onReady(selectedName, address)\n                        AppLog.add("BLE NAV V1.6 VC17: READY con WRITE_TYPE_NO_RESPONSE; nessun ACK ATT richiesto")\n                        main.removeCallbacks(heartbeat)\n                        main.postDelayed(heartbeat, 1000L)\n                    }\n                    main.postDelayed(::sendNext, if (tx.kind == Kind.NAV) NAV_PACE_MS else 0L)\n                }, 180L)\n            } else {\n                main.postDelayed({\n                    synchronized(this) {\n                        if (generation != writeGeneration || inFlight == null || !wanted) return@postDelayed\n                    }\n                    AppLog.add("BLE NAV V1.6: callback write assente; riconnetto senza liberare artificialmente writeInFlight")\n                    scheduleReconnect("Timeout callback GATT", forceFreshScan = true, minDelayMs = 6000L)\n                }, WRITE_WATCHDOG_MS)\n            }\n''',
'no response settle path')

anchor='''    private fun failWithoutReconnect(reason: String) {\n'''
helper='''    @Synchronized\n    private fun tryEnableNoResponseFallback(): Boolean {\n        if (noResponseFallbackTried || writeTypeMode != BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) return false\n        val tx = txChar ?: return false\n        val supported = (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0\n        noResponseFallbackTried = true\n        if (!supported) {\n            AppLog.add("BLE NAV V1.6 VC17: status=3 ma TX non dichiara WRITE_NO_RESPONSE; fallback non disponibile")\n            return false\n        }\n        writeTypeMode = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE\n        AppLog.add("BLE NAV V1.6 VC17: fallback trasporto attivato WRITE_TYPE_NO_RESPONSE")\n        return true\n    }\n\n'''
if s.count(anchor)!=1: raise SystemExit('helper anchor')
s=s.replace(anchor,helper+anchor,1)

rep(
'''        automaticFallbackSent = false\n        selectedAddress = savedAddress?.takeIf { it.isNotBlank() }\n''',
'''        automaticFallbackSent = false\n        writeTypeMode = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT\n        noResponseFallbackTried = false\n        selectedAddress = savedAddress?.takeIf { it.isNotBlank() }\n''',
'reset new start')

required=[
'private var writeTypeMode = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT',
'PROPERTY_WRITE_NO_RESPONSE',
'heartbeat DEFAULT rifiutato status=3',
'WRITE_TYPE_NO_RESPONSE; nessun ACK ATT richiesto',
'tryEnableNoResponseFallback()',
'val writeType = synchronized(this) { writeTypeMode }',
'roadFlag = if (maneuver == NavManeuver.ROUNDABOUT) 4 else 0',
]
for m in required:
    if m not in s: raise SystemExit('missing '+m)

p.write_text(s,encoding='utf-8')
print('VC17 VALICO 900 ADAPTIVE WRITE PATCH COMPLETE')
