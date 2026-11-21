# Ocrustar IR Lab (separate diagnostic APK)

**Status: v0.1 has successfully captured a 552-byte IR payload on a real OnePlus 15. v0.2 decoder remains to be verified on hardware.** The Android module has been added
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
- Stops after a maximum of 15 seconds or when a complete FE capture arrives, whichever occurs first. STOP is attempted in a finally block
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
fully verified against v0.5.4. This diagnostic logs raw USB packets; it now reassembles complete FE-framed IR payloads and reports tentative 16-us timings, but does
not yet verify their carrier or waveform against playback or replace production learning code.
The handshake is experimental and is deliberately limited to the small
known FC/FA/FE/FD command family.


## v0.2: Successful hardware test and reassembly

The user's 045C:0132 orange-insert dongle, connected to a OnePlus 15, returned:

- HELLO: `FC FC FC FC` => `FC FC FC FC 70 01`.
- HELLO ACK: `FA FA FA FA`.
- LEARN START: `FE FE FE FE`.
- `FA FA FA FA 22 01` status packet.
- IR frame header: `FE FE FE FE 02 28` => 552-byte big-endian payload.
- Nine IR USB reads totaling 558 bytes, including the 6-byte frame header.
- A separate 6-byte status packet, resulting in ten reads and 564 total USB bytes.
- Final LEARN STOP `FD FD FD FD`.

v0.2 verifies the declared payload size, joins fragments, reports tentative timing statistics, and stops as soon as all 552 bytes have been received. This is a host-side parsing experiment, not an automatic replay implementation.

## v0.2 field test — OnePlus 15, November 21, 2026

Screenshot verified the **handshake + capture** test with the orange-insert 045C:0132 dongle:

- HELLO reply `FC FC FC FC 70 01`, then acknowledged with `FA FA FA FA`.
- LEARN START sent, then status `FA FA FA FA 22 01` received.
- First data packet `FE FE FE FE 02 28 ...` (552-byte declared payload).
- Reassembler reported `COMPLETE IR PAYLOAD: 552 / 552 bytes` and `extra bytes=0`.
- Observed ten USB read packets totaling 564 bytes: 6-byte status plus 558-byte framed capture.
- Tentative timing interpretation: **520 durations**, 32 overflow bytes (`FF`), 0 trailing overflow bytes, all 520 durations within 16–200000 μs, maximum 58992 μs, aggregate timing 691920 μs.
- Automatic STOP `FD FD FD FD` sent at ~7.67 s; USB interface closed.

**Next evidence needed:** complete copyable hex report from the lab app to independently validate format, repetitions, and carrier assumptions, then demonstrate replay against a real device. The 16-μs timing model is provisional; successful capture does not yet prove correct IR playback.
