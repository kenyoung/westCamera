# westCamera

An Android app that creates and removes empty "signal" files in a Dropbox account.
It exists to control a process on a desktop computer: that process watches a
subdirectory of its synced Dropbox tree and reacts to the appearance or
disappearance of specifically named files.

The app writes to the `/detections/` directory at the Dropbox account root, which
syncs down to the desktop (for example `~/Dropbox/detections/`), where the
controlled process is watching.

## What it does

- Connects to Dropbox once via the browser-based OAuth 2 PKCE flow (no app secret,
  a refresh token is stored so you only sign in once).
- Shows one row per signal file, each with **Create** and **Delete** buttons:
  `shutup`, `run`, `shutdown`, `quit`.
- Create writes a zero-byte file; Delete removes it. Both use Dropbox's API, so the
  change reaches the desktop after the normal Dropbox sync delay.

## Requirements

- Android 8.0 (API 26) or newer.
- A Dropbox app (scoped, Full Dropbox access) created at
  <https://www.dropbox.com/developers/apps>, with the `files.content.write` and
  `files.metadata.read` permissions enabled and submitted.

## Configuration

The Dropbox **App key** is set in one place, `app/build.gradle.kts`:

```kotlin
val dropboxAppKey = "YOUR_DROPBOX_APP_KEY"
```

From there it feeds both `BuildConfig.DROPBOX_APP_KEY` (used in the auth call) and
the manifest redirect scheme `db-<appkey>`. The app key is a public client
identifier for a PKCE app and is embedded in the distributed APK; it is not a
secret. The Dropbox **app secret** is never used and must never be added to the app.

## Build

This project uses the Gradle wrapper, so no local Gradle install is needed.

```bash
./gradlew assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

A `local.properties` file pointing at your Android SDK is required but is not
committed. Android Studio creates it automatically; otherwise add:

```
sdk.dir=/path/to/Android/Sdk
```

## Install

Open the project in Android Studio and run it on a device, or install the built
APK over adb:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Tap **1. Open Dropbox login (browser)**, approve access as the Dropbox account
   your desktop syncs, and copy the shown access code.
2. Paste the code into the app and tap **2. Finish connecting**.
3. Use the **Create** / **Delete** buttons on each signal file's row.
