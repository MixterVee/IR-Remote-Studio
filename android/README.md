# IR Remote Studio for Android — v0.3.0-alpha

**Android companion to IR Remote Studio for Windows.** This is a test APK, not a Play Store or stable release. It is designed to build remotes, capture through a compatible Harmony USB learner and share `.irplus` files with Windows and the IRplus Android app.

## What's new in v0.3.0-alpha

This update ports the Windows v1 library and layout workflow to Android:

- **Create devices and buttons first**, even when a button has no IR code yet. Tap an unlearned button or long-press an existing one to learn an IR command.
- **Capture into existing buttons.** On successful USB Harmony capture for a preselected button, the command is automatically saved to the local library; the old sample is retained.
- After a saved capture, choose **New button**, **Existing button**, or **Finish**. A newly captured unassigned command must first be named and saved before those choices appear.
- **Multiple samples per button.** Long-press a button for **Undo last capture**, **Clear all captures** (keeps name and layout), or **Choose best match**. Raw IR signals can still export as Pronto Hex; status labels distinguish saved/verified/mismatched commands. Agreement compares complete Pronto samples, so slight timing differences may appear as mismatches.
- **Layout Editor** for staged button ordering, width from 1–12 columns and a 12-column preview. **Save layout** commits the changes; **Cancel** discards them. This controls exported `.irplus` order and width.
- A proper **About / Version** screen with accurate Android hardware support and project information.
- **Full JSON library backup and restore**, including uncaptured buttons, button width, sample history and selected samples, via the Android system document picker. Restore asks for confirmation and saves a local recovery copy when possible. Legacy Android `remotes.json` files migrate automatically.

### Install and protect your library

Download the latest successful **IR Remote Studio v0.3.0-alpha Android debug** artifact from [GitHub Actions](https://github.com/MixterVee/IR-Remote-Studio/actions/workflows/android.yml), extract the ZIP and install `app-debug.apk`.

**Before replacing any existing test APK**, open its Import/Export controls and export each remote as `.irplus`. If you're already using v0.3.0-alpha, open **About / Version → Library backup...** to save a complete JSON backup. GitHub-generated debug APKs may use different signing keys, so Android may reject installation over an earlier debug build. **Uninstalling the older app can erase its private saved library**. Keep your backups elsewhere before uninstalling.

### Hardware and feature differences from Windows

- **Android:** Compatibility-tested pathway remains the app's existing **Logitech Harmony 300/350 USB learning** and diagnostics, with Android USB host permission.
- **Windows:** The **SofaBaton U1 Bluetooth** learner and persistent BLE connection are available in the Windows application. Native Android U1 BLE learning is *not yet implemented or hardware-verified*. This release does not send any U1 BLE commands.
- **IR transmission:** Uses Android's public `ConsumerIrManager` when available. Some phones with IR hardware do not expose that API. Import/export remains usable without transmission capability.
- **File interchange:** Both platforms import/export `.irplus` PRONTO_HEX for learned commands and layouts. Android's full library JSON backup is **Android-specific**, not a direct import of the Windows `library.json` capture-history format.

### Learning workflow

1. Plug a compatible Harmony 300/350 into your phone through USB OTG, grant USB access under **Harmony USB • Connect / Troubleshoot**, and create/select a remote.
2. Tap **+ Add Button** to create and name the button before learning.
3. Tap that unlearned button to capture, or long-press any previously learned button and choose **Learn / add another sample**.
4. When the Harmony indicates readiness, press and release the button on the source remote. On a valid capture targeted to an existing button, Android saves it immediately.
5. Choose **Existing button**, **New button**, or **Finish**. Undo or clear a bad capture without deleting the button.
6. Open **Layout Editor...** to reorder buttons or adjust widths. Save the layout, then export your remote as `.irplus`.

### Source and tests

Open the `android/` directory in Android Studio using Java 17, Gradle 8.13, Android SDK 35. The [Android Actions workflow](../.github/workflows/android.yml) runs unit tests and builds the debug APK.

Earlier Android prototypes and troubleshooting notes are retained in [development history](DEVELOPMENT-HISTORY.md).

MIT License: see [LICENSE](../LICENSE). Source repository: [MixterVee/IR-Remote-Studio](https://github.com/MixterVee/IR-Remote-Studio).
