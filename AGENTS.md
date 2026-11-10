# GT Link: notes for coding agents

GT Link is an Android companion app for the **Huawei Watch GT 4** that replaces Huawei Health.
It talks Huawei's link protocol over classic Bluetooth (RFCOMM). The protocol is ported from
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge), which is the reference for
every byte this app sends. Read `README.md` for the feature list and user-facing behaviour.

Package `com.nmnd.gtlink` · Kotlin · Jetpack Compose · minSdk 26 · compileSdk 37 · targetSdk 36.
There is no Android Studio in this workflow: everything is built from the command line.

## Status: never run on a real watch

The code builds and the unit tests pass, but nothing has been confirmed on hardware yet.
Treat any protocol behaviour that isn't covered by a test as a best guess from Gadgetbridge.
When the owner reports a problem, ask for the **Developer log** from the app (Developer screen ›
share). It records every frame in both directions, decrypted, and is the main debugging tool.

## Toolchain

- **JDK 17**, with `JAVA_HOME` pointing at it. Newer JDKs are not tested.
- **Android SDK** via the command-line tools, with `ANDROID_HOME` set. Run
  `sdkmanager --licenses` once; the first build then downloads platform 37 and the build tools.
  A `local.properties` file with `sdk.dir=...` also works; it's ignored by git.
- Gradle 9.3.1 comes from the wrapper. AGP 9.1.1, Kotlin 2.3.20, Compose BOM 2026.03.01.
  Versions live in `gradle/libs.versions.toml`; don't hard-code versions in build files.

## Commands

Run these from the project root. On macOS/Linux use `./gradlew`.

```
.\gradlew.bat :app:testDebugUnitTest      # unit tests (JVM, no device needed)
.\gradlew.bat :app:assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Every change must leave the build green and all unit tests passing.** Run both before saying
a change is done. There are no instrumented or UI tests; Compose screens are checked by hand.

## Layout

```
protocol/   Bytes (hex, CRC16), Tlv (tag + varint length + value, nested containers at tag >= 0x80),
            Frame (0x5A framing, slicing, AES-CBC/GCM body encryption, Frame.Reader reassembly),
            Crypto (Huawei key derivations, HKDF, PBKDF2)
link/       WatchLink        request/response matching by (service, command), `incoming` flow
            Authenticator    handshake: link params › device status › security negotiation ›
                             PIN › HiChain op 1 (auth) › op 2 (bind, gives the session key)
            Commands         PURE packet builders and parsers for every feature. No I/O.
            WatchConnection  RFCOMM socket, reconnect loop, initialisation, the feature API
            FileUploader     service 0x28 upload state machine (watch faces, apps, music)
            WatchPackages    unpacks .hwt / .bin / audio into an UploadItem
            ProtocolLog      bounded log behind the Developer screen
data/       WatchSettings (+ SettingsSync: diffs settings into packets), AppStore (SharedPreferences),
            HistoryDb (plain SQLite, no Room: per-minute samples and sleep segments)
service/    WatchService (foreground service; owns the connection), NotificationRelay,
            CallMonitor, BootReceiver, CameraBus
weather/    Open-Meteo client (no API key), WMO › Huawei icon mapping
theme/      Compose theme (HCT tonal palettes)
ui/         AppViewModel (the single ViewModel), GtRoot (Route enum navigation), screens/, camera/
```

## Rules

- **Protocol bytes come from Gadgetbridge.** Before adding or changing a command, find the
  matching class in Gadgetbridge (`devices/huawei/packets/*`, `service/devices/huawei/*`) and copy
  its service id, command id and TLV tags exactly. Cite the Gadgetbridge class in a comment.
- **Keep `Commands` pure.** Builders return a `Packet`; parsers take a `Tlv`. All sending,
  waiting and state lives in `WatchConnection`. This is what keeps the protocol unit-testable.
- **Test new protocol code.** Add a test in `app/src/test/.../link/` that checks the built TLV
  or frame bytes, ideally against a byte vector from Gadgetbridge's own tests.
- After authentication every packet is encrypted with the session key; `Packet(encrypt = true)`
  is the default. Only the handshake goes unencrypted, plus file-upload chunks when the watch
  asks for no encryption.
- A watch reply of `0x186A0` in tag `0x7F` means success (`RESULT_OK` and `Tlv.resultOk()` in
  `link/WatchLink.kt`).
- Before sending a command, check that the watch supports it (`WatchConnection.supports` and
  the expand capabilities in `WatchState`). Unsupported commands can make the watch drop the link.
- Never send factory reset, firmware or other destructive commands without an explicit
  confirmation in the UI.
- Compose UI follows the existing screens: Material 3, the theme in `theme/`, state from
  `AppViewModel` as `StateFlow`s collected with `collectAsStateWithLifecycle`.

## Licence

The protocol layer is a derivative of Gadgetbridge, so the whole app is **AGPL-3.0** (`LICENSE`).
Keep the licence and the attribution in `README.md`. Don't paste in code under incompatible
licences.
