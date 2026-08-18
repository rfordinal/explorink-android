# ExplorInk GPS — Android companion app

Sends the phone's GPS position to the Xteink X4 over BLE and records the ride
for replay. One window. Built to `docs/android-app-brief.md`.

Public pages for this side of the system:
[explorink.com/development/ble-gps/](https://explorink.com/development/ble-gps/)
and [explorink.com/how-it-works/](https://explorink.com/how-it-works/) — keep
them true in the same pass as any change here (`docs/site.md`).

Package `org.explorink.gpsbridge`, app label **ExplorInk GPS**.
minSdk 31, targetSdk 36, compileSdk 36. Debug-signed only.

## What it does

0. Starts itself when the rider opens the map screen on the paired X4 — the OS
   watches for that device and wakes the app, with the app swiped away and
   nothing tapped. Setup is one system dialog; see "Pairing and auto-start"
   below and `../docs/ble-app-wake.md`.
1. Scans for the paired X4 by MAC address (by `XteinkX4Map` and the service UUID
   while unpaired), connects, no BLE pairing.
2. Writes 21 bytes to characteristic `5a1e6d00-73a4-4f1e-9b8f-2c6e1a8f0002`
   whenever the position has actually moved — see the send policy below.
3. Records every raw GPS fix, every packet written (including failures) and
   every link event to one JSON Lines file per recording — started and stopped
   by the user.

4. Answers the device when it asks for the map tiles it is missing: pages the
   list off the command characteristic and pushes the tiles back over the
   transfer channel. See "Fetching missing tiles" below.

All of it runs in a foreground service, so it keeps going with the screen
locked and the app swiped away.

No route logic, no cloud.

## Public snapshot

The source is mirrored standalone at
[rfordinal/explorink-android](https://github.com/rfordinal/explorink-android)
— source only, no APK, no history. That repo has its own README, written
standalone; do not overwrite it with this one. Procedure, the leak scan and
what is still manual: [`../docs/android-snapshot-publish.md`](../docs/android-snapshot-publish.md).

## Battery

Low battery use is a priority. GPS, the wake lock and the send timer only run
while the device is connected or a recording is going; the service stops
itself after 5 minutes with no link, and the companion association wakes it
again when the rider opens the map. Scanning drops to low power after the
first 20 s. What costs what, and what is still unmeasured:
[`../docs/app-power.md`](../docs/app-power.md).

Planned, not built: receiving a shared route (Google Maps link, GPX, KML),
turning it into a `.tir` and pushing it to the card —
[`../docs/route-share-plan.md`](../docs/route-share-plan.md).

## Pairing and auto-start

Pairing is a **companion device association**, not BLE bonding — the link itself
still needs no pairing. Once per phone: open the map on the X4, press **Pair the
X4**, pick the device in Android's own dialog, then allow location all the time.

**Forget the X4** undoes it, behind a confirm; re-pairing is forget then pair. It
drops the association, unpins the link, forgets the remembered address and restarts
scanning, so the app keeps working on the old first-match rules.

It buys two things. The OS wakes this app when that X4 starts advertising, and
the app connects to that one MAC only — every X4 running this firmware advertises
the same name and the same service UUID, so unpaired the app connects to whichever
answers first. Full mechanism, limits and the unverified list:
`../docs/ble-app-wake.md`.

## Bluetooth off and on again

Airplane mode mid-ride does not kill the bridge any more. The app tears the
link down itself when the adapter goes off — Android does not reliably report
the disconnect — and reconnects on its own when it comes back, nothing tapped.
Mechanism, the teardown order and what is still unverified:
[`../docs/ble-adapter-off.md`](../docs/ble-adapter-off.md).

## One conversation at a time on the command channel

The device asks for things unprompted, and this app answers with a command. A reply
listing ends with a plain `OK`, so two conversations open at once cannot be told
apart -- each one can be ended by the other's terminator. Measured on hardware
2026-08-11: the sync screen asked both questions 15 ms apart, this app answered
both, and the fetcher read a 20-tile list as empty, pushed nothing, sent no skips,
and left the device showing 20 rows of "waiting" with nothing to explain it.

`BridgeService.onCommandLine` now defers a second ask and replays it when the
channel is free. The device serializes its asks too, but this side does not rely on
that -- an older build does not. Details and the wire evidence:
`../docs/ble-map-transfer-protocol.md`, "One conversation at a time".

## What the app shows about map squares

A dedicated block, in words, not the shorthand link trace: what the device asked
for, which square arrived or did not, the live transfer with two progress bars
(bytes of the current square, and settled squares of the ask), and a summary line.
Progress is in the notification too, so a phone in a bag says something useful
without being unlocked.

Every number goes through `TileFormat`, which is a port of the device's own sync
screen (`TileSyncActivity.cpp`: decimal kB, rate over the whole fetch from
completed squares only, skips counted apart). Its unit tests assert the values that
C++ prints, so the panel and the phone cannot drift apart -- they had: kB was 1024
bytes here and 1000 there, and the rate was per-square here and whole-fetch there.

## Fetching missing tiles

The rider starts it **on the device**, never the phone -- it is a transfer of
kilobytes over a link the rider is relying on for position, so the rider says
when. Two places start it, and they ask for different amounts:

- **Home menu > Sync map tiles.** The whole list, up to 200 entries.
  Preparation, done at home before a ride.
- **The map screen's autosync**, if the rider turned it on in Settings > Map.
  Sends the same ask with a trailing **`view`**, mid-ride, the moment a frame
  hatches. See "The `view` ask" below.

The conversation (`docs/ble-map-transfer-protocol.md` and
`firmware/explorink/docs/missing-tiles.md` in the parent repo):

1. Device indicates `NEED_TILES <count> fmt <version> [view]` on `...0003`.
2. App reads the list: `tiles` for a `view` ask, otherwise pages `missing` /
   `missing <offset>` until `missing_next=done`.
3. For each tile **in the order the device gave them** -- already fetch
   priority, regional LOD first -- the app reads the bytes from its
   `TileSource` and pushes them over `...0004`: begin, wait for `RDY`, chunks,
   `OK`.
4. A tile it does not have, or one built to a format version this device cannot
   read, becomes `skip <z> <col> <row> <reason>`, so the device's progress
   screen counts it as failed instead of waiting for a file that is never
   coming.
5. `FETCH_CANCEL` (the rider pressed Back) stops everything and aborts whatever
   is in flight.

A local timeout aborts one stalled tile and moves on, so the dead tile's verdict
is still coming when the next tile's begin goes out. Status lines carry no tile
identity, so `TileFetcher` counts owed verdicts and drops them —
`docs/ble-map-transfer-protocol.md`, "A status line says nothing about which
transfer it is for".

### The `view` ask

`NEED_TILES <n> fmt <v> view` means **answer from `tiles`, not from `missing`**.

`tiles` reports the tiles under the device's screen right now, at most 32, each
flagged `missing` or `ok`. `missing` reports every tile it has ever hatched, up
to 200. Mid-ride those are very different amounts of the rider's mobile data,
and only the first is what they are looking at.

What the app does differently, and nothing else changes:

- Sends `tiles` once. **Never pages** -- there is no `tiles <offset>`, and asking
  for one would hang the fetch until the timeout.
- Pushes only the entries flagged `missing`. An `ok` tile is already on the
  card, and pushing it would spend data to overwrite a file with itself.
- `INFO tiles=none` ends the fetch with "device has no viewport yet" -- the
  device has had no fix since it started, which is a different problem from
  having nothing to ask for.

`view` is a bare flag word with no value, so an older device simply never sends
it and the app pages `missing` as before (`MissingList.parseNeedTiles`).

**Verified end to end on real hardware, 2026-08-07.** A tile under the rider's
own position was deleted off the X4's card, the map was reopened and the app
connected:

```
device wants 1 tiles, format 3, scope viewport, source is CDN
list complete: 1 tiles of 1              191 ms, one request, no paging
landed z13 4485/2843 (317895 bytes)
fetch finished: done (1 sent, 0 skipped of 1)
```

317,895 B in 34.6 s = **9.0 kB/s**, at the 12.5 ms interval the app asks for and
gives back 180 ms after the fetch ends. The device redrew itself with the tile.

**The format version is checked before a single byte goes out.** A tile built
to another `.tib` version transfers fine, passes CRC, is renamed into place --
and is then refused by the device's reader on the next render, after the entry
has already been dropped from its missing list. That is a transfer wasted on
every fetch, not once. `TileHeader` reads the magic and the u16 version; a
mismatch is `skip ... fmt<found>`, which is deliberately distinct from a plain
miss: "no tile here" and "wrong tile here" want different fixes.

### Where tiles come from: the CDN, and only the CDN

`https://tiles.explorink.com/v<format>/base/<z>/<col>/<row>.tib`
(`docs/tile-cdn-plan.md`). Static files, no API -- a miss is a 404 and that is
the whole protocol. **Verified 2026-08-06** from the laptop and from the phone
over LTE: a real tile answers 200 byte-identical to the local build, a
nonexistent one answers 404.

**Nothing is stored on the phone.** There was a directory on it once -- a
stand-in from before the CDN existed, then briefly a cache for what the CDN
served. Both are gone. A tile belongs on the X4 or on the CDN; the phone is the
pipe between them, holding one in memory for the length of its transfer and
dropping it the moment it lands.

Caching would have traded a repeated download -- which happens only when a link
dies mid-sync -- for a phone that silently accumulates a continent of map data it
never reads itself. A second source would also be somewhere for the two to
disagree.

**The format version in the URL is the device's, not the app's.** The CDN
publishes one path per `.tib` `FORMAT_VERSION`, and the device states which it
reads in `NEED_TILES ... fmt N`. Passing that straight through makes a format
mismatch impossible by construction instead of something detected after a wasted
transfer -- and it paid off immediately: the firmware moved to format 3 the same
day, and the app followed with no change at all, because it had never hardcoded
2. An older firmware that does not say leaves it null, and only then does the app
fall back to a compiled-in guess.

### The source is asynchronous, and has to be

`TileSource.read()` takes a callback rather than returning bytes. The fetch runs
on the service's main thread because that is where BLE lives, and Android throws
`NetworkOnMainThreadException` for an HTTP call there. Every implementation does
its work on a worker and comes back on the main thread, so the state machine
keeps its single-threaded contract.

That opened one case worth a test of its own: a read can land *after* the fetch
it belongs to has been cancelled or lost its link. A late answer must not open a
transfer on a run that is already over.

### One GATT operation at a time

Android runs exactly one GATT operation per connection and reports it on a
callback; a second issued before the first completes is refused. With position
writes, console lines, transfer chunks and CCCD writes all in play, every
operation goes through one queue -- `GattOpQueue`, with `BleLink` keeping the
enqueue call sites, the BLE calls and the callbacks -- and the completion
callback pumps the next.

A timeout there is not a completion: Android holds its busy flag until the
timed-out operation's real callback arrives, so the queue keeps the slot as a
tombstone and pumps nothing until it does. Per-op budgets (10 s for a transfer
frame, 3 s for the rest), the tombstone rule, the 30 s dead-link verdict and
what it cost before:
[`../docs/ble-gatt-op-queue.md`](../docs/ble-gatt-op-queue.md).

A transfer does not starve the position channel: the fetcher sends its next
chunk only from the previous chunk's callback, so a position write waits at
most one chunk. A position write already in flight is *not* queued behind
again -- the queue would fill with fixes that are stale by the time they go
out, and only the newest position is worth sending.

Chunks use write-with-response, and that is load-bearing rather than politeness:
the ATT response arrives only once the device has the bytes on its SD card, so
driving the loop off it means the sender physically cannot outrun the card.

**The CDN read for the next tile starts at this tile's `RDY`, not at its `OK`.**
Before that it ran between the two, so the link sat idle at HIGH priority for a
whole HTTPS GET at every tile boundary -- 0.3-1.5 s of dead air per tile with
both radios on. `TileFetcher.maybePrefetch()` holds exactly one tile ahead: a
deeper queue would buy about a second and cost megabytes on a phone mid-ride.
The held bytes are consumed only if the tile actually popped is the one they
were read for, and dropped on cancel, link loss, a listing restart, or a skip of
that tile. Added 2026-08-13, unit-tested, **not measured on hardware** -- the
expected saving is 0.3-1.5 s per boundary against ~5 s per 35 kB tile at the
measured 7.9 kB/s. Rules and reasoning:
[`../docs/ble-map-transfer-protocol.md`](../docs/ble-map-transfer-protocol.md),
"Read the next tile while this one is still going out".

The app requests a 517-byte MTU after subscribing, and it asks **through the
queue** like any other operation -- `requestMtu` takes the stack's busy flag, and
issued straight from the subscribe callback it used to get the next queued write
refused, which is usually the fetcher's `missing` ask. On the default 23-byte MTU
a chunk carries 15 payload bytes, measured at 0.2 KB/s against the X4 -- a 4 KB
tile took 25 seconds. **Measured against the real device 2026-08-06**: the link
settles on 256, so a chunk carries 248 bytes.

It also asks for a high-priority connection **for the duration of a fetch and no
longer**. The first real fetch moved 450 kB over nine tiles in 183 s -- a steady
2.4 kB/s -- with a 50 ms connection interval. A chunk is write-with-response, so
it costs one interval out and one back: 248 bytes per 100 ms is the whole
ceiling, and the MTU was never the limit. At the ~15 ms a high-priority
connection asks for, the same transfer should be roughly three times quicker.

Scoped to the fetch because a fast interval holds the radio busy continuously,
which is battery spent for nothing once the tiles have landed; position packets
go out every few seconds and do not care about interval at all. Released on
every exit -- done, cancelled, link lost, service stopping -- which is why
`TileFetcher.finish()` is the single place that hands it back, and why there is a
test that each of those paths does.

Android usually ignores a peripheral's request for faster parameters, so this has
to come from the phone; the device cannot ask for it on its own.

The command and status channels are **indications**, not notifications: the
device sends multi-line replies faster than the connection interval drains
them, and an unacknowledged notification can have its tail dropped by the
controller with no error. The device also refuses to start a transfer with
nobody subscribed to the status channel, so both subscriptions happen at
discovery, not at the first tile.

All three extra characteristics are optional at discovery. An older firmware
build has only the position characteristic, and forwarding fixes -- the app's
whole original job -- still works against it.

## Checking freshness: is what the device already has still current?

A different question from the fetch above, and the reason it has its own
exchange. `missing` and `tiles` are about tiles the device does **not** have.
This is about tiles it does: they open, they draw, and the map may have been
rebuilt and republished since. Before this, no already-synced device ever found
out -- a tram-line classification bug was fixed, the area rebuilt and pushed,
and every device kept the wrong tile permanently.

```
device -> phone   CHECK_TILES <count>
phone  -> device  have
device -> phone   INFO have_total=<n>
                  INFO have_<z>_<col>_<row>=<content_id hex>
                  OK
phone  -> device  stale <z> <col> <row>          one per differing tile
phone  -> device  checked <n> | checked unknown
```

**`have_total` is checked against the lines that arrive, and a short listing is
answered `checked unknown`.** Measured on hardware 2026-08-13: a four-tile
reply arrived as one tile line, and the check reported "0 stale of 1" for a
screen holding two out-of-date tiles -- the device believed it and kept them for
nine days. The device-side cause (one BLE indication per reply line, clobbering
a one-slot queue) is fixed in the firmware; this end refuses to turn a partial
listing into a verdict either way (`MissingList.HaveReader.truncated`).

`content_id` is `crc32` over the six per-layer `crc32`s a tile already carries,
so the device computes it with no seek and no read. Never a timestamp:
`osm_epoch` does not move when only the build rules change, which is exactly the
case the feature exists for.

The app reads the CDN's freshness index by **byte range** -- a dense array of
16-byte slots, one file per z7 block, the slot's offset arithmetic on
`(z, col, row)` (`TileIndex.kt`, ported by hand from
`mapbuilder/tilegen/tile_index.py`, pinned against it by `TileIndexTest`).

**One range request per zoom plane, not one per tile.** Slots are row-major, so
a whole viewport sits between one lowest and one highest offset: a few kB in one
request. A round trip per tile would not finish inside the device's 15-second
patience on mobile data.

Three answers the app is careful to keep apart:

- **stale** -- the index has this ground and its `content_id` differs. Certain.
- **current** -- the index has it and agrees. Also certain.
- **`checked unknown`** -- no signal, a server error, or only part of the index
  read. **Nothing is claimed.** Answering "stale" here would push the rider's
  whole viewport over a 7 kB/s link to replace tiles that were already right;
  answering "current" would bury the bug. The app also backs off, doubling from
  1 minute to 30, so an offline phone is not asked the same unanswerable
  question every cooldown -- and a device asking again inside that window is
  answered `unknown` without the window growing.

A slot that is not present is **not** stale. It means the CDN publishes nothing
for that ground; there is nothing to fetch, so there is nothing to say.

### `?crc=` is what makes the fetch actually replace the tile

A stale tile joins the ordinary fetch. The app requests it as
`<path>.tib?crc=<content_id>`, the version the index promised.

The path does not change when a tile is rebuilt and the edge caches a path for
**seven days with no purge mechanism**. Without the query the fetch gets back
the exact copy it is replacing, the device writes it, finds it still differs,
and asks again -- forever. The query makes every content version its own cache
key.

The app verifies the arriving tile's `content_id` anyway (`TileHeader.contentId`,
the third implementation of that number and pinned to the other two), retries
once past the cache, then gives up with `skip` rather than push a tile it cannot
vouch for.

**Not yet run against hardware.** The firmware side -- the setting, the stale
list, `CHECK_TILES` -- is separate work; everything here is unit-tested and
nothing has been on the glass.

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
speed-adaptive **only below ~26 km/h**. Above that the time floor alone
always covers more ground than the move threshold requires, so the policy
behaves like a plain fixed-interval timer; see
[`../docs/send-interval-analysis.md`](../docs/send-interval-analysis.md) for
the real-ride data this was checked against and what it did and didn't
confirm.

| | |
|---|---|
| never faster than | **7 s** — the floor |
| at walking pace (≤2.2 m/s avg since last send) | **30 s** floor instead |
| never slower than | **60 min** — the keep-alive, so the device knows the phone is alive |
| in between, send when | the position moved **50 m** from the last sent one |
| also send when | the 16-sector heading changed **and** at least 10 m was covered |
| move threshold is raised to | the fix's own accuracy, so a bad fix indoors triggers nothing (no upper bound yet — flagged as a bug in the analysis doc) |
| move threshold, once the device has said its screen diagonal (`DIAG_M`) | `diagonal_m * 0.008` instead of the flat 50 m — one constant cannot fit every zoom rung, `../docs/ble-map-transfer-protocol.md`, "Viewport diagonal" |
| also send when | parked, the last packet actually sent carried a fix worse than 20 m accuracy, and the phone has since settled on 3 fixes in a row at ≤10 m (`reason: correction`) — never observed firing on real rides so far, accuracy on this hardware is 3.8 m 95% of the time |
| always send once | on every new link, `reason: first` — the state above is per-link and resets on connect. Waits for a fix accepted on that link, so it never sends the previous ride's position. Fixed 2026-08-17 and confirmed on the X4 the same day; before that the state survived the disconnect and a parked rider opening the map screen got no packet at all, `../docs/send-interval-analysis.md` §8 |

What that works out to:

| Situation | Packets |
|---|---|
| 100+ km/h on a highway | one every 7 s — the floor; the 50 m threshold never binds here |
| 25.7 km/h | the break-even point (50 m / 7 s) |
| 5 km/h hiking | one every ~18 s |
| parked, lunch break | one an hour, or none if nothing needed correcting |

The heading rule needs real movement behind it because a stationary phone's
bearing wanders across all 16 sectors on GPS noise alone — though real ride
data found this alone isn't enough at a dead stop (a rider walking to a fuel
pump clears 10 m easily); gating it on vehicle speed instead is one of the
analysis doc's recommendations, not yet built.

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
reasoning in it, so it is the part with unit tests on it (21 of them).
`BridgeService` holds the one piece of state `SendPolicy` itself must not —
`lastKnownDiagonalM`, the last `DIAG_M` heard on this link — and passes it in
as a parameter, the same way it already holds `lastSentFix` and
`preciseFixStreak`.

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
ExplorInk GPS 0.2.0-g3f38ecd (2)
```

Shown small and grey at the bottom of the one window, and written into every
recording's header as `app_version`. On a sideloaded debug build "which of six
builds is on the phone" is otherwise unanswerable.

## Files

```
android/
  app/src/main/java/org/explorink/gpsbridge/
    BridgeService.kt     the bridge: BLE, GPS, 5s send timer, recorder,
                         counters, notification. Survives a locked screen.
    MainActivity.kt      the one window. Binds, renders a snapshot, four
                         buttons. Holds no state of its own.
    BleLink.kt           scan, connect, reconnect, the GATT operation queue,
                         all four characteristics, indications; UUIDs and name.
                         pinnedAddress is the paired X4 and the only device it
                         will talk to once set
    CompanionWake.kt     the companion association: pairing dialog, presence
                         observation, the paired MAC address
    X4PresenceService.kt bound by the OS when the paired X4 advertises;
                         starts BridgeService and nothing else
    SendPolicy.kt        when a position is worth a BLE write. No Android
                         types, so it is unit-tested.
    FixGate.kt           which fix is trusted as the position at all,
                         upstream of the send policy. No Android types.
    HeadingTrend.kt      heading from recent fixes rather than one bearing
    PositionPacket.kt    the 21-byte encoder and heading sectors
    SessionLogger.kt     JSON Lines recording file, fsynced per line
    TileFetcher.kt       the whole fetch conversation as a state machine.
                         BLE behind Transport, time behind Scheduler, so it
                         is unit-tested without either.
    TransferFrames.kt    the transfer wire format: frames, CRC32, chunk
                         sizing, path rules, status lines. Pure.
    FreshnessChecker.kt  the CHECK_TILES conversation as a state machine, and
                         the offline backoff. Same no-BLE-no-Android contract
                         as TileFetcher, so it is unit-tested too.
    MissingList.kt       parses NEED_TILES, CHECK_TILES, and the `missing`,
                         `tiles` and `have` replies
    TileIndex.kt         the CDN freshness index: slot offsets, slot parsing,
                         and grouping a viewport into one byte range per zoom
                         plane. A hand port of mapbuilder/tilegen/tile_index.py.
    TileHeader.kt        magic + format version of a .tib, and content_id from
                         its layer directory
    TileSource.kt        the CDN seam: tiles, with ?crc= and verification
    IndexSource.kt       the CDN seam for index byte ranges
  app/src/test/java/...  PositionPacketTest, SendPolicyTest, FixGateTest,
                         HeadingTrendTest, TransferFramesTest,
                         MissingListTest, TileFetcherTest, TileIndexTest,
                         TileHeaderTest, FreshnessCheckerTest -- 117 tests,
                         pure JVM
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
`/sdcard/Android/data/org.explorink.gpsbridge/files/explorink-gps-<YYYYMMDD-HHmmss>.jsonl`.

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
jq -c 'select(.type=="packet")' explorink-gps-*.jsonl | head
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

Laptop: clean build, zero Kotlin warnings, 69/69 unit tests.

**Auto-start verified on hardware 2026-08-11**, Galaxy S24 (SM-S928B, Android 16)
against the real X4: paired, app process killed, map reopened on the device, and
the OS started a fresh process that connected at MTU 256 and sent one packet with
nobody touching the phone. It also found a bug no test could — pairing needs the
BLE link released first, or the device is not advertising and the dialog stays
empty. Reboot survival, ignoring a second X4, and battery cost are still open:
`../docs/ble-app-wake.md`.

**The tile fetch has never run against the real device.** Verified on the
laptop only: the frame layout byte for byte against the protocol doc, CRC32
against zlib's known value, the paging, the format-version refusal, and the
ugly paths through the state machine -- a tile the source lacks, a device `ERR`,
a stalled transfer, a cancel, a dropped link. What none of that proves is the
part that needs hardware: MTU negotiation with the X4, indication delivery on
both channels at once, and whether a real tile transfer holds up over a link
that is also carrying position packets. Running a fetch against a card with a
real `missing_tiles.json` is what would settle it.

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
