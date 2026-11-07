# IR Remote Studio for Android — v0.1.6-alpha

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
\n## Import fix in v0.1.5-alpha\n\nAndroid's XML parser does not support every JAXP configuration option used\nby Java desktop. The initial alpha enabled XML settings that could abort\nbefore reading valid Windows .irplus files. The importer now uses bounded XML\ninput and rejects DTD/entity declarations, while tolerating missing optional\nJAXP features. Failures now show the full reason in a visible dialog.\n\nUpdate by installing the new debug APK over the original; existing local\nremote data remains stored under the same application ID.\n
## Harmony USB lab — v0.1.5-alpha

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

## USB permission layout fix (v0.1.5-alpha)

The detection report now scrolls inside a height-limited panel; **Grant Harmony
USB access** is always above that panel when Android detects an eligible
device without permission. **Copy full USB report** stays below it. This
prevents the request button from being hidden below Android's navigation bar
on phones with large font/display settings. After approving USB access, run
Detect Harmony USB again and check that permission is Granted and opening
the device for descriptor inspection succeeds. This remains read-only:
no HID report transmission or IR capture is attempted in this version.

## Read-only HID claim and descriptor test (v0.1.5-alpha)

After attaching the Harmony and granting USB permission, **Detect Harmony USB**
shows **Test HID interface (read-only)** for VID:PID 046D:C124.

The test attempts to claim the HID interface with `force=false` (never detaches
kernel drivers). If successful, it requests the standard HID **report
descriptor** using a USB `GET_DESCRIPTOR` control IN request, and releases
the interface immediately. It never sends Harmony application commands or
configuration writes. If claiming fails, the report will tell us that the
system HID driver may own the interface; it does not attempt to override it.

Copy the results and provide them to the developer. HID interface access
alone does not mean IR learning has been implemented.

## Harmony version query (v0.1.5-alpha)

Following successful HID interface/descriptor testing, v0.1.5 adds an
optional **Read Harmony identity (0x10)** button.

It confirms before sending one 64-byte **GET_VERSION** USB output report,
reads a 64-byte response via the HID input endpoint, then releases the HID
interface. This is a **read-only protocol request** (although it necessarily
transmits a USB OUT packet). The command does not alter the firmware, flash
memory, configuration, or stored remote programs.

The request and its expected version response are documented by Concordance
upstream in `libconcord/protocol.h` and `libconcord/remote.cpp`. The Android
code uses the published hardware protocol, not copied Concordance source code.

The button is shown only when VID:PID is **046D:C124**, Android permission is
granted, and the HID endpoints match the expected 64-byte form.

If it succeeds, copy the **Harmony identity result** and share it with the
developer. If it fails, the report gives the exact stage. The test remains
separate from IR learning; it does not capture commands.

## MH protocol correction — v0.1.6-alpha

**Important correction:** `046D:C124` (Harmony 300) is classified by
Concordance as **CRemoteMH**, not the legacy **CRemote** family. The earlier
v0.1.5 `GET_VERSION (0x10)` query was therefore the wrong protocol and timed
out. Do not interpret that timeout as a USB hardware failure.

The old "Read Harmony identity (0x10)" action has been **removed**. The
replacement is **Test Harmony MH handshake**, a first-stage experiment that
performs only the documented MH session initialization and reads its initial
acknowledgement. It is **not** yet the full `/sys/sysinfo` identity retrieval,
nor is it IR learning.

With the remote attached and USB access granted, choose **Detect Harmony USB**,
then **Test Harmony MH handshake**, confirm, and copy the result. The diagnostic
queues its initial IN read before the final initialization request and performs
bounded USB interrupt transfers (no forced interface takeover). On timeouts or
disconnects it closes and releases the USB device.

The handshake changes only the transient USB session state. No flash memory,
firmware, configuration, IR-learning commands, or reset packets are issued.

Reference: Concordance `libconcord/libconcord.cpp` (`is_mh_pid` and
`init_concord`) and `libconcord/remote_mh.cpp` (`CRemoteMH::GetIdentity`).
The new code is independently written using published protocol facts.
