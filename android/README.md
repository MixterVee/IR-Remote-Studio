# IR Remote Studio for Android — v0.1.1-alpha

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
normal application signing and field testing. USB-C learning and Harmony USB
connection are **not** included in this alpha build; Windows remains the
learning station.

## Data, safety and privacy

The application requests only the normal Android `TRANSMIT_IR` permission.
Import/export uses Android's document picker. It needs no Internet, account,
telemetry or contacts access. A 2-second signal limit is enforced before IR
transmission. IR signals should only be used to control equipment you own
or are authorized to operate.

The Android app and Windows app both use the same `.irplus` interchange
format; Android does not yet read Windows library.json files directly.
\n## Import fix in v0.1.1-alpha\n\nAndroid's XML parser does not support every JAXP configuration option used\nby Java desktop. The initial alpha enabled XML settings that could abort\nbefore reading valid Windows .irplus files. The importer now uses bounded XML\ninput and rejects DTD/entity declarations, while tolerating missing optional\nJAXP features. Failures now show the full reason in a visible dialog.\n\nUpdate by installing the new debug APK over the original; existing local\nremote data remains stored under the same application ID.\n