# Ocrustar IR Lab (separate diagnostic APK)

**Status: untested on hardware, source-only.** The Android module has been added
on branch "ocrustar-v054-recovery". It must be built in Android Studio before use.

This is a **separate Android application** with package ID
"com.mixtervee.ocrustarlab". It **does not replace** the signed IR Remote Studio
v0.5.4 app ("com.mixtervee.irstudio") or read its saved remotes.

## Safety and scope

- Accepts ONLY USB VID:PID **045C:0132** with BULK OUT **0x02** and IN **0x82**.
- Device scan and endpoint discovery are read-only.
- "Learn IR — handshake + capture" sends only FC x4 (hello), optionally
  FA x4 (reply acknowledgement when an FC/FA greeting is received), FE x4
  (learn start), and FD x4 (learn stop).
- "Alternative: Learn IR — no handshake" sends only FE x4 and FD x4.
- Stops after a maximum of 15 seconds. STOP is attempted in a finally block
  even if capture fails. No firmware, configuration, or transmission commands.
- All USB activity is isolated to a worker thread; the UI presents timestamps
  and **exact raw incoming USB bytes in hex**.
- No network permission, no file access, no transmission permission, no analytics.

### First test (OnePlus 15)

1. Exit/force-stop the official Ocrustar application so it releases USB.
2. Install **ocrustarLab** from Android Studio, **without uninstalling v0.5.4**.
3. Insert the orange-insert Ocrustar dongle.
4. Start **Ocrustar IR Lab**, tap **Scan USB**. Confirm 045C:0132 and IN/OUT.
5. Tap **Learn IR — handshake + capture**, and approve the Android USB prompt.
6. While the app is listening, aim your Harmony/SofaBaton remote at the IR
   receiving end and press a button as with the official successful procedure.
7. Tap **Copy full report**, paste its contents in ChatGPT.
8. If the first test returns nothing, try **Alternative: Learn IR — no handshake**
   as a *separate* test, then copy that report. Do not use both simultaneously.

The raw logs can identify which packet is or is not received; they do NOT by
themselves prove why the official app succeeds. Do not interpret a timeout as
a broken receiver: the official app already confirmed learning and replay.

### Building on the already configured Windows machine

1. In PowerShell, change to the project's root:
   cd "$env:USERPROFILE\StudioProjects\IR-Remote-Studio"
   then run:
   git pull --ff-only
2. Open the "android" Gradle project in Android Studio.
3. Wait for Gradle sync; select **ocrustarLab** (not **app**) as the run
   configuration. Choose a real Android device; an emulator cannot interface
   with the USB-C dongle.
4. Build/install the diagnostic; unplug the phone's data cable and connect
   Ocrustar before running the IR learning test.

The host Android Studio installation already uses JDK 17 and Gradle 8.13;
this lab shares the existing Android Gradle Plugin 8.13.2 build setup.

## Limitations

The official Ocrustar SDK has an internal read loop and framing rules not
fully verified against v0.5.4. This diagnostic logs raw USB packets; it does
not yet reconstruct full IR waveforms or replace production learning code.
The handshake is experimental and is deliberately limited to the small
known FC/FA/FE/FD command family.
