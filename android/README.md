# IR Remote Studio for Android — v0.1.2-alpha

An **experimental, unsigned-for-production / debug-signed** Android companion
for IR Remote Studio Windows. This is the first test application, **not** a
Play Store release.

## Initial features

- Import Windows-generated `.irplus` PRONTO_HEX remote files from the Android
  system file picker. Keep all imported remotes in app-private storage.
- Display each remote with its original 12-column layout and intentional spaces.
- Transmit a selected button's Pronto IR command using Android's public
  `ConsumerIrManager` API where available.
- Export the selected remote as `.irplus` from Android's file picker.
- Use the project's transparent remote-and-paintbrush logo.

**Important:** Some phones contain a built-in IR blaster but do **not** expose
it through Android's public ConsumerIrManager API. Such phones can still
import and export remotes, but cannot transmit with this first implementation.
The app explicitly reports whether Android exposes an IR emitter.

## Building

Open `android/` as a Gradle project in Android Studio, with Java 17 and
Android SDK 35 installed. AGP 8.13.2 with Gradle 8.13 is used. Or download
the debug APK from the GitHub Actions **Android prototype APK** workflow.

The APK is debug-signed for testing only. An eventual public release requires
normal application signing and field testing. The USB lab can **enumerate devices** and request access permission but cannot
capture IR signals yet. USB-C learning and Harmony protocol commands remain
future work; Windows is still the learning station.

## Data, safety and privacy

The application requests only the normal Android `TRANSMIT_IR` permission.
Import/export uses Android's document picker. It needs no Internet, account,
telemetry or contacts access. A 2-second signal limit is enforced before IR
transmission. IR signals should only be used to control equipment you own
or are authorized to operate.

The Android app and Windows app both use the same `.irplus` interchange
format; Android does not yet read Windows library.json files directly.
\n## Import fix in v0.1.2-alpha\n\nAndroid's XML parser does not support every JAXP configuration option used\nby Java desktop. The initial alpha enabled XML settings that could abort\nbefore reading valid Windows .irplus files. The importer now uses bounded XML\ninput and rejects DTD/entity declarations, while tolerating missing optional\nJAXP features. Failures now show the full reason in a visible dialog.\n\nUpdate by installing the new debug APK over the original; existing local\nremote data remains stored under the same application ID.\n
## Harmony USB lab — v0.1.2-alpha

This is a **read-only discovery prototype**, not IR capture.

1. Install the Android test APK on a USB-host-capable phone.
2. Insert batteries in the Harmony 300/350 and connect its USB data cable to
   the phone using a USB-C OTG adapter.
3. Open **Detect Harmony USB (experimental)**.
4. Copy the report and share it with the developer. It reports USB device
   vendor/product IDs, interface and endpoint descriptors and permission state.
5. If the Harmony is recognized and permission is missing, tap **Request USB
   access**, approve Android's prompt, then scan again.

For Harmony 300/350, expect VID `046D` and PID `C124`, though we must
confirm this with your hardware. A successful enumeration, permission grant,
or descriptor read does **not** constitute IR learning. We send **no commands**
to the remote in this build and never write its settings or firmware.

The USB diagnostics have no network access. Only the user can choose to copy
the report to the Android clipboard.

### Current priorities

Harmony USB IR learning on Android and a Concordance-independent Windows
learning backend are **equal** priorities. Cosmetic editing and new remotes
are deferred until the hardware experiments are validated.
