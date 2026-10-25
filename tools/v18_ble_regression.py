#!/usr/bin/env python3
"""V1.8 inherited VC33 BLE static contract and isolated scheduler-model regression.

NOTE: synthetic model test is NOT an Android GATT test. Physical TFT remains mandatory.
"""
from pathlib import Path

nav = Path("app/src/main/java/it/motolink/app/V16Navigation.kt").read_text(encoding="utf-8")
activity = Path("app/src/main/java/it/motolink/app/MainActivity.kt").read_text(encoding="utf-8")
gradle = Path("app/build.gradle.kts").read_text(encoding="utf-8")

required_nav = {
  "NAV group serial": "private var activeNavGroup: Long? = null",
  "pending latest": "queue.filter { it.kind == Kind.NAV && it.navGroup == active }",
  "group sequence": "val newGroup = ++nextNavGroup",
  "preserve last frame": "frames.forEach { queue.addLast(Tx(it, Kind.NAV, navGroup = newGroup, queuedAtMs = receivedAtMs)) }",
  "completion reset": "queue.none { it.kind == Kind.NAV && it.navGroup == s.navGroup }",
  "write-inflight": "if (!wanted || inFlight != null || queue.isEmpty()) return",
  "GATT 100ms pace": "private const val NAV_PACE_MS = 100L",
  "heartbeat gate": "if (!navPending && !heartbeatPending && inFlight == null)",
  "scan retry": "private fun retryScanWhenBikeMissing(reason: String)",
  "duty cycle": "else -> 15000L",
  "scan generation guard": "wanted && generation == scanGeneration && !transportVerified",
  "scan timeout retry": 'retryScanWhenBikeMissing("Nessuna moto BLE compatibile trovata")',
  "stop cancels": "wanted = false",
  "initial AUTO fallback": "if (automatic && !everReady)",
  "ever connected": "everReady = true",
  "GATT queued timestamp": "NAV_GROUP_COMPLETE group=",
}
for label, needle in required_nav.items():
    assert needle in nav, f"Missing VC33 contract: {label}"
assert nav.count("everReady = true") == 2
assert "mapsLaunchRequested = true" in activity
assert "if (!mapsLaunchRequested)" in activity
assert "if (runSelection == RunSelection.START || startInProgress)" in activity
assert (
    ("versionCode = 34" in gradle and 'versionName = "1.8"' in gradle) or
    ("versionCode = 35" in gradle and 'versionName = "1.8.1"' in gradle)
), "Unexpected official MotoLink version identity"

# Pure-model stress: from a notification burst, allow one active coherent five-frame
# group and only the newest pending coherent five-frame group. This checks expected
# sequence semantics; the Kotlin implementation is statically guarded above.
from collections import deque

class Scheduler:
    def __init__(self):
        self.queue = deque()
        self.active = None
        self.inflight = None
        self.last_id = 0

    def notify(self, meters):
        keep = [x for x in self.queue if x[0] == self.active] if self.active is not None else []
        self.last_id += 1
        self.queue = deque(keep + [(self.last_id, i, meters) for i in range(5)])

    def next(self):
        assert self.inflight is None
        if not self.queue: return None
        frame = self.queue.popleft()
        if self.active is None: self.active = frame[0]
        assert frame[0] == self.active
        self.inflight = frame
        return frame

    def ack(self):
        assert self.inflight is not None
        sent = self.inflight
        self.inflight = None
        if not any(x[0] == sent[0] for x in self.queue):
            self.active = None
        return sent

# A first group (90m) already started, notification burst arrives (80..50).
q = Scheduler()
q.notify(90)
first = q.next()
assert first == (1, 0, 90)
for meters in (80, 70, 60, 50):
    q.notify(meters)
sent = []
while q.inflight is not None or q.queue:
    if q.inflight is None: q.next()
    sent.append(q.ack())
    if q.queue: q.next()
assert len(sent) == 10
assert [x[2] for x in sent] == [90] * 5 + [50] * 5, sent
assert [x[1] for x in sent] == list(range(5)) * 2

# Nothing in flight: every new update replaces even a fully queued older group.
q = Scheduler()
for v in (90,80,70,60,50): q.notify(v)
assert [v[2] for v in q.queue] == [50]*5

print("PASS static Kotlin contract: 16 checks")
print("PASS synthetic latest-wins atomic NAV group and burst/no-inflight model")
print("NOTE: physical GATT/TFT roundabouts, STOP and ignition OFF/ON remain pending")
