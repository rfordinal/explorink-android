# TrailInk GPS — Android companion app

Sends the phone's GPS position to the Xteink X4 over BLE and records the ride
for replay. One window. Built to `docs/android-app-brief.md`.

Package `org.trailink.gpsbridge`, app label **TrailInk GPS**.
minSdk 31, targetSdk 36, compileSdk 36. Debug-signed only.

## What it does

1. Scans for `XteinkX4Map`, connects, no pairing.
2. Every 5 seconds, while connected and a fix exists, writes 19 bytes to
   characteristic `5a1e6d00-73a4-4f1e-9b8f-2c6e1a8f0002`.
3. Logs every raw GPS fix, every packet written (including failures) and every
   link event to one JSON Lines file per session.

It does not touch the command characteristic (`...0003`). No route logic, no
background service, no cloud.

## Files

```
android/
  app/src/main/java/org/trailink/gpsbridge/
    MainActivity.kt      the one window, permissions, 5s send timer
    BleLink.kt           scan, connect, write; UUIDs and device name
    PositionPacket.kt    the 19-byte encoder and heading sectors
    SessionLogger.kt     JSON Lines session file, fsynced per line
  app/src/test/java/...  PositionPacketTest.kt, 9 tests, pure JVM
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
{"type":"header","format":1,"app_version":"0.1.0","packet_encoding":"hex","packet_bytes":19,"target_device_name":"XteinkX4Map", ...}
```

Then one JSON object per line, `type` picks the stream:

- `fix` — a raw `Location` update, unfiltered and unsnapped: lat, lon,
  `alt_m`, `bearing_deg`, `speed_mps`, `accuracy_m`, provider, `is_mock`,
  `fix_time_utc_ms` (the provider's own clock) and
  `elapsed_realtime_nanos`.
- `packet` — the exact bytes written, hex, plus `ok`, `seq`, `heading`, the
  decoded lat/lon/accuracy/speed, and `error` on failure.
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

- **One session file, both streams, tagged by `type`** — the brief demands
  "one file per session" with a single header line, so the two streams share
  the file and stay separable by `type`.
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
  logged raw; the newest one is what gets sent. This is what lets a
  mock-location app drive it indoors.
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
- **Foreground only.** Sending and location updates stop in `onStop` and resume
  in `onStart`. The BLE link is left connected across a short background trip
  so returning to the app does not force a rescan.

## Verification status

Laptop: clean build, zero Kotlin warnings, 9/9 packet-encoder unit tests.

Emulator: verified end to end against a throwaway test peripheral on a second
Android 16 emulator, both launched with `-packet-streamer-endpoint default` so
they share the emulator's virtual Bluetooth. Ten 19-byte writes 5 s apart,
`seq` 1..10, every packet decoded correctly on the peripheral side, log file
valid after a hard `am force-stop`, share sheet opens with the right file.

Not verified: the real X4. See the end of `../docs/android-install.md`.

The test peripheral is not in this repo on purpose — it is 130 lines,
re-writable in minutes, and keeping a fake X4 in the tree invites testing
against it instead of the device.

See `../docs/android-install.md` for the phone-side install manual.
