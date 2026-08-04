# TrailInk GPS — Android companion app

Sends the phone's GPS position to the Xteink X4 over BLE and records the ride
for replay. One window. Built to `docs/android-app-brief.md`.

Package `org.trailink.gpsbridge`, app label **TrailInk GPS**.
minSdk 31, targetSdk 36, compileSdk 36. Debug-signed only.

## What it does

1. Scans for `XteinkX4Map`, connects, no pairing.
2. Writes 19 bytes to characteristic `5a1e6d00-73a4-4f1e-9b8f-2c6e1a8f0002`
   whenever the position has actually moved — see the send policy below.
3. Records every raw GPS fix, every packet written (including failures) and
   every link event to one JSON Lines file per recording — started and stopped
   by the user.

All of it runs in a foreground service, so it keeps going with the screen
locked and the app swiped away.

It does not touch the command characteristic (`...0003`). No route logic, no
cloud.

## Two things the brief got wrong, reversed after first field use

The brief listed a background service as a non-goal and left the logging toggle
optional. Both were wrong in the field and were changed:

- **Sending died the moment the screen locked.** Holding a phone screen awake in
  a handlebar bag is not a plan. Everything now lives in `BridgeService`, a
  foreground service of type `location|connectedDevice`, with a partial wake
  lock so the 5 s timer still fires in Doze. No
  `ACCESS_BACKGROUND_LOCATION` is needed: a location-typed foreground service
  started from a visible activity counts as in-use.
- **There was no way to say "record this ride".** Recording is now an explicit
  Start/Stop, in the window and as a notification action. Each start opens a new
  file with its own header.

- **The fixed 5 s cadence woke the device for nothing.** Every write changes
  `seq`, which wakes the X4 and can cost it a redraw. Sitting out a lunch hour
  used to cost 720 packets for a position that never changed. The interval is
  now distance-driven; see below.

A third thing fell out of the first real ride test, and it was a genuine bug:
after the X4 dropped the link (`gatt status 19`, peer terminated) the app never
found it again. Cause: **since Android 8.1 an unfiltered BLE scan returns
nothing while the screen is off** — exactly the case this app is for. Fixed two
ways: the scan now carries `ScanFilter`s (by name, and by service UUID), and a
drop is first answered by a direct `autoConnect = true` reconnect to the
remembered address, which needs no scan at all. Three tries with a 25 s timeout
each, then fall back to the filtered scan.

## Send policy

`SendPolicy.kt`. Distance-driven, not clock-driven — which makes it
speed-adaptive with no speed term in the decision at all.

| | |
|---|---|
| never faster than | **5 s** — the floor |
| never slower than | **60 min** — the keep-alive, so the device knows the phone is alive |
| in between, send when | the position moved **25 m** from the last sent one |
| also send when | the 16-sector heading changed **and** at least 10 m was covered |
| move threshold is raised to | the fix's own accuracy, so a bad fix indoors triggers nothing |

What that works out to:

| Situation | Packets |
|---|---|
| 90 km/h on a road | one every 5 s — the floor, same as the old fixed cadence |
| 18 km/h | one every 5 s, the break-even point |
| 5 km/h hiking | one every ~18 s |
| parked, lunch break | one an hour |

The heading rule needs real movement behind it because a stationary phone's
bearing wanders across all 16 sectors on GPS noise alone.

Location updates stay at 1 Hz regardless — the policy gates the **BLE write**,
not the fix rate. The raw-fix stream in the recording stays dense, which is what
`docs/replay-concept.md` wants: a replay can re-derive packets under a different
cadence from the raw fixes, and it cannot do that from a thinned-out stream. A
real 60-second stationary window recorded 40 fixes and 0 packets.

Every packet line carries why it went out (`reason`: `first` / `moved` /
`heading` / `keepalive`), how far the phone had moved (`moved_m`) and how long it
had been quiet (`since_last_ms`). The header carries the four bounds. A reader
therefore knows which policy produced the file and can replace it.

`SendPolicy` is deliberately free of Android types: it is the part with the
reasoning in it, so it is the part with unit tests on it (11 of them).

## Fix gate

`FixGate.kt`. Upstream of the send policy and a separate cadence from it:
this decides which fix is *trusted* as the phone's position at all, before
`SendPolicy` ever asks whether that position is worth a BLE write.

A phone in the field asks for GPS and network fixes together. GPS holds a
tight, steady accuracy; a network fix that races in beside it can land tens
to hundreds of metres away, at whatever accuracy it likes to claim for
itself. Taking every fix as-is, newest wins, made that network fix look like
the phone teleported — a real jump the app itself produced, not GPS noise.

The rule: a fix that could not have got here from the last trusted one at a
plausible speed (55 m/s, well past motorcycle pace, tempered only by the
*previous* fix's own accuracy — never the new fix's self-reported one) is
held back, not used, for one more location update. If the next fix lands
nearer the held-back position than the old one, the phone kept moving that
way and it was real; the held-back fix and the confirming one are both
accepted. If it snaps back near the old position instead, the held-back fix
was noise and is dropped. A hold that gets no next fix within 3 s times out
and is dropped too — bounded by the ~1 Hz fix rate, not the 5 s send floor,
so the map never sits frozen for a full send cycle waiting on a fix that
still hasn't arrived.

Field logs from 2026-08-04 showed the hold-and-confirm rule above isn't
enough on its own: a network fix's error radius is wide enough that a
following fix can legitimately land nearer the bad position than the real
one, "confirming" a jump that never happened. All confirmed jumps in those
logs were network fixes racing a GPS that was still live — so a network fix
is now ignored outright, never even held, whenever a GPS fix has answered in
the last 5 s (`FixGate.GPS_LIVE_WINDOW_MS`). Only once GPS has been quiet
longer than that — a real outage, not a live GPS being raced — does a
network fix reach the hold-and-confirm rule at all, since at that point it's
the only signal left.

Every fix is still logged raw regardless of this decision, per
`docs/replay-concept.md` — the gate decides what the live app trusts, not
what the recording keeps.

## Version

One source, `appVersion` in `app/build.gradle.kts`. Debug builds append the
commit they were built from, plus `-dirty` for an uncommitted tree, the same
habit as the firmware's `TRAILINK_VERSION`:

```
TrailInk GPS 0.2.0-g3f38ecd (2)
```

Shown small and grey at the bottom of the one window, and written into every
recording's header as `app_version`. On a sideloaded debug build "which of six
builds is on the phone" is otherwise unanswerable.

## Files

```
android/
  app/src/main/java/org/trailink/gpsbridge/
    BridgeService.kt     the bridge: BLE, GPS, 5s send timer, recorder,
                         counters, notification. Survives a locked screen.
    MainActivity.kt      the one window. Binds, renders a snapshot, four
                         buttons. Holds no state of its own.
    BleLink.kt           scan, connect, reconnect, write; UUIDs and name
    SendPolicy.kt        when a position is worth a BLE write. No Android
                         types, so it is unit-tested.
    FixGate.kt           which fix is trusted as the position at all,
                         upstream of the send policy. No Android types.
    PositionPacket.kt    the 19-byte encoder and heading sectors
    SessionLogger.kt     JSON Lines recording file, fsynced per line
  app/src/test/java/...  PositionPacketTest.kt + SendPolicyTest.kt +
                         FixGateTest.kt, 30 tests, pure JVM
```

## Build

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=$HOME/Android/Sdk \
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Unit tests (packet layout — worth running after any protocol change):

```bash
./gradlew testDebugUnitTest
```

Toolchain used: JDK 17, Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.21,
build-tools 36.0.0, `androidx.core:core-ktx:1.17.0` (the only dependency —
`FileProvider` for the share button; the UI is plain framework views).

`local.properties` is gitignored and holds `sdk.dir`.

## Log format

One file per session at
`/sdcard/Android/data/org.trailink.gpsbridge/files/trailink-gps-<YYYYMMDD-HHmmss>.jsonl`.

First line is a header:

```json
{"type":"header","format":2,"app_version":"0.2.0-g3f38ecd","packet_encoding":"hex","packet_bytes":19,"target_device_name":"XteinkX4Map","send_min_interval_ms":5000,"send_keepalive_interval_ms":3600000,"send_move_threshold_m":25,"send_heading_min_move_m":10, ...}
```

`format` is 2 since the packet stream stopped being a fixed 5 s cadence. A reader
that assumes v1's fixed interval would read a v2 file's gaps as signal loss.

Then one JSON object per line, `type` picks the stream:

- `fix` — a raw `Location` update, unfiltered and unsnapped: lat, lon,
  `alt_m`, `bearing_deg`, `speed_mps`, `accuracy_m`, provider, `is_mock`,
  `fix_time_utc_ms` (the provider's own clock) and
  `elapsed_realtime_nanos`.
- `packet` — the exact bytes written, hex, plus `ok`, `seq`, `heading`, the
  decoded lat/lon/accuracy/speed, `error` on failure, and the send policy's own
  reasoning: `reason`, `moved_m`, `since_last_ms`.
- `event` — `scan_start`, `found`, `connected`, `ready`, `disconnected`,
  `bluetooth_off`, `permissions_denied`, `app_background`, and friends. A gap
  in the recording is explained by these.

Every line carries `t_utc_ms`, `t_utc_s` and `tz_offset_min` — UTC plus offset,
same clock rule as the wire packet.

The file is flushed **and fsynced after every line**, so an app killed
mid-session leaves a valid partial file. Every line is a complete JSON object
or absent; there is no half line.

Read it back with anything:

```bash
jq -c 'select(.type=="packet")' trailink-gps-*.jsonl | head
```

## Calls made where the brief was silent

- **One file per recording, both streams, tagged by `type`** — the brief demands
  "one file per session" with a single header line, so the two streams share
  the file and stay separable by `type`. "Session" now means one Start-to-Stop
  recording, not the app's lifetime.
- **Recording is separate from sending.** The bridge connects and sends whenever
  it is running; recording only decides whether that gets written down. Stopping
  a recording does not stop navigation.
- **`Share log` offers the current recording, or the last one if stopped.** No
  file browser — the older files are in the same folder for anyone who wants
  them.
- **`START_NOT_STICKY`.** A service resurrected after a low-memory kill would
  come back with a null intent, no permissions re-checked and nobody watching.
  A ride that ends in a kill should end visibly.
- **Packet bytes as lowercase hex**, noted in the header as
  `"packet_encoding":"hex"`.
- **Logging runs for the whole app lifetime**, no start/stop toggle — the brief
  allows either, and one less piece of state is one less thing to get wrong.
- **The connected address lands on the `connected` event line**, not in the
  header: the header is written before any device is found, and the file is
  append-only.
- **Share mime type is `text/plain`** so every mail/chat/cloud target accepts
  it. The file keeps its `.jsonl` name.
- **Both GPS and network providers are registered.** Every fix from either is
  logged raw — this is what lets a mock-location app drive it indoors — but
  which one becomes the trusted position goes through `FixGate` first; see
  below.
- **Heading**: `Location.getBearing()` when moving faster than 0.5 m/s,
  otherwise the bearing between consecutive fixes at least 3 m apart, otherwise
  the last known bearing. Never snaps back to North just because the phone
  stopped.
- **Writes are acknowledged** (`WRITE_TYPE_DEFAULT`), because the firmware
  characteristic declares plain `WRITE`, not `WRITE_NR`. A write with no
  callback within 3 s counts as failed and is logged as such.
- **One write in flight at a time.** A send that lands while the previous write
  is unacknowledged is counted and logged as failed, not queued — at a 5 s
  cadence this only happens when the link is already sick, and that is
  information.
- **The service outlives the window.** Closing the activity or locking the phone
  does not stop the bridge. The only things that stop it are the **Stop** button
  and the **Stop** notification action.

## Verification status

Laptop: clean build, zero Kotlin warnings, 9/9 packet-encoder unit tests.

Emulator: verified end to end against a throwaway test peripheral on a second
Android 16 emulator, both launched with `-packet-streamer-endpoint default` so
they share the emulator's virtual Bluetooth. Ten 19-byte writes 5 s apart,
`seq` 1..10, every packet decoded correctly on the peripheral side, log file
valid after a hard `am force-stop`, share sheet opens with the right file.

The test peripheral is not in this repo on purpose — it is 130 lines,
re-writable in minutes, and keeping a fake X4 in the tree invites testing
against it instead of the device.

Send policy, on paired emulators against the sim peripheral: stationary 12 s →
no packet; moved 10 m → still no packet, UI showing `10/25 m`; moved 33 m →
one packet, `reason: moved`, `since_last_ms: 60460` — a full minute of silence
where the old build would have written twelve packets; jumped 300 m → one
packet. 40 raw fixes recorded across the same window. Stop/Start recording
closes the file with a `recording_stop` line and opens a new one.

**Real hardware, Galaxy S24 (SM-S928B, Android 16) against the real X4:**

- connected to `XteinkX4Map` at `14:63:93:F4:8A:36` with no pairing, and did it
  **while the phone was locked and the screen off**, which is what the
  `ScanFilter` fix bought
- sent one packet every 5 s with the phone in Doze: counter 21 → 25 → 29 across
  40 s, zero failures
- a recording taken with the screen off held 103 raw fixes at ~1 Hz and six
  acknowledged packets, all `ok`
- the X4 terminating the link (`gatt status 19`) is logged as an event, which is
  how the scan-while-locked bug was found in the first place
- with the adaptive policy on the phone standing still: **two minutes, one
  packet.** The old build would have sent twenty-four.

Still open: a long ride, and whether the X4's own drop after ~30 s is a firmware
issue. See the end of `../docs/android-install.md`.

See `../docs/android-install.md` for the phone-side install manual.
