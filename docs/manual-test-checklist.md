# Manual test checklist

A build is not tested by compiling clean. This list is what gets run by hand
on a real phone, against a real device, after any change that could touch it.
Not every change needs every section — run the sections the change actually
touches, plus 1 and 2 always.

Record a run as: date, phone model, Android version, app version
(`versionName-g<sha>`), paired device model, which sections run, pass/fail per
section. Keep that record in the session's own notes or `docs/PROGRESS.md` in
the parent repo, not in this file. This file is the steps, not the log.

## 1. Fresh install and launch

1. Install the APK (`adb install -r ...`).
2. Launch the app.
3. Home screen loads: no crash, no blank screen.
4. Check installed version: `adb shell dumpsys package org.explorink.gpsbridge
   | grep -E "versionName|versionCode"` matches what was built.

## 2. Permissions

1. On a device with no permissions granted yet: open the app, confirm the
   disclosure screen appears **before** the first system permission prompt
   (precise location, then background location).
2. Deny a permission: confirm the app says what is missing, does not crash,
   and the affected feature is visibly gated off (no silent failure).
3. Grant all permissions: confirm the gated feature comes back without a
   reinstall.
4. Open the Settings route to the disclosure text directly (not only the
   first-run path) and confirm it shows there too.
5. On Android 13+: confirm the Bluetooth runtime permission is asked
   separately from location.

## 3. Pairing and BLE link

1. With the paired device powered on and its map screen open, confirm this
   app connects within the fast scan window (~20 s).
2. Home screen shows "Device resting" / connected state correctly, and the
   paired device's BLE suffix matches the actual device.
3. Power the device off, or move it out of range: app shows link lost,
   does not crash, and does not spin forever.
4. Bring it back: app reconnects without needing a relaunch.
5. If pairing from scratch: confirm only a known device name connects
   (`BleLink.KNOWN_DEVICE_NAMES`), not an arbitrary nearby BLE peripheral.

## 4. Position send

1. Walk or drive with the device connected. Confirm position updates land on
   the device's map (dot moves).
2. Check the recording JSONL (if recording is on) for `reason` values during
   the session: expect `first` once per link, `moved` while actually moving,
   `keepalive` if left parked over an hour, `heading_lost` after stopping a
   while.
3. Stand still: heading arrow should go stale and eventually disappear
   (`STALE_HEADING_MS`), not keep pointing a stale direction forever.
4. Walking pace vs. driving pace: confirm the floor changes the packet dot
   does not need a stopwatch, just "it keeps up and does not flood".

## 5. Map tile sync (missing tiles)

1. On the device, delete or never-build a tile under the current position.
2. Start "Sync map tiles" from the device's home menu.
3. Confirm the app answers, transfers, and the device's progress screen
   counts the tile as done.
4. Force a failure case (airplane mode on the phone mid-sync, or a tile that
   does not exist on the CDN): confirm the device gets a `skip` with a reason
   it can show, not a stall.
5. Cancel mid-transfer (device Back button): confirm the app stops cleanly.

## 6. Tile freshness check

1. With a tile already on the device that has since been rebuilt
   server-side, open the map screen (autosync on) or trigger a freshness
   check.
2. Confirm the app answers `stale` for that tile and `current` for an
   unchanged one.
3. With the phone offline: confirm the device gets `checked unknown`, not a
   false `stale` or `current`.

## 7. Pins

1. Add a pin from decimal degrees, from a Google Maps DMS string, from a
   `geo:` link, and from a share-into-app (Maps share sheet).
2. Confirm the pin lands on the device with a real UTC timestamp, not
   "time unknown".
3. Add, edit, and delete each pin type the device supports (base, parking,
   destination, meet, camp, favorite, `#1`-`#5`).
4. Confirm the Pins screen does not show anything that leaks the live phone
   position on screen (see privacy note in the parent repo's `CLAUDE.md`,
   "After every device screenshot").

## 8. Map areas

1. Queue a new map area (bounding box).
2. Confirm it shows up correctly and does not leak unintended coordinates on
   screen if screenshotted (same privacy note as Pins).

## 9. Recording

1. Start a recording, ride or walk a bit, stop it.
2. Confirm the JSONL file exists with raw fixes, packets (including any
   failures), and link events.
3. Confirm the foreground service keeps running with the screen locked and
   the app swiped away, for as long as the recording is on.

## 10. Battery behavior

1. Disconnect the device and leave it disconnected with no recording:
   confirm GPS, network location, wake lock, and the send timer all go off
   within the service's own timeout, and the service stops itself after
   5 minutes with no link.
2. Reopen the device's map screen: confirm the companion association wakes
   the app again with no tap needed.
3. Trigger a scan failure deliberately (Bluetooth off mid-scan) and turn it
   back on: confirm the app auto-retries instead of sticking at "Link
   failed".

## 11. Cross-device check

Run section 1 and whichever of 3-6 the change touches on more than one phone
model and Android version when more than one is on the desk — see the parent
repo's `CLAUDE.md`, "Pick the board whose data exercises the new code". A
pass on one phone is a pass for that phone, not for the app.
