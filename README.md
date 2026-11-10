# GT Link

An Android companion app for the **Huawei Watch GT 4** that works without Huawei Health.
It pairs with the watch directly over Bluetooth and talks Huawei's own link protocol.

Package `com.nmnd.gtlink` · minSdk 26 · Kotlin + Jetpack Compose · no account, no cloud.

> **Status: built and unit-tested, not yet tried on a real watch.** The protocol is ported
> from Gadgetbridge, which supports the GT 4. The handshake is checked end to end against a
> simulated watch, and the framing is checked against Gadgetbridge's real-device byte vectors.
> Firmware quirks only show up on hardware. When something doesn't work, open
> **Developer log**: it records every message that crosses the link.

## Features

| Area | What it does |
|---|---|
| Pairing | Finds the watch (paired devices or a Bluetooth scan). Runs Huawei's HiChain PIN pairing, which you confirm on the watch. Stores the long-term key and reconnects by itself, including after a reboot. Falls back to HiChain Lite or the legacy challenge if the watch asks for them. |
| Dashboard | Battery, model, firmware, today's steps, calories and distance with step-goal progress. Quick actions: sync time, send weather, test notification. |
| Notifications | Forwards notifications from any app, with a per-app on/off list. SMS and email are typed so the watch shows the right icon. Duplicate re-posts are suppressed. |
| Calls | Incoming calls ring on the watch with the caller's name. You can answer or reject from the wrist. Missed calls become a notification. |
| Music | The watch's music app controls the phone's active player (play, pause, skip, volume) and shows the artist and title. |
| Find my phone | The watch makes the phone ring at alarm volume. Stop it from the notification or the watch. |
| Alarms | Up to 5 alarms with name, time and repeat days, plus a smart alarm with a wake window. Can read back alarms edited on the watch. |
| Weather | Current conditions, 24-hour and 7-day forecast from Open-Meteo (no API key). Uses the phone's location or a city you pick. Refreshes every 30 min, or whenever the watch asks. |
| Watch faces | Lists installed faces, switches the active one, deletes non-built-in ones. |
| Install | Pushes **watch faces (.hwt)**, **watch apps (.bin)** and **music** to the watch over Huawei's file service, with progress and a screen-size check for faces. |
| Health history | Downloads per-minute steps, calories, distance, heart rate, SpO2 and resting HR, plus sleep, every 2 h. Shows 7/30-day step charts, daily heart-rate and SpO2 curves and last night's sleep. Exports CSV. |
| Replies | Reply to messages from the watch: the text goes into the app's own "Reply" action. Dismissing a notification on the phone clears it on the watch. |
| Camera remote | The watch's camera app becomes a shutter for the phone camera, with a self-timer. |
| Contacts | Writes chosen phone contacts to the watch's contact list. |
| Watch settings | Lift to wake, rotate to switch, wear wrist, 12/24h, date format, language, units, °C/°F. Do not disturb schedule. Continuous heart rate, high/low heart-rate alerts, SpO2 monitoring and low alert, TruSleep, stress, skin temperature. Step goal, inactivity reminder, body profile, disconnect alert, "don't push when not worn". |
| Maintenance | Forget the watch, factory reset (with confirmation), resend all settings. |
| Developer | Live protocol log (share or clear), and a raw command console (service, command, TLV hex). |

Not implemented yet (Gadgetbridge has them): workout/GPS track sync, TruSleep detail files
(REM/awake stages), ECG and stress history, calendar sync and canned replies (they need the
HarmonyOS P2P channel), GPX routes, offline maps, firmware updates.

## First pairing

1. If the watch is paired with another phone or Huawei Health, either reset it
   (watch: **Settings › System › Reset**) or use **Pair without resetting** below.
2. In GT Link, grant Bluetooth permission and pick the watch (it shows as
   `HUAWEI WATCH GT 4-XXX`). Accept Android's Bluetooth pairing prompt if one appears.
3. Confirm the pairing request on the watch screen within 30 seconds.
4. Open **Permissions** on the dashboard and grant notification access, calls, location
   and "no battery optimisation". Each one unlocks a feature; none is needed to connect.

### Pair without resetting

A watch that's still set up with Huawei Health accepts a new phone if that phone presents the
**same Huawei account ID** after authenticating (Gadgetbridge PR #3721). In GT Link: Pairing ›
*Pair without resetting the watch* › enter the ID, then pair as normal. Before you pair, remove
Huawei Health or revoke its Bluetooth permission. Don't unpair inside Huawei Health.

Finding the ID (a 17–19 digit number): sign in at cloud.huawei.com and read the `userId` cookie
in the browser's developer tools; or, with root, `old_user_id` in
`/data/data/com.huawei.health/shared_prefs/login_data.xml`; or `adb logcat | grep huid=` while
Huawei Health runs. This route is community-documented and may not work on every firmware. A
reset always works.

## Build

No Android Studio needed. You need:

- **JDK 17**, with `JAVA_HOME` pointing at it.
- **Android SDK command-line tools** (developer.android.com/studio, "Command line tools only"),
  unzipped so the path ends in `<sdk>/cmdline-tools/latest/bin`, and `ANDROID_HOME` set to `<sdk>`.
  Alternatively, put `sdk.dir=<sdk>` in a `local.properties` file (ignored by git).

One-time setup, then build:

```
sdkmanager --licenses
sdkmanager "platform-tools"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

On macOS/Linux use `./gradlew`. The first build downloads Gradle 9.3.1, Android platform 37,
the build tools and all libraries (about 1–2 GB). Coding agents: see `AGENTS.md`.

## How it is built

```
protocol/   Tlv (tag/varint-length/value), Frame (0x5A framing, CRC16, slicing,
            AES-CBC/GCM payload encryption), Crypto (Huawei key derivations, HKDF, PBKDF2)
link/       WatchLink: request/response matching + unsolicited message stream
            Authenticator: link params → security negotiation → PIN → HiChain op 1/2
            Commands: pure builders for every command (ids/tags from Gadgetbridge)
            WatchConnection: RFCOMM socket on UUID 82FF3820-…, reconnects, feature API
data/       WatchSettings (+ SettingsSync, which diffs settings into packets), AppStore
service/    WatchService (foreground; find-phone ring, media, calls, weather),
            NotificationRelay, CallMonitor, BootReceiver
weather/    Open-Meteo client, WMO → Huawei icon mapping
ui/         AppViewModel + Compose screens
```

The GT 4 is a classic Bluetooth device: RFCOMM, not BLE GATT. After authentication, every
message is AES-encrypted with the session key from the HiChain bind.

## What came from where

| Piece | Source |
|---|---|
| Gradle 9.3.1 wrapper, AGP 9.1.1 / Kotlin 2.3.20 / Compose BOM 2026.03.01 catalog, repository filters, `gradle.properties`, fonts, HCT colour approach | NewsPull |
| Protocol: framing, TLV, crypto constants, HiChain / auth flow, packet layouts, frame test vectors | [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (`devices/huawei`, `service/devices/huawei`), **AGPL-3.0** |

Because the protocol layer is a port of Gadgetbridge code, this app is a derivative work of
AGPL-3.0 software and is licensed under AGPL-3.0 (see `LICENSE`). Anyone you share it with
receives it under the same terms, and must keep sharing the source if they pass it on.
