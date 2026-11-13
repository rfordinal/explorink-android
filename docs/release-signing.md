# Release signing, and what Google Play needs from the app

Two artifacts come out of this repo for users: the bundle Google Play
distributes, and the APK on the GitHub releases page. Both are signed with the
same key here, and they still end up different on a phone. This file is why, and
how to build each one.

## The key lives outside every checkout

`~/.config/explorink/android-release/` holds `release.jks` and
`keystore.properties`, both `chmod 600`. Nothing in this repository holds a key,
a password or a path to one.

`app/build.gradle.kts` reads that directory by default and honours
`EXPLORINK_KEYSTORE_PROPS` as an override; `keystore.file` inside the properties
file is resolved next to it. Where neither exists -- CI, somebody else's clone --
`bundleRelease` and `assembleRelease` still produce **unsigned** artifacts
instead of failing the build.

**Never put the key in a worktree.** The first one was created in a gitignored
`keystore/` inside the worktree that generated it, and removing that worktree
after the merge destroyed the only copy on 2026-11-09. It survived only because
it had been handed to the maintainer minutes before. A worktree is disposable
and this key is not: losing it means asking Google to reset the upload key before
the app can be updated again.

## Building

```bash
./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab, for Play
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk, for GitHub
```

Prove the signature before either leaves the machine:

```bash
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Both must report
`SHA-256 digest: 0b500f72803471e2462c9dc8c973648fc9004055f5efe77a3dfd513d04a80f0a`,
`CN=ExplorInk, O=Comsultia s.r.o.`. A different fingerprint means a different
key, and Play rejects the upload.

A release build carries no git suffix: `versionNameSuffix = gitDescribe()` is on
the debug type only, so a `versionName` of plain `0.3.14` is how you tell a
release build from a debug one on a phone
(`adb shell dumpsys package org.explorink.gpsbridge | grep versionName`).

## Play and the APK are two routes, and a phone takes one

The app is published to Play as a **bundle**, which means Play App Signing:
Google re-signs the app with a key of its own and ours is only the *upload* key.
So the copy a user installs from Play and the APK built here carry different
signatures, and Android refuses an update across a different signing key.

Consequences, in the order people hit them:

- A phone on the Play version cannot update from our APK, and vice versa.
  Switching routes means uninstalling first.
- Every build before 0.3.14 was signed with the shared Android **debug** key, so
  those installs cannot be updated by either route. Measured on a Galaxy S10,
  2026-11-13: `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match
  previously installed version`, and a clean install after an uninstall.
- Uninstalling loses the app's own storage on that phone. The reader pairing
  survives, because it lives in Android's companion-device record.

## The background-location disclosure is a policy requirement, not decoration

Google Play's location policy wants a prominent in-app disclosure **before** the
system prompt for `ACCESS_BACKGROUND_LOCATION`: it has to name the data, say it
is collected while the app is in the background, and say what it is for, in the
app rather than only in a privacy page. Going straight to `requestPermissions()`
fails that, and the App content declaration form asks for a video of this exact
screen appearing before the prompt.

`MainActivity.showBackgroundLocationDisclosure()` holds that text. It precedes
**both** routes to the permission, not just the first: once the system prompt
has been shown and declined, the only way to grant is the app's settings page,
and a user sent there with no explanation is the same policy problem plus a
worse experience.

Changing that dialog's text means the declaration video no longer matches what
the app shows. Re-record it when the wording changes materially.

## What Play asks about this app

All of these are Console-only; the Play Developer API does not cover them. The
answers that are true here, with their evidence:

| Declaration | Answer |
|---|---|
| Advertising ID | **No.** The merged release manifest carries no `com.google.android.gms.permission.AD_ID` |
| Location permissions | Required, because `ACCESS_BACKGROUND_LOCATION` is declared. Needs purpose, one feature, and a video |
| Foreground service | `FOREGROUND_SERVICE_LOCATION` plus `FOREGROUND_SERVICE_CONNECTED_DEVICE`, matching `BridgeService`'s `foregroundServiceType="location|connectedDevice"`. Connected device is continuous data transfer to an external device; location is **other**, with a written reason, never "navigation" |
| Health apps | None |
| Data safety | Precise location, collected and shared, ephemeral, required, purpose app functionality. Shared covers brouter.de. Encrypted in transit: **no**, the BLE hop runs with no pairing and no bonding |

"Navigation" is wrong on purpose rather than by oversight: the reader answers
where you are and never where to go, and the declaration is a statement to Google
about what the product does.
