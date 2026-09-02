# ExplorInk GPS — Android companion app

This repo is a standalone snapshot of the Android app's source, pulled out of
a private monorepo that also holds the firmware and the map-building
tooling. It carries snapshot commits, not the development history. For the
device side —
firmware, the on-device map renderer, the BLE protocol from the device's
point of view — see [rfordinal/explorink](https://github.com/rfordinal/explorink),
a fork of [CrossPoint Reader](https://github.com/crosspoint-reader/crosspoint-reader)
for the Xteink X3/X4.

Project site, with real device screenshots and the development log:
**[explorink.com](https://explorink.com/)** — the app's place in the whole
system is on [how it works](https://explorink.com/how-it-works/), and the BLE
bridge itself on [the BLE GPS page](https://explorink.com/development/ble-gps/).

Package `org.explorink.gpsbridge`, app label **ExplorInk GPS**.
minSdk 31, targetSdk 36, compileSdk 36. Debug-signed only, no license file yet.

## What it does

ExplorInk turns a hacked Xteink X4 e-ink reader into a motorcycle/trail map
device. This app runs on the rider's phone and is the bridge between the
phone's GPS and that device:

0. Starts itself when the rider opens the map screen on the paired device.
   The OS watches for that device over a companion association and wakes this
   app, with the app swiped away and nothing tapped.
1. Scans for a BLE device advertising one of five known names (an X4, X3,
   X4 Pro or LilyGo T5S3, or a generic fallback -- `BleLink.KNOWN_DEVICE_NAMES`),
   connects, no pairing.
2. Sends the phone's GPS position to the device over BLE whenever the
   position has actually moved meaningfully — a distance-driven send policy,
   not a fixed interval. See "Send policy" below.
3. Records every raw GPS fix, every packet written (including failures), and
   every link event to a JSON Lines file per recording session, for later
   replay or analysis.
4. Answers the device when it asks for map tiles it is missing over BLE (a
   tile-transfer protocol), and checks whether tiles the device already has
   have gone stale against a CDN-published freshness index.
5. Manages the pins saved on the device — base, parking, destination, meet,
   camp, favorite, `#1`-`#5`. The device can only save a pin where the rider is
   standing: it has no keyboard and no coordinate entry. So this app is where a
   place chosen from text gets onto the device's card — decimal degrees,
   degrees-minutes-seconds (what Google Maps shows when a place's coordinates
   are tapped), a `geo:` link, a full Maps URL, or a share from a maps app. It
   also fills the one field the device cannot: the device has no clock, so a pin
   it saves alone carries "time unknown", and a pin saved from here carries a
   real UTC second.

All of it runs in a foreground service, so it keeps going with the screen
locked and the app swiped away — but only while there is something to do; see
"Battery" below. No route logic, no cloud dependency beyond fetching map tiles
from a CDN.

## Send policy

`SendPolicy.kt`. Distance-driven, not clock-driven — which makes it
speed-adaptive **only below ~26 km/h**. Above that the time floor alone
always covers more ground than the move threshold requires, so above road
speed the policy behaves like a plain fixed-interval timer.

| | |
|---|---|
| never faster than | **7 s** — the floor |
| at walking pace (≤2.2 m/s avg since last send) | **30 s** floor instead |
| never slower than | **60 min** — the keep-alive, so the device knows the phone is alive |
| in between, send when | the position moved **50 m** from the last sent one (no upper bound on the fix's own accuracy yet, so a very bad fix can still clear an inflated threshold) |
| move threshold, once the device has said its screen diagonal (`DIAG_M`) | `diagonal_m * 0.008` instead of the flat 50 m — one constant cannot fit every zoom rung on the device, and the ratio itself is a starting point, not a measured one |
| also send when | the 16-sector heading changed **and** at least 10 m was covered |
| also send when | parked, the last packet actually sent carried a fix worse than 20 m accuracy, and the phone has since settled on 3 fixes in a row at ≤10 m (`reason: correction`) |
| always send once | on every new link, `reason: first` — the state above is per-link and resets when the link comes up. It waits for a fix accepted on that link, so it never sends the previous ride's position. Before 2026-08-17 the state survived the disconnect, so a parked rider opening the map screen got no packet at all and the device kept showing the fix off its own SD card until the rider covered 50 m. |

What that works out to: 100+ km/h on a highway sends one packet every 7 s
(the floor; the 50 m threshold never binds at that speed); 25.7 km/h is the
break-even point; 5 km/h hiking sends about one every 18 s; parked at lunch,
one an hour, or none at all if nothing needed correcting. The heading rule
needs real movement behind it because a stationary phone's bearing wanders
across all 16 sectors on GPS noise alone.

`DIAG_M <metres>` arrives unprompted on the same command channel as the
tile-fetch conversation below (`MissingList.parseDiagonalM`) — the ground
distance the device's current screen diagonal represents, sent once per
zoom-rung change and once per reconnect. Not the rung index and not a
screen width: the diagonal is the one number that stays meaningful across
different device models regardless of resolution or portrait-vs-landscape.

Location updates stay at 1 Hz for as long as the app is asking for them at
all — the policy gates the BLE write, not the fix rate, so the raw-fix stream
in the recording stays dense and a later replay can re-derive packets under a
different cadence from the raw fixes. Whether it asks at all is a separate
question, answered under "Battery" below.

Every packet line in the recording carries why it went out (`reason`:
`first` / `moved` / `heading` / `keepalive` / `correction`), how far the phone
had moved (`moved_m`), and how long it had been quiet (`since_last_ms`).
`SendPolicy` itself has no Android types in it — it is the part with the
reasoning, so it is the part with unit tests on it.

## Fix gate

`FixGate.kt`. Upstream of the send policy and a separate concern from it:
this decides which fix is trusted as the phone's position at all, before
`SendPolicy` ever asks whether that position is worth a BLE write.

A phone in the field asks for GPS and network fixes together. GPS holds a
tight, steady accuracy; a network fix that races in beside it can land tens
to hundreds of metres away, at whatever accuracy it claims for itself. Taking
every fix as-is, newest wins, makes a stray network fix look like the phone
teleported.

The rule: a fix that could not have got here from the last trusted one at a
plausible speed (55 m/s, well past motorcycle pace, tempered by the
*previous* fix's own accuracy — never the new fix's self-reported one) is
held back for one more location update rather than used immediately. If the
next fix lands nearer the held-back position than the old one, both are
accepted as real movement. If it snaps back near the old position, the
held-back fix was noise and is dropped. A hold with no next fix within 3 s
times out and is dropped too.

That alone was not enough: a network fix's error radius is wide enough that
a following fix can legitimately "confirm" a jump that never happened. So a
network fix is now ignored outright whenever a GPS fix has answered in the
last 5 s (`FixGate.GPS_LIVE_WINDOW_MS`) — only once GPS has been quiet longer
than that does a network fix reach the hold-and-confirm rule at all, since at
that point it is the only signal left.

Every fix is still logged raw regardless of this decision — the gate decides
what the live app trusts, not what the recording keeps.

## Fetching missing tiles

The rider starts this on the device, never the phone — it is a transfer of
kilobytes over a link the rider is relying on for position, so the rider
says when. Two places on the device start it and ask for different amounts:
the home menu's "Sync map tiles" (the whole missing-tile list, up to 200
entries, done at home before a ride), and the map screen's autosync (if the
rider turned it on), which asks only for what is under the current viewport.

The conversation, summarized:

1. Device indicates `NEED_TILES <count> fmt <version> [view]` on the command
   characteristic.
2. App reads the list: `tiles` for a viewport ask (at most 32 entries, no
   paging), otherwise pages `missing` / `missing <offset>` until
   `missing_next=done` (up to 200 entries).
3. For each tile, in the order the device gave them, the app reads the bytes
   from its `TileSource` (the CDN) and pushes them over the transfer
   characteristic: begin, wait for `RDY`, chunks, `OK`.
4. A tile the app does not have, or one built to a format version this
   device cannot read, becomes `skip <z> <col> <row> <reason>` so the
   device's progress screen counts it as failed instead of waiting forever.
5. `FETCH_CANCEL` (the rider pressed Back) stops everything in flight.

The tile URL carries the device's own declared format version
(`https://tiles.explorink.com/v<format>/base/<z>/<col>/<row>.tib`), not a
version hardcoded in the app — the CDN publishes one path per `.tib` format
version, and the format-version check happens before a single byte goes out.
A mismatch is a distinct `skip ... fmt<found>` rather than a plain miss,
because "no tile here" and "wrong tile here" want different fixes.

**Nothing is cached on the phone.** A tile lives on the X4 or on the CDN;
the phone holds one in memory for the length of its transfer and drops it
the moment it lands, then a static file fetch — no API, a miss is a 404.

**Verified end to end on real hardware**: a tile under the rider's own
position was deleted off the X4's card, the map reopened, and the app
fetched and delivered it — a 317 KB tile landed at roughly 9 kB/s over BLE,
and the device redrew itself with it. See "One GATT operation at a time"
below for why that ceiling is what it is.

## Checking freshness: is what the device already has still current?

A separate question from the fetch above. `missing` and `tiles` are about
tiles the device does not have. This is about tiles it does have: they open
and draw fine, but the map may have been rebuilt and republished since —
without this, an already-synced device never finds out its tile is stale.

```
device -> phone   CHECK_TILES <count>
phone  -> device  have
device -> phone   INFO have_total=<n>
                  INFO have_<z>_<col>_<row>=<content_id hex>
                  OK
phone  -> device  stale <z> <col> <row>          one per differing tile
phone  -> device  checked <n> | checked unknown
```

`content_id` is a CRC32 over the six per-layer CRC32s a tile already
carries, so the device computes it with no seek and no read, and it is never
a timestamp — a build-rule change with no new source data still needs to be
detected.

The app reads a CDN-published freshness index by byte range: a dense array
of 16-byte slots, one file per z7 block, with the slot offset computed
arithmetically from `(z, col, row)` (`TileIndex.kt`). It requests **one
range per zoom plane, not one per tile** — a whole viewport sits between one
lowest and one highest offset, a few kB in one request — because a round
trip per tile would not finish inside the device's patience on mobile data.

Three answers, kept deliberately distinct: **stale** (index has this ground
and the content ID differs, certain), **current** (index has it and
agrees, also certain), and **`checked unknown`** (no signal, a server
error, or a partial index read — nothing is claimed either way). Answering
"stale" on an unknown would push the rider's whole viewport over a slow link
to replace tiles that were already right; answering "current" would bury a
real staleness bug. On unknown the app backs off, doubling from 1 minute to
30, so an offline phone is not asked the same unanswerable question every
cooldown. A slot the index has nothing for is not stale — there is simply
nothing published for that ground, so there is nothing to fetch.

A stale tile is re-fetched with `?crc=<content_id>` appended to its URL —
the tile path does not change when a tile is rebuilt, and the CDN's edge
caches a path for seven days with no purge mechanism, so without the query
a re-fetch would get back the exact stale copy it is meant to replace. The
app verifies the arriving tile's content ID independently before writing it,
retries once past the cache, then gives up with `skip` rather than push a
tile it cannot vouch for.

**Run against real hardware.** The device's tile-sync screen asked, this app
answered from the CDN index, and a stale tile came down and afterwards matched
the published map. That run also found a bug no unit test could: the device
asked whether the phone was there at the instant the screen opened, and the
phone arrives about two seconds later, so the question was always answered no
and the check never ran. The logic was right; when it ran was not.

## BLE link details that matter for this code

- **One GATT operation at a time.** Android runs exactly one GATT operation
  per connection and reports it on a callback; a second issued before the
  first completes is refused on the spot. `GattOpQueue` holds every operation
  — position writes, console lines, transfer chunks, CCCD writes, the MTU
  exchange — and pumps the next from the completion callback. A transfer does
  not starve the position channel: the fetcher sends its next chunk only from
  the previous chunk's callback, so a position write waits at most one chunk.
- **A timed-out operation does not free the queue.** Android holds its busy
  flag until the timed-out operation's *own* callback arrives, so failing an
  operation on a timeout and pumping the next one just feeds refusals into a
  stack that is still busy — and the fetcher turned each refusal into a
  `skip`, which the device remembers as a refused tile until it reboots. One
  slow SD write used to discard the rest of a fetch. The timed-out operation
  now stays in the slot as a tombstone: nothing is pumped until some callback
  arrives, which by construction is that operation's. A transfer frame gets
  10 s rather than 3, because its ATT response is SD-bound by design. If no
  callback arrives within 30 s total — the ATT transaction timeout — the link
  is treated as dead rather than slow.
- **Chunks use write-with-response**, which is load-bearing rather than
  politeness: the ATT response arrives only once the device has the bytes on
  its SD card, so driving the loop off that response means the sender
  physically cannot outrun the card.
- **The app requests a 517-byte MTU** after subscribing. On real hardware
  the link settles on 256, so a chunk carries 248 payload bytes — well above
  the default 23-byte MTU's 15 payload bytes.
- **The command and status channels are indications, not notifications.**
  The device sends multi-line replies faster than the connection interval
  drains them, and an unacknowledged notification can have its tail silently
  dropped by the controller. The device also refuses to start a transfer with
  nobody subscribed to the status channel, so both subscriptions happen at
  discovery, not at the first tile.
- **A high-priority connection interval is requested for the duration of a
  fetch and no longer** — position packets go out every few seconds and do
  not care about interval, but a tile transfer is throughput-bound by it, and
  a fast interval held open forever would spend battery for nothing once
  tiles have landed.
- All three extra characteristics (command, status, transfer) are optional at
  discovery — an older firmware build with only the position characteristic
  still gets position forwarding, the app's original job.

## Battery

Low battery use is a priority, not a nice-to-have — the rider carries one
phone for a whole ride and this app is not what it is for.

`BridgeService.updatePowerState()` is the only place that decides whether the
service spends power, and it asks one question: **is the device connected, or
is a recording running?** If neither, three things go down together:

| Cost | On when | Off when |
|---|---|---|
| GPS, 1 Hz | connected or recording | otherwise |
| network location, 30 s / 50 m | connected or recording | otherwise |
| `PARTIAL_WAKE_LOCK` | connected or recording | otherwise |
| the 1 Hz send timer | connected or recording | otherwise |

The network provider used to run at 1 Hz too. `FixGate` throws a network fix
away whenever GPS is live — the whole ride — so that rate bought a WiFi scan
every second whose result was discarded, and it is a suspect in BLE
coexistence besides, the two radios sharing one 2.4 GHz antenna. 30 s still
covers the indoor and mock-location fallback the provider is there for.

Nothing read a fix with the link down anyway — `trySend()` returns on
`!ble.isConnected` — so asking for GPS then bought a stale position and a flat
battery. Recording holds them up on its own, because a ride recorder with no
fixes is not a recorder and it is the one thing the rider asked for by hand.

Three more consequences of the same rule:

- **The service stops itself after 5 minutes with no link.** A running
  recording blocks it. Restarting costs the rider nothing: the companion
  association wakes the app again when the device opens its map screen.
- **Scanning drops from `SCAN_MODE_LOW_LATENCY` to `SCAN_MODE_LOW_POWER`
  after 20 s.** A continuous radio earns its keep for the seconds right after
  the rider opens the map; after that the scan is looking for a device that is
  probably off.
- **An automatic rescan starts at `SCAN_MODE_LOW_POWER` and never gets the
  fast window at all.** Only a scan somebody asked for gets it — the rider
  pressing Start or Retry, the companion wake, Bluetooth being switched back
  on. A dropped link, a connect timeout and a scan-failure retry are the app
  talking to itself with nobody watching, and a flapping link mid-ride would
  otherwise repeat a full-duty 20 s scan every time.
- **A failed scan retries itself** instead of ending at "Link failed" until
  the rider presses Retry. Android throttles a caller to five scan starts in
  30 s and answers the sixth with error code 6; the retry for that code waits
  35 s so it cannot land inside the window that tripped it, every other code
  waits 5 s and backs off to a 60 s cap. The screen still says the scan
  failed — the rider sees the truth, the retry just also happens.
- **The `location` foreground-service type is claimed when location is
  actually requested**, not at service start. The claim then matches the work,
  and it is also the claim Android 14+ refuses to a service woken from the
  background — which is how most sessions start.

The status notification re-posts only when its text actually changes. It used
to be rebuilt on every accepted fix, so about once a second, and from
Android 14 a foreground-service notification is dismissible — a re-post
undid the dismissal within a second of the rider swiping it away. With no link
it now reads `GPS off until connected` rather than the send counters, which
read as ongoing work when there is none.

Confirmed on a Galaxy S24 (Android 16): the wake path still works with the
gating in place, a swiped-away notification stays away, and the service stops
itself with the notification going with it. **Not measured:** there is no
battery figure for any of it — the checks say the behaviour is right, not what
it saved.

## Recording file format

One JSON Lines file per recording session, flushed and fsynced after every
line — an app killed mid-session leaves a valid partial file, and every line
is a complete JSON object or absent, never a half line.

First line is a header (format version, app version, target device name,
the send policy's four bounds, and more). Then one JSON object per line,
`type` picks the stream:

- `fix` — a raw `Location` update, unfiltered and unsnapped: lat, lon,
  altitude, bearing, speed, accuracy, provider, `is_mock`, the provider's own
  fix time, and elapsed-realtime nanos.
- `packet` — the exact bytes written (hex), `ok`, `seq`, heading, the
  decoded lat/lon/accuracy/speed, `error` on failure, and the send policy's
  own reasoning (`reason`, `moved_m`, `since_last_ms`).
- `event` — `scan_start`, `found`, `connected`, `ready`, `disconnected`,
  `bluetooth_off`, `permissions_denied`, `app_background`, and others. A gap
  in the recording is explained by these.

Every line carries UTC time plus timezone offset. Read it back with
anything, e.g.:

```bash
jq -c 'select(.type=="packet")' explorink-gps-*.jsonl | head
```

Files land at
`/sdcard/Android/data/org.explorink.gpsbridge/files/explorink-gps-<YYYYMMDD-HHmmss>.jsonl`.
Recording is a separate concern from sending: the bridge connects and sends
whenever the service is running, and Start/Stop only decides whether that
gets written down.

## Version

One source, `appVersion` in `app/build.gradle.kts`. Debug builds append the
commit they were built from, plus `-dirty` for an uncommitted tree:

```
ExplorInk GPS 0.2.0-g3f38ecd (2)
```

Shown small and grey at the bottom of the one window, and written into every
recording's header as `app_version` — on a sideloaded debug build, "which of
several builds is on the phone" is otherwise unanswerable.

## The two hosts this app talks to

**The tile CDN**, for everything map: tiles, the freshness index, the built-area
list (`CdnTileSource`, `CdnIndexSource`, `CdnMapsetSource`).

**A maps shortener**, once, and only when the rider has just shared a link. A
Google Maps share hands over `https://maps.app.goo.gl/<id>` with no coordinates
anywhere in the text, so the pre-trip area picker cannot finish without expanding
it. `MapsShortLink` sends one HEAD request, reads the `Location` header and does
not follow it, so no Google page is ever fetched. It never runs in the background
and never on a schedule.

Nothing else. There is no analytics, no crash reporter and no update check.

## Files

```
app/src/main/java/org/explorink/gpsbridge/
  BridgeService.kt     the bridge: BLE, GPS, send timer, recorder, counters,
                        notification. Survives a locked screen.
  MainActivity.kt      the status window. Binds, renders a snapshot, the
                        buttons. Holds no state of its own.
  PinsActivity.kt      the pins window: the device's pins with distances, a
                        coordinate field, the history pager. Holds no state
                        either — the device is authoritative and this asks it
                        again after every change.
  PinManager.kt        the pin console conversation as a state machine, one
                        command at a time. No BLE, no Android: unit-tested.
  PinList.kt           the pin wire: `pin list` / `pin set` / `pin del` /
                        `pin log` and the readers for their replies. Pure.
  PinCoordinates.kt    pasted text to a coordinate: a pair, DMS, a geo: link,
                        a Maps URL — and what it refuses to guess at. Pure.
  PinGeo.kt            distance to a pin and how it is written, ported from the
                        device's own so the two never disagree. Pure.
  PinKinds.kt          the pin catalogue keys, mirrored from the firmware.
  X4PresenceService.kt the companion hook the OS binds when the paired device
                        starts advertising. Starts the bridge, gets out of the way.
  CompanionWake.kt     the companion association: pairing, the remembered
                        address, forgetting it.
  BleLink.kt           scan, connect, reconnect, all four characteristics,
                        indications; UUIDs and name.
  GattOpQueue.kt       the one-operation-at-a-time GATT queue: timeouts, the
                        tombstone rule, the dead-link verdict. No Android BLE
                        calls of its own, so it is unit-tested.
  ScanRetryPolicy.kt   how long to wait before retrying a failed scan. No
                        Android types, unit-tested.
  SendPolicy.kt        when a position is worth a BLE write. No Android
                        types, unit-tested.
  FixGate.kt           which fix is trusted as the position at all, upstream
                        of the send policy. No Android types.
  HeadingTrend.kt       heading from recent fixes rather than one bearing.
  PositionPacket.kt    the 21-byte encoder and heading sectors.
  SessionLogger.kt     JSON Lines recording file, fsynced per line.
  TileFetcher.kt       the whole fetch conversation as a state machine. BLE
                        behind a Transport interface, time behind a Scheduler
                        interface, so it is unit-tested without either.
  TransferFrames.kt    the transfer wire format: frames, CRC32, chunk sizing,
                        path rules, status lines. Pure.
  FreshnessChecker.kt  the CHECK_TILES conversation as a state machine, and
                        the offline backoff. Same no-BLE-no-Android contract
                        as TileFetcher, unit-tested too.
  MissingList.kt       parses NEED_TILES, CHECK_TILES, DIAG_M, and the
                        `missing`, `tiles` and `have` replies.
  TileIndex.kt         the CDN freshness index: slot offsets, slot parsing,
                        grouping a viewport into one byte range per zoom plane.
  TileHeader.kt        magic + format version of a .tib, and content_id from
                        its layer directory.
  TileFormat.kt        transfer progress in words, a port of the device's own
                        sync screen so panel and phone cannot disagree.
  TileSource.kt        the CDN seam: tiles, with ?crc= and verification.
  IndexSource.kt       the CDN seam for index byte ranges.
  IndexScanner.kt      a TilePlan.Reading for a whole tile list, one byte-range
                        read per (block, zoom), sequenced and cancellable.
  MapsetSource.kt      the CDN's built-area list, which is what tells "nobody
                        has built this yet" from "this tile never exists".
  TileBox.kt           centre plus a box side in km -> the tiles that cover it,
                        coarse zoom first, centre tile first. Pure.
  TilePlan.kt          index slots -> counts, exact bytes and an ETA, and the
                        built-ground test behind the three outcomes. Pure.
  TileOutbox.kt        the pre-trip queue: the receipt ledger, the three
                        outcomes, the build backoff. Pure, no clock.
  OutboxStore.kt       that queue on disk, versioned and written atomically.
                        Format: docs/tile-outbox-format.md in the parent repo.
  Json.kt              JSON both ways. org.json is a stub under
                        unitTests.isReturnDefaultValues, so it cannot be used.
  MainThread.kt        hands async work (tile reads, HTTP) back to the main
                        thread, where BleLink and TileFetcher keep their
                        single-threaded state.
app/src/test/java/org/explorink/gpsbridge/
  PositionPacketTest, SendPolicyTest, FixGateTest, HeadingTrendTest,
  TransferFramesTest, MissingListTest, TileFetcherTest, TileIndexTest,
  TileHeaderTest, TileFormatTest, FreshnessCheckerTest, GattOpQueueTest,
  ScanRetryPolicyTest, BleLineAssemblerTest, BridgeForegroundTest,
  BridgeProgressThrottleTest, PinListTest, PinGeoTest, PinCoordinatesTest,
  PinManagerTest, TileBoxTest, TilePlanTest, TileOutboxTest, MapsetTest,
  IndexScannerTest, OutboxStoreTest, JsonTest — 348 tests, pure JVM, no
  emulator needed.
```

## Build

From the repo root:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=$HOME/Android/Sdk \
./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Unit tests (worth running after any protocol change):

```bash
./gradlew testDebugUnitTest
```

Toolchain, verified against `build.gradle.kts` / `settings.gradle.kts` /
`gradle/wrapper/gradle-wrapper.properties` in this repo: JDK 17, Gradle
8.14.3, Android Gradle Plugin 8.13.2, Kotlin 2.2.21, compileSdk/targetSdk 36.
`androidx.core:core-ktx:1.17.0` is the only dependency — `FileProvider` for
the share button; the UI is plain framework views, no AppCompat, no Compose.

`local.properties` is gitignored and holds `sdk.dir`.

## Verification status

Laptop: clean build, zero Kotlin warnings, 246 unit tests passing.

Emulator: verified end to end against a throwaway test BLE peripheral on a
second Android emulator, sharing the emulator's virtual Bluetooth — position
writes decoded correctly, log file valid after a hard force-stop, share
sheet opens with the right file. That test peripheral is not part of this
repo (it is small and easy to rewrite, and keeping a fake X4 around invites
testing against it instead of the real device).

Real hardware, a Galaxy S24 (Android 16) against a real X4:

- connected to `XteinkX4Map` with no pairing, including while the phone was
  locked and the screen off
- sent packets on the 5 s floor with the phone in Doze, zero failures over
  the test window
- a recording taken with the screen off held raw fixes at roughly 1 Hz
  alongside far fewer acknowledged packets under the adaptive send policy
- the device terminating the link is logged as an event
- the missing-tile fetch has been run end to end on real hardware: a
  deleted tile was re-fetched from the CDN and the device redrew with it
- the freshness check (`CHECK_TILES`) has been run end to end against the
  real device: the tile-sync screen asked, this app answered from the CDN
  index, and a stale tile was replaced with one whose content ID matched the
  published map
- the power gating: the wake path still works with it in place, a swiped-away
  notification stays away with the device disconnected, and the service stops
  itself after the idle window
- the freshness check from the **map** screen, 2026-08-13: two out-of-date
  tiles found, both fetched, and the device's content IDs then matched the
  published index. This path had been answering about a fraction of the screen
  until that day — the device's reply lines arrived one BLE indication each and
  most were lost, so a four-tile listing reached this app as one line and it
  reported "0 stale of 1" in good faith. The device end batches its reply now;
  this end refuses a listing whose lines do not add up to `have_total` and
  answers `checked unknown` instead of a verdict

**A reliability and power pass landed on 2026-08-13 and none of it has been
on hardware yet.** A full review of both ends of the BLE stack turned up a
set of silent-failure cases, and the fixes for the app half are all in this
snapshot: the foreground-service type mask no longer loses `location` on a
repeated start, Bluetooth being switched off now tears the link down instead
of leaving a stale one that the switch back on cannot revive, the GATT queue
survives a timeout without cascading refusals, `requestMtu` goes through that
queue instead of racing it, status lines and late reads and restarted
listings can no longer be credited to the wrong tile or the wrong fetch, a
failed scan retries itself, automatic rescans stay at low power, the next
tile is read from the CDN while the current one is still going out, and the
freshness listing runs at fast link parameters instead of on its own
deadline's edge.

What that means for the claims above: **they were verified before this pass
and the pass has not been re-run against a device.** The evidence for the new
work is a clean build and 186 unit tests, on a laptop — the state machines
were built to be testable exactly so this much can be checked without
hardware, but a laptop cannot tell you that a phone reconnects after airplane
mode or that a fetch got faster. Those measurements are planned and not done:
recovery after Bluetooth off/on, that the `location` type survives a Record
tap with the screen off, throughput before and after, and what the power
changes are worth.

**The pins screen (2026-08-19) has been on hardware.** Galaxy S24 against a
real X4 on its map screen, every device answer cross-checked over the device's
own USB serial console rather than trusting the app's screen: a six-pin listing
arrived whole (the fourteen-pin worst case is untested), a save from the coordinate field landed on the card with the
device assigning the pin id, the phone's clock reached the record (pins saved on
the device itself still read "time unknown", which is what that field is for), a
delete appended a `del` record without erasing anything, and the distances read
correctly — including `0 m` for a pin at the phone's own position.

That pass found a bug no unit test could have. `48 09 05.4N 17 07 47.1E` — a
DMS coordinate with the degree and minute symbols stripped, which is what a
share sheet or a keyboard leaves behind — matched the plain `lat, lon` pattern
on its first two numbers and offered to save `48.0000000, 9.0000000`. Germany,
700 km from the place asked for, with nothing in the parser noticing; the only
thing in the way was the confirmation dialog showing the parsed coordinate back.
The tests had only ever fed the form *with* the symbols, which the old guard
caught. Fixed by reading DMS properly instead of refusing it, and DMS-shaped
text no longer falls through to the plain pattern at all.

Still not on hardware for pins: the channel gate (a pin command issued during a
tile transfer, and a tile request arriving mid-pin-command — both are handled in
code and neither has been seen happen), the "device is not on its map screen"
answer, and what the screen's one-second redraw costs in battery.

**The per-link send reset (2026-08-17) has been on hardware.** It came off a
report from the device: a parked rider opening the map screen saw the map keep
the position it had restored from its own SD card, and only riding corrected
it. The send state was not per-link, so nothing was sent at all on a
reconnect. Fixed on this side, plus a narrower version of the same bug on the
device side. Confirmed the same day against a real X4 with the phone standing
still: a packet arrived 56 ms after the device drew its entry frame and the
device re-anchored the map onto it, tens of metres off the position it had
restored. Still unmeasured: how long this takes from a cold GPS start (the
first packet of a link waits for a fix accepted on that link, and the test run
had GPS already warm).

Still open: behavior over a long ride, whether an occasional link drop seen
in early testing is a firmware issue or an app issue, and what any of the
battery work above is actually worth in milliamp-hours — none of it has been
put against a power profiler.

**2026-08-17, separate from the pass above.** `SendPolicy`'s walking-pace
floor, bad-fix correction and the `DIAG_M`-derived move threshold (see "Send
policy") landed with 198 unit tests total, all passing. Flashed to a real X4
and installed on a real phone the same day — boot, BLE connection, and a
zoom change on the device all confirmed working, but whether `DIAG_M`
actually reaches `SendPolicy` and changes its threshold on a live link is
**not yet verified on hardware**: the one attempt to check it was
inconclusive (indoors, no fresh GPS fix arrived between the zoom change and
the check, so the phone's own status display never recomputed). The
`0.008` fraction is also unmeasured — a starting point, not a tuned value.
