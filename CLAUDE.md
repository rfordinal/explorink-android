# Working in this repo

This is the Android companion app for ExplorInk, package
`org.explorink.gpsbridge`, label **ExplorInk GPS**. `README.md` has what it
does, the BLE protocol it speaks, the send policy, and the test suite.

This repo is a standalone snapshot pulled out of a private monorepo
(`xteink`) that also holds the firmware and the map tooling. If you are
working from that monorepo, its own `CLAUDE.md` is the one that governs
worktrees, branching, flashing, and the rest — this file only covers what is
specific to this repo on its own.

## Manual testing: see docs/manual-test-checklist.md

A clean build and a green `./gradlew testDebugUnitTest` prove the code does
what its author thought. They do not prove the feature works on a phone
against a real device. **Testing a change means running the matching
sections of [`docs/manual-test-checklist.md`](docs/manual-test-checklist.md)
on a real phone with the app installed**, not just reading the diff.

Always run section 1 (fresh install and launch) and section 2 (permissions)
after any change. Run the other sections the change actually touches.

Record what you ran and the result — do not just say "tested" with nothing
behind it.

## Releasing: see docs/release-signing.md

The app is on Google Play since 2026-11-13, and the signing key lives outside
every checkout. Before building anything a user will install, read
[`docs/release-signing.md`](docs/release-signing.md): where the key is, how to
prove a build carries it, why a Play install and an APK install can never update
each other, and what Play's declarations say about this app. Changing the
background-location disclosure text also invalidates the video filed with that
declaration.
