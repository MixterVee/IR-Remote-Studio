# IR Remote Studio for Android — v0.4.3-alpha

**Android companion to IR Remote Studio for Windows.** This is a test APK, not a Play Store or stable release. It is designed to build remotes, capture through a compatible Harmony USB learner and share `.irplus` files with Windows and the IRplus Android app.

## Windows v1 workflow carried forward from v0.3.0-alpha

This update ports the Windows v1 library and layout workflow to Android:

- **Create devices and buttons first**, even when a button has no IR code yet. Tap an unlearned button or long-press an existing one to learn an IR command.
- **Capture into existing buttons.** On successful USB Harmony capture for a preselected button, the command is automatically saved to the local library; the old sample is retained.
- After a saved capture, choose **New button**, **Existing button**, or **Finish**. A newly captured unassigned command must first be named and saved before those choices appear.
- **Multiple samples per button.** Long-press a button for **Undo last capture**, **Clear all captures** (keeps name and layout), or **Choose best match**. Raw IR signals can still export as Pronto Hex; status labels distinguish saved/verified/mismatched commands. Agreement compares complete Pronto samples, so slight timing differences may appear as mismatches.
- **Layout Editor** for staged button ordering, width from 1–12 columns and a 12-column preview. **Save layout** commits the changes; **Cancel** discards them. This controls exported `.irplus` order and width.
- A proper **About / Version** screen with accurate Android hardware support and project information.
- **Full JSON library backup and restore**, including uncaptured buttons, button width, sample history and selected samples, via the Android system document picker. Restore asks for confirmation and saves a local recovery copy when possible. Legacy Android `remotes.json` files migrate automatically.

### Install and protect your library

Download the latest successful **IR Remote Studio v0.4.1-alpha Android debug** artifact from [GitHub Actions](https://github.com/MixterVee/IR-Remote-Studio/actions/workflows/android.yml), extract the ZIP and install `app-debug.apk`.

**Before replacing any existing test APK**, open its Import/Export controls and export each remote as `.irplus`. If you're already using v0.3.0-alpha or newer, open **About / Version → Library backup...** to save a complete JSON backup. GitHub-generated debug APKs may use different signing keys, so Android may reject installation over an earlier debug build. **Uninstalling the older app can erase its private saved library**. Keep your backups elsewhere before uninstalling.

### Hardware and feature differences from Windows

- **Android:** Compatibility-tested pathway remains the app's existing **Logitech Harmony 300/350 USB learning** and diagnostics, with Android USB host permission.
- **Android:** This version adds U1 **BLE scanning, identity verification, persistent connection, stored-command read, backup and the original Windows v0.3.3 learning commands**. The protocol is ported and unit-tested, but **actual Android U1 hardware learning has not yet been confirmed**. Android requests Bluetooth Scan/Connect permissions (or location permission on Android 8–11 for BLE discovery). Unrelated nearby devices are not shown.
- **Windows:** The original working **SofaBaton U1 Bluetooth** learner is unchanged. Android's new BLE transport requires real-phone confirmation before claiming equivalence.
- **U1 backup and restore:** Before every U1 learning attempt, Android reads slot 8, writes its previous **raw IR waveform** to private `U1-Learn-Backups/` JSON, reads and validates it, and only then sends the observed learning-start command. Use **U1 Backups / Restore** to export a copy, import a prior Windows backup, or restore a waveform **into an Android library button**. **This is NOT a restore of the U1's internal programming, profiles or button mappings.** No safe hardware-restore/write protocol is established; this version does NOT attempt an on-device restore.
- **IR transmission:** Uses Android's public `ConsumerIrManager` when available. Some phones with IR hardware do not expose that API. Import/export remains usable without transmission capability.
- **File interchange:** Both platforms import/export `.irplus` PRONTO_HEX for learned commands and layouts. Android's full library JSON backup is **Android-specific**, not a direct import of the Windows `library.json` capture-history format.

### SofaBaton U1 Bluetooth workflow (added in v0.4.0-alpha)

1. Back up existing Android remotes from **About / Version → Library backup** before installing another debug APK.
2. Put the U1 into pairing mode by holding **– and E** until its screen indicates pairing, and fully close the official SofaBaton app.
3. Tap **SofaBaton U1 • Connect**, grant the Android Bluetooth permissions when prompted, and watch **SEARCHING → CONNECTING → CONNECTED**. Only one identified SB-prefixed U1 can auto-connect. If several match, choose yours from the small U1-only dialog.
4. Create or select a remote and button. Tap **Learn IR Button** or an unlearned button, or long-press an existing button and select Learn.
5. The first time, acknowledge that learning may replace physical U1 slot 8. Every attempt **reads, saves and verifies the previous IR waveform before starting**. If no valid stored code can be backed up, the app will refuse to start learning.
6. When the U1 says Learning/Ready, press the source remote command. The app validates the resulting IR and adds it to the button's saved capture history. Choose **Existing / New / Finish**.
7. Tap **U1 Backups / Restore** to import/export saved slot-8 waveform files or add a previously saved waveform into a selected Android library button. This never sends a hardware-restore command to the U1.

**Restore boundary:** A waveform backup is not a complete U1 image. Restoring U1 internal configuration must remain unavailable until a real, verified U1 restore protocol and full profile backup are established. Saving, clearing, importing or restoring samples to the Android library does not reprogram the U1.

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



## v0.4.3-alpha: Cleaner Android home screen

This update changes only the **Android home-screen presentation**, not the now-confirmed SofaBaton U1 Bluetooth scanning, preflight slot-8 backup, packet learning, capture saving, or IRplus exporter.

- Compact identity/version header and a single **SofaBaton U1** connection card with unambiguous **Connect U1 / Connected** status.
- The primary **Learn IR Button** stays prominent and adapts to connected U1 Bluetooth.
- The selected remote, **+ New Remote**, and **+ Add Button** stay on the main screen.
- One **Tools & Settings** menu replaces the long rows of secondary buttons. It contains **Import .irplus**, **Export selected remote**, **Layout Editor**, **Manage selected remote**, **U1 waveform backups / restore**, **Full library backup / restore**, **Harmony USB diagnostics**, and **About / Version**.
- Removes the duplicated remote heading and the outdated fixed Harmony-only footer. The entire page can scroll, so remote buttons and the status section are not compressed/clipped on smaller screens.
- Latest status shows a compact two-line preview; **tap it to read the complete capture or diagnostic message**.
- The `.irplus` and local library storage formats are unchanged.

The APK remains signed using the **same permanent certificate** introduced in v0.4.2-alpha. It advances `versionCode` to 26; it is intended to install **over v0.4.2 without uninstalling**. Test that before choosing any uninstall: uninstalling still wipes app-private U1 backup files. To preserve backups externally, use Tools & Settings → U1 waveform backups / restore → Export, and for complete remote libraries use Tools & Settings → Full library backup / restore.

## v0.4.2-alpha: Permanent APK signing (one-time owner setup required)

Previous GitHub Actions runs used a fresh ephemeral Android debug signing certificate for every build. Android requires the **same certificate for every upgrade**, so old APKs couldn't update in place and users had to uninstall; that uninstall also deletes the app's privately stored U1 backups and captured signals.

This release switches the build to a **single persistent test signing key**, stored in private GitHub Actions secrets. The public repository contains **no signing keys or passwords**. To avoid another accidental data-wiping version, CI now **refuses to publish an APK if the permanent signing secrets have not been configured**.

**One-time signing key setup on the repository owner's Windows 11 PC:**

1. Install [GitHub CLI](https://cli.github.com/) if necessary, e.g. `winget install --id GitHub.cli -e`. Open a new PowerShell window and run `gh auth login` using the GitHub account with repository Actions secrets access. Android Studio / Java must also be installed for `keytool`.
2. Download/run `android/scripts/setup-signing.ps1` from this repository. It creates a **private** PKCS#12 key at `Documents\\IR Remote Studio\\Signing\\ir-studio-android-test.p12` and uploads its Base64 content and your password directly as encrypted GitHub Secrets. The keystore and password are never committed to the repository.
3. **Back up the keystore file and its password offline** and do not share them in a public chat, GitHub issue, PR, or screenshot. If you lose this key, Android will not allow compatible updates signed by a replacement key.
4. Re-run the **Android prototype APK** workflow on the signing-enabled branch, or merge the signing change and build from `main`. Download the artifact named `IR-Remote-Studio-v0.4.2-alpha-Android-persistently-signed`.
5. **Before the one-time transition, back up your current app data outside the app:** In v0.4.1 go to **About / Version → Library backup... → Back up library**, save its JSON to Downloads or a cloud folder, and export any individual remotes as `.irplus`. Also export any available U1 waveform backups through **U1 Backups / Restore**. **Only then**, if Android rejects installing the new certificate over the existing APK, uninstall the old app and install v0.4.2. Restore the JSON library through **About / Version → Library backup... → Restore backup**.
6. Every **subsequent** build using that same key and an increasing `versionCode` should install over the prior app without clearing data. Never reset or regenerate the signing key just to make another APK.

**Run the script from PowerShell in the project root:**

```powershell
powershell -ExecutionPolicy Bypass -File .\\android\\scripts\\setup-signing.ps1
```

The script requires **GitHub CLI**, authenticates via `gh auth login`, prompts privately for a permanent password, and uses `keytool` from Android Studio or your Java installation. If it finds an existing key, it validates and **reuses** that same key.

**Not included:** This fixes Android **APK upgrade signing**, not the ability to write a restored waveform back into the SofaBaton U1's internal hardware programming (still tracked separately).

## v0.4.1-alpha: Backup picker repair

On Android, AlertDialog does not display its `setItems` list if `setMessage` is also used. In the previous alpha, **U1 Backups / Restore** showed only its explanation and **Close**, hiding all saved files. This build restores the selectable list and fixes the same issue in the **Restore into Android library** destination picker. If no backup files exist, the list explicitly says so. Nothing in the tested Bluetooth connection, learning, preflight backup, or IR export engines changed.
