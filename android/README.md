# IR Remote Studio for Android — v0.2.0-alpha

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
normal application signing and field testing. The USB lab enumerates Harmony devices, requests USB permission, inspects HID
interfaces and offers an experimental first-stage MH handshake. **IR capture is
not implemented**; Windows is still the learning station.

## Data, safety and privacy

The application requests only the normal Android `TRANSMIT_IR` permission.
Import/export uses Android's document picker. It needs no Internet, account,
telemetry or contacts access. A 2-second signal limit is enforced before IR
transmission. IR signals should only be used to control equipment you own
or are authorized to operate.

The Android app and Windows app both use the same `.irplus` interchange
format; Android does not yet read Windows library.json files directly.
\n## Import fix in v0.1.1-alpha

Android's XML parser does not support every JAXP configuration option used\nby Java desktop. The initial alpha enabled XML settings that could abort\nbefore reading valid Windows .irplus files. The importer now uses bounded XML\ninput and rejects DTD/entity declarations, while tolerating missing optional\nJAXP features. Failures now show the full reason in a visible dialog.

Update by installing the new debug APK over the original; existing local\nremote data remains stored under the same application ID.\n
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
or descriptor read does **not** constitute IR learning. The v0.1.2 USB discovery feature sent **no Harmony commands**;
later builds add separately confirmed protocol diagnostics, without changing
stored settings or firmware.

The USB diagnostics have no network access. Only the user can choose to copy
the report to the Android clipboard.

### Current priorities

Harmony USB IR learning on Android and a Concordance-independent Windows
learning backend are **equal** priorities. Cosmetic editing and new remotes
are deferred until the hardware experiments are validated.

## USB permission layout fix (v0.1.3-alpha)

The detection report now scrolls inside a height-limited panel; **Grant Harmony
USB access** is always above that panel when Android detects an eligible
device without permission. **Copy full USB report** stays below it. This
prevents the request button from being hidden below Android's navigation bar
on phones with large font/display settings. After approving USB access, run
Detect Harmony USB again and check that permission is Granted and opening
the device for descriptor inspection succeeds. This remains read-only:
no HID report transmission or IR capture is attempted in this version.

## Read-only HID claim and descriptor test (v0.1.4-alpha)

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

## MH full-response diagnostics — v0.1.7-alpha

The existing **Test Harmony MH handshake** test now logs all bytes of the
64-byte USB reply (16 bytes per line), the HID endpoint addresses, an observed
header-prefix comparison, and the last non-zero byte offset. Header comparison
is only a repeatability diagnostic, **not** proof of a valid protocol reply.
The five MH sync reports and final MH session-init request remain unchanged;
no configuration, firmware, reset, or IR-learning commands have been added.
Copy the result from the app, ideally from two separate runs, for comparison.


## Harmony MH session continuation — v0.1.8-alpha

A repeatable 64-byte reply with the first bytes FF FF 81 00 81 was observed
after the v0.1.7-alpha test. This is not proven to be a protocol success code.

The **Test MH session continuation** action repeats the earlier MH session
handshake and, **only if** a complete outgoing packet and a 64-byte reply with
the observed first five bytes are seen, sends Concordance's documented next
session packet: FF 00 02 (zero-padded to 64 bytes). The next 64-byte reply
is logged in full. The remote interface is always released afterward.

No /sys/sysinfo identity file, configuration file, flash memory, reset,
firmware operation, or IR-learning interface is accessed. The result is
a USB protocol experiment, not a claim of IR capture support.


## Read-only Harmony /sys/sysinfo — v0.1.9-alpha

After the observed FF 00 82 00 7D session reply, this experimental
option attempts a bounded metadata read of the published /sys/sysinfo
path. It follows Concordance's sequence of read request, transfer ACK,
limited data reads, and a transient sequence reset. Only firmware/hardware
metadata is displayed: fw_ver, hw_ver, arch, fw_type, and skin.

The app never logs the device's GUID, serial number, or unknown keys.
No IR-learning, configuration, flash, firmware, or user configuration
commands are issued. This is an experimental USB protocol test; a passed
handshake does not guarantee the metadata read will work. USB permission
must be granted and the device should have working batteries.


## MH sysinfo transfer correction — v0.1.10-alpha

Hardware testing of 0.1.9 demonstrated a 289-byte file descriptor response to
`/sys/sysinfo`, but the file-transfer ACK completed with an unexpected USB
byte count. Review against Concordance `CRemoteMH::ReadFile` identified a
bad local file-transfer sequence (previously read=03, ACK=04, close=05).

Version 0.1.10 incorrectly used read=00, ACK=01 and close=02 for
/sys/sysinfo. This was corrected in v0.1.12-alpha: Concordance's GetIdentity
calls ReadFile with start_seq=0x03 (read=03, ACK=04, close=05). It logs actual OUT and IN transfer byte
counts, accepts bounded short control and data replies (HID max packet size
remains 64), and validates each data packet length before reading its payload.

The target file remains strictly `/sys/sysinfo`, bounded to 1,000 bytes.
Only allowlisted non-identifying metadata is displayed; no IR learning,
stored configuration, firmware or flash operation is attempted.


## Stage-one USB timeout diagnostics — v0.1.11-alpha

The v0.1.10-alpha hardware report timed out during the first MH
initialization exchange, before reaching the earlier corrected sysinfo read.

This version preserves the five MH sync reports and exact initialization bytes,
but extends the initial reply wait from 4 to 6 seconds (Concordance MH_TIMEOUT
is 5 seconds). It reports separately whether the 64-byte OUT request completed,
whether the IN request completed, actual transferred byte counts, and whether
the USB wait itself timed out.

There is **no automatic retry** and no extra Harmony command. On an
incomplete first reply, the identity-file read is skipped and the USB
interface is released. For a repeat test, unplug and reconnect the Harmony
USB cable, leave batteries installed, then run the identity diagnostic once.


## Correct /sys/sysinfo file-transfer sequence — v0.1.12-alpha

**Correction to v0.1.10:** The MH file transfer does not universally start
at sequence zero. In `CRemoteMH::GetIdentity`, Concordance passes
`start_seq=0x03` to `ReadFile("/sys/sysinfo", ...)`. That function
increments the sequence for the read request, ACK, and transfer close:
**03 → 04 → 05**. v0.1.10-alpha erroneously changed those values to
00 → 01 → 02; this version restores the correct identity-read sequence
and updates the unit tests.

The stage-one USB timeout reported on v0.1.11 happened **before** this
file-transfer step. v0.1.12 retains v0.1.11's six-second, no-retry MH
handshake diagnostic. For a power-cycle, disconnect USB, remove batteries
briefly, reinstall batteries, reconnect USB and run the identity test once.
No firmware resets, configuration writes, or IR-learning commands are issued.


## Fix MH ACK and sequence-close packet length — v0.1.13-alpha

A hardware trace on v0.1.12 showed a successful 289-byte `/sys/sysinfo`
metadata announcement, followed by `sysinfo transfer ACK: OUT=true/8 bytes`.
The ACK prefix was correctly constructed (FF 04 04 02 ...), but the USB
report ByteBuffer was flipped after only 8 bytes. The MH protocol uses a
64-byte HID OUT report, with zero padding for short command prefixes.

This release zero-pads **all** MH sysinfo control commands to 64 bytes,
including the 8-byte ACK and 6-byte transfer-sequence close packet.
The 03/04/05 sequence numbers, bounded identity-only read, data-length checks,
serial/GUID redaction, and existing handshake remain unchanged.

Unit tests now specifically verify full-length zero-padding for the ACK and
session close, and that the already-full 64-byte read request stays identical.
The Android debug APK remains experimental: no IR capture, configuration,
flash, firmware or persistent device writes are attempted.


## Harmony MH IR receiving test — v0.1.14-alpha

With the Harmony 300 connected by USB to the Android phone and batteries
installed, the earlier v0.1.13 diagnostic successfully read the complete
289-byte `/sys/sysinfo` file, including firmware `1.4.0`, architecture
`0x10`, and a clean 64-byte close packet.

This build adds a **separate, explicitly confirmed** `Test IR receiving
(experimental)` action under Harmony USB discovery. After the verified MH
session handshake it attempts Concordance's transient `/ir/ir_cap` receive
sequence for architecture 16: an open/read packet, capture acknowledgement,
up to 10 seconds to press one button on another IR remote, followed by
explicit STOP (0x06) and sequence CLOSE (0x07) packets. The STOP/CLOSE
cleanup runs even when receiving fails after a confirmed open. All OUT
reports are 64-byte zero-padded HID messages.

The first version is **diagnostic only**. It reports packets, unprocessed
timing words and an estimated carrier frequency. It deliberately does not
store, transmit, replay, export, or reprogram any captured command. The
receiver listens only for a single press and caps the duration and number
of IR packets. No firmware, flash, configuration, or persistent writes are
attempted. If a test fails, disconnect USB and briefly remove/reinstall
the batteries before trying again; avoid repeated rapid test attempts.

This new functionality is independent of the existing **Read Harmony identity**
action, which remains available and unchanged.


## Longer bounded Harmony IR receiving diagnostic — v0.1.15-alpha

The v0.1.14-alpha hardware test successfully captured an estimated **38,173 Hz**
carrier and 33 raw IR timing words from another remote. The capture stopped at
the prototype's 16-report cap without seeing a capture-end marker. Because
some incoming IR reports can contain no additional timing words, that cap
cannot establish whether the command waveform was complete.

This release distinguishes USB IR reports **with timing data** from zero-word
reports and extends the cap to 128 reports, with an overall 15-second deadline.
After receiving timing data, 24 consecutive empty reports also end listening.
No automatic USB command retries are sent. The diagnostic records exactly
why listening stopped, elapsed time, data/empty report counts, and up to 128
raw timing words. It explicitly labels timing capture as unverified when no
end marker is seen.

The start, ACK, STOP and CLOSE packets remain unchanged; STOP/CLOSE are always
attempted after opening the transient IR receive session. As before,
no captured command is saved, replayed, exported or sent to the Harmony's
persistent storage.


## First repeat boundary and verified IR session close — v0.1.16-alpha

The v0.1.15-alpha hardware trace captured 128/128 IR reports in ~2.3
seconds, yielding 257 raw timing words at about **38,192 Hz**. The raw
timing output includes repeated header patterns `4006, 7995` preceded
by ~8,800 microsecond gaps. This suggests repeated copies of a button
transmission, possibly because the source remote's button remained held.

The IR receive diagnostic now uses a **conservative repeat-frame hint**:
it identifies the first reappearance of the leading header burst/period
after a long gap and stops gathering raw timing words at that boundary.
This is not proof of a complete signal and is never automatically saved
as a reusable IR command. If no repeat boundary appears, the 128-report
and 15-second limits still apply.

The cleanup now separately sends zero-padded 64-byte STOP and CLOSE OUT
commands and performs bounded IN draining for each. IR data reports
(`90 ..`) no longer count as STOP acknowledgement, and the IR end marker
(`F0 ..`) no longer counts as a verified CLOSE reply. The app looks for
the end marker after STOP and for the expected `FF 07 83 ..` reply
after CLOSE, logging how many stale packets were discarded. Both cleanup
phases have finite time/report limits.

To test, use a **quick tap and release** on the source remote pointed at
the Harmony's receiver. Avoid holding the button down. No saved remote,
flash or firmware is modified; this remains a diagnostic capture, not
a Pronto Hex export feature.


## Duplicate stage-one MH handshake reply — v0.1.17-alpha

A v0.1.16 IR test stopped *before activating the receiver* because
the response to the documented FF 00 02 session continuation was
FF FF 81 00 81 (a repeat of the previously seen first-stage response),
rather than the expected FF 00 82 00 7D.

Version 0.1.17 preserves **all existing USB OUT commands**. Only when
a complete continuation exchange returns that exact stage-one duplicate,
the app checks **one additional incoming 64-byte USB report** within the
original 4-second deadline. It does not resend the command and never
starts an IR capture unless the extra report matches the known stage-two
response. Unexpected replies and timeouts still fail closed.

The user interface now displays "Connecting to Harmony..." during the
MH handshake and shows a prominent READY prompt/Toast *only after* the
`/ir/ir_cap` receiver ACK succeeds. Tap and release the button on the
other infrared remote when that READY prompt appears, not before.
The 10-second window applies to the first IR receive report and is not
available when the initial MH handshake fails.

No configuration/firmware writes, automatic reprogramming or saving/
exporting of captured commands. Disconnect the Harmony's USB and briefly
remove/reinstall batteries if stale replies persist across tests.


## Experimental Pronto Hex candidate — v0.1.18-alpha

The v0.1.17-alpha test successfully collected a 57-word sample on the
Harmony 300: ~38,183 Hz, a probable repeat boundary at word 54, and a
verified IR STOP end marker and CLOSE response. The raw word stream
contains a complete-looking first frame before the repeated leading burst.

The app now optionally **previews a one-shot raw Pronto Hex (0000) code**
in the capture report only when the probable repeat frame boundary is
found and all marks/spaces are structurally valid. It takes only the first
frame before the repeat, converts the first mark+carrier-cycle count into
a Pronto frequency divider, turns each MH mark+total-period pair into
individual mark and space durations, and rounds the durations to carrier
cycle counts. The resulting code is validated by the app's existing
`IrplusCodec.decodePronto` before it appears in the report.

**This is an unverified candidate, not an automatically saved remote
command.** The capture results still require real device replay testing
before the signal is treated as reliable. Nothing is automatically
transmitted, written to Harmony, installed in its configuration, or
added to the IR Remote Studio library. Users can copy the diagnostic
text containing the candidate code.

Tests use the actual 57-word sample (26 timing pairs, Pronto frequency
divider 006D), and reject malformed frames, missing repeat boundaries,
and implausible carrier frequencies. The USB handshake, IR receiver
start/ACK, STOP and CLOSE implementation are unchanged.


## Learn & Library workflow — v0.2.0-alpha

The first successful Harmony 300 capture was decoded into a working Pronto
command and replayed successfully from the OnePlus 15. With this proof of
capture-to-replay, the app now offers a usable, opt-in **Learn IR Button**
workflow on its home screen.

1. Attach a supported Harmony 300/350 over USB and grant Android USB
   permission via **Harmony USB Connection & Diagnostics**.
2. Tap **Learn IR Button**, wait for the confirmed READY indication, then
   briefly press and release a button on another IR remote.
3. The app offers a validated raw Pronto 0000 candidate only when it
   recognizes a probable first frame *and* verifies STOP/DONE and
   CLOSE acknowledgments from the Harmony. Incomplete captures remain
   diagnostics only and cannot be saved via the learning flow.
4. **Test IR on target device** sends a single one-shot signal with
   Android's public ConsumerIrManager API, where available. This action
   is manual and does not alter the Harmony.
5. Name the new button, select an existing remote or create a new one,
   and tap **Save button** to persist the learned command on the
   phone's existing private `remotes.json` library.
6. Long-press a remote heading to rename/delete a remote, or long-press
   a button to rename/delete it; changes are subject to confirmation.
7. Use the existing **Export Remote** button to write an .irplus backup
   with the newly learned buttons; import remains supported.

This Android release includes a refreshed dark interface, a prominent
Learn button and new-remote action, accessible READY indication,
capture preview with test/copy/report controls, and rollback if a local
library save fails. The entire existing Harmony identity and advanced
USB diagnostic section is preserved.

**Safety:** learning uses only the transient Harmony `/ir/ir_cap` read
receiver; NO commands to modify its existing stored button mapping,
flash, firmware or persistent configuration. Code can be saved to the
phone only after successful protocol cleanup and explicit user action.
Replay requires a separate manual test or tapping a saved button.
