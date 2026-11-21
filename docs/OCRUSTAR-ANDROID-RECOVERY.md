# Ocrustar Android v0.5.4 recovery — confirmed findings

This file tracks recovery work on `ocrustar-v054-recovery`. **Do not merge into `main` or replace the user's installed v0.5.4 APK until a replacement build has been tested.**

## What has been proven on real hardware

- An orange-insert Ocrustar USB-C dongle **successfully learned an IR command and replayed it on a OnePlus 15**, using the manufacturer's Android app, through **Add New Remote → IR Learning**.
- The manufacturer's **Existing Remote → Add Key** learning path timed out on the same hardware.
- Therefore a missing or universally defective IR receiver, and generic Android incompatibility, are ruled out. The two UI flows may differ in session setup or state even though both ultimately use the same USB learning command.
- The target dongle's USB ID is **045C:0132**; observed bulk endpoints are **OUT 0x02 / IN 0x82**.
- Our v0.5.4 Android APK could enumerate/communicate with the dongle but IR capture timed out.

## Binary inspection

Sources examined: user's original official `Ocrustar-base.apk` and their `IR-Remote-Studio-v0.5.4-alpha-Android-persistently-signed.zip` (contains only `app-debug.apk`). **These binaries are not editable original source files.**

- The official APK calls `com.esmart.ir.IROTG.startLearnIr()` and `stopLearnIr()`.
- Learning start command: `FE FE FE FE`.
- Learning stop command: `FD FD FD FD`.
- Official USB implementation runs a continuous bulk-IN reader and dispatches decoded captures through `onReadLearnedIrData(byte[])`.
- Our v0.5.4 binary includes `OcrustarUsbLearner`, `OcrustarUsbReadPump`, and `OcrustarUsbProtocol`. It also sends the same start/stop learning commands and uses a continuous reader. **The exact cause of the timeout is not yet established.**
- v0.5.4 handshake logic distinguishes FC identity responses from FA firmware identity responses, and its parser handles FE capture headers. Verify its exact behavior against official code before changing device commands.

## Source provenance and safety

- The GitHub `android/` source currently corresponds to **v0.2.1-alpha** and does **not** include the v0.5.4 Android Ocrustar and SofaBaton implementations. A clean Gradle build of it does not recover v0.5.4.
- The user has cloned the repository locally under `C:\Users\mixte\StudioProjects\IR-Remote-Studio`, opened `android/` in Android Studio, configured JDK 17 and Gradle 8.13, and achieved successful Gradle sync.
- Recovery branch `ocrustar-v054-recovery` is created and pushed to GitHub. Original `main` remains untouched.
- Keep any experimental USB receiver as a **separately installable lab build**, with a distinct application ID, to protect the existing signed v0.5.4 install and its saved IR remotes.
- No firmware or configuration writes. Log packet hex and timestamps, limit capture duration, always attempt STOP after START, and close/release the interface.

## Next engineering verification

1. Compare the full official *working* learning flow with v0.5.4: USB session opening, claim interface, FC/FA handshake, initial draining, reader startup timing, receive-frame splitting, FE header/payload aggregation, cancellation, and error path.
2. Build a separate minimal Ocrustar lab receiver that records and displays raw responses for **045C:0132 / 0x82**, preserving both the success and timeout traces.
3. Test with the user's proven physical procedure: official app exited, OnePlus 15 + orange-insert dongle, Harmony/SofaBaton remote button, then inspect packet trace.
4. Only after capture is reproducible should the recovered backend be integrated into the wider app, preserving Harmony, SofaBaton, remote storage, UI functionality and signing.
