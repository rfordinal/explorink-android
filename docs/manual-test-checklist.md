# Manual test checklist

A build is not tested by compiling clean. This list is what gets run by hand
on a real phone, against a real device, after any change that could touch it.
Not every change needs every section — run the sections the change actually
touches, plus 1 and 2 always.

Record a run as: date, phone model, Android version **and SDK level**, app
version (`versionName-g<sha>`), paired device model **and firmware build**,
which sections run, pass/fail per section, and the measurements section 3.7
asks for. Keep that record in the session's own notes or `docs/PROGRESS.md` in
the parent repo, not in this file. This file is the steps, not the log.

A pass/fail alone cannot see a link getting slower or flakier, which is the
way this app degrades in practice. That is what the numbers are for.

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

This section is the one that decays quietly. A link that takes twice as long
to come up, or drops twice as often, still passes a yes/no check. **So every
run writes down the numbers in 3.7, not just a tick.** A run with no numbers
cannot be compared to the next one.

Run the whole section after any change to `BleLink`, `CompanionWake`,
`BridgeService`'s BLE half, or the Home device plate.

### 3.1 Pairing from scratch

1. Forget the device first (device plate, "Forget device"), so this is a real
   first pairing and not a re-bind.
2. Open the reader's map screen so it advertises. Tap the device plate, pick
   the reader in the system companion picker.
3. **The toast and the plate must agree.** The toast says `paired with
   <MAC>`, and the plate under it says the same. A toast reading `pairing did
   not complete` over a plate that shows the pairing is the T-162 defect
   coming back: the result was read from the association list instead of from
   the picker's own result Intent, and the list lags on some phones.
4. Confirm the MAC in the toast is the reader's, not another board in the
   room.
5. Only a known device name connects (`BleLink.KNOWN_DEVICE_NAMES`), never an
   arbitrary nearby peripheral.

**Phone matters here.** The association list lag was measured on a Galaxy S10
(SM-G973F, Android 12, SDK 31). A pass on a newer phone does not cover it --
API 33+ takes a different branch (`EXTRA_ASSOCIATION`) from 31/32
(`EXTRA_DEVICE`). Run 3.1 on an SDK 31/32 phone **and** an SDK 33+ phone, and
say which was which.

### 3.2 The reader is named correctly, link up and link down

1. With the link up, open the device plate dialog. The title is the name the
   reader actually advertises (`XteinkX4ProMap` for an X4 Pro,
   `LilyGoT5S3Map` for a T5 S3 Pro, and so on).
2. Put the reader to sleep or close its map screen so the link drops. Open the
   dialog again.
3. **The title must not change model.** It stays the remembered name, or says
   `Your reader`. A dialog headed `XteinkX4Map` over a line reading `paired
   with <an X4 Pro MAC>` is T-163 coming back -- a model constant used as a
   fallback.
4. Do this with two different reader models if two are on the desk. Naming the
   wrong one is only visible when the two names differ.

### 3.3 Link down: resting, stopped, blocked

Three different states, three different plates. They are easy to collapse into
one another by accident.

1. **Resting.** Reader paired, asleep or map screen closed, bridge running.
   Plate reads `Device resting` with `paired ..<last 5 of MAC>`.
2. **Stopped.** Leave the app with no reader for over five minutes
   (`BridgeService.IDLE_STOP_MS`), with no recording running, then return to
   Home. Plate reads `Bridge stopped` with `tap to start looking again` --
   **not** `Connect a device`, which is what a reader that was never paired
   looks like. That confusion cost an hour of chasing a reader that was
   advertising fine at -57 dBm (T-165).
3. Tap that plate. The bridge starts, with no dialog in between, and the plate
   moves on to resting or connected.
4. **Blocked.** Turn Bluetooth off. Plate reads the blocker and `tap to fix`,
   and the tap goes to the fix, not to the pairing picker.

### 3.4 Reconnect

1. Power the reader off, or carry it out of range. App shows the link lost,
   does not crash, does not spin.
2. Bring it back. The app reconnects with no relaunch.
3. Repeat three times in a row. A reconnect path that works once and then
   stops is the shape of a leaked state, and one try cannot see it.

### 3.5 A peer that vanishes without dropping the link

The case the stacks hide: a peripheral whose **process** dies keeps the ACL
open, so nothing at the link layer reports anything. Needs a peripheral whose
process can be killed, which a real reader is not -- use a second phone
running the simulator as the peripheral (the original measurement, 2026-08-23,
was made this way). Powering a real reader off is **a different test**, the one
in 3.4, and it does not cover this.

1. Connect the app to the simulator peripheral.
2. Leave both parked, so the app sends nothing: the keepalive is an hour
   (`SendPolicy.KEEPALIVE_INTERVAL_MS`), and the point of the test is that
   there is no traffic to fail on.
3. `adb shell am force-stop <the peripheral app>` on the other phone.
4. **Within about two minutes the app must leave the connected state** and go
   back to scanning. Before T-540 it sat on `connected to <name>` for at least
   four minutes with nothing in its own log.
5. Check the plate dialog's event log: expect `link_dead` naming the probe,
   then the ordinary recovery lines (`reconnect_direct` / `scan_start`).
6. On a reader whose firmware has no Device Information Service, expect one
   `liveness_unavailable` event at connect time instead, and no probe. That is
   a stated limit, not a failure -- but say which firmware the run used.

### 3.6 Swapping to a second reader

The app holds **exactly one** reader. Pairing a second one replaces the first
(`CompanionWake.keepOnly`), so this is a switch, not a second pairing.

1. With reader A paired and working, pick up reader B and open its map screen
   so it advertises.
2. Tap the device plate, then `Pair a different reader`. The system picker
   lists both readers.
3. Pick B. **No Forget anywhere in this.** The toast names B's address, and
   the plate goes to B.
4. After switching, Home names **B** -- the plate, the plate dialog's title
   and the `paired ..<suffix>` line all agree, and none of them still says A.
5. **A's association is gone, not merely unused.**
   `adb shell dumpsys companiondevice | grep explorink` must list one address
   and it must be B's. Two addresses means the replacement did not happen,
   and which reader the phone follows is then decided by the system's list
   order, which no rider can see.
6. Switch back to A the same way and confirm it is the same three taps.

### 3.7 Numbers to write down

Measured with the reader's map screen open and the phone next to it, unless a
line says otherwise. Take each one three times and record the median.

| What | How | Watch for |
|---|---|---|
| Time to first connect | from tapping the plate to the plate reading connected | creeping past the ~20 s fast scan window |
| Time to reconnect | from the reader waking to connected, after 3.4 | a direct reconnect should beat a cold scan |
| RSSI at one metre | plate's `connected - N dBm` | a big change means the advertisement or the scan changed |
| Dropped links in 10 min parked | plate dialog event log, count `disconnected` | should be zero; anything else is new |
| `sent ok / failed` after a 10 min walk | instrument row dialog | failures were rare before; a ratio that moved is a regression |
| Liveness probes in 10 min parked | `adb logcat -s BleLink` and count `liveness probe` | roughly one a minute while nothing else talks, and none at all while a transfer runs. Deliberately not in the plate's event log -- one line a minute would bury it |

Write these into the session's notes or `docs/PROGRESS.md` in the parent repo,
next to the previous run's, so the comparison is possible at all.

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
4. **The failure has to be clearable without a restart** (T-164). Still in
   that offline state, Home's map-squares row reads `could not check - no
   connection`. Turn the phone's network back on with the reader still
   connected: the row must stop saying that on its own, within seconds of the
   network validating. Before this fix it kept the failure until the app was
   force-stopped.
5. Same state, but tap the map-squares row instead of waiting: the dialog
   offers `Check the squares again`, and it runs. The option is absent when
   the reader is not connected, because there would be nothing to ask.
6. Confirm the row never shows the checker's own wording. `freshness unknown
   (index unreachable, 0 stale found anyway)` is a developer sentence on the
   first screen a rider sees.

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
