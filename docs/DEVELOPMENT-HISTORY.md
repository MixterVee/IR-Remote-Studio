# IR Remote Studio — development notes prior to 1.0

This file preserves the project's original pre-1.0 README and experimental progress notes for historical reference. **Many details below describe obsolete user interfaces and beta versions** and should not be used as current installation instructions. For current Windows instructions, refer to the repository root README and WINDOWS-SETUP.txt.

---

# IR Remote Studio

**Portable IR learning and remote management for Windows, with an experimental Android companion.**

Create devices, use custom labels for every button, capture commands from a compatible Logitech Harmony IR learner, compare repeated recordings, and export **`.irplus`** remotes for compatible Android phones.

> **Status (November 2026):** Windows v0.1.7 is the established GitHub release.
> **Windows v0.2.8-beta** builds on direct USB learning backends for Harmony 300/350
> (shared HID ID 046D:C124) and a documented Ocrustar/ElkSmart D226/D552
> learning family (045C:02AA). Both Windows USB transports need real-device
> validation before they can be treated as production-ready. The existing
> Concordance/PowerShell backend remains selectable. Android v0.2.1-alpha
> has passed real-world Harmony 300 learning, saving, .irplus export and
> replay in IRPlus.

## Download Windows

For the tested Windows v0.1.7 release, use
[GitHub Releases](https://github.com/MixterVee/IR-Remote-Studio/releases).

For the **experimental v0.2.8-beta direct USB learner**, obtain the
Windows ZIP from the latest successful
[Windows Actions build](https://github.com/MixterVee/IR-Remote-Studio/actions/workflows/windows.yml).
Extract the ZIP and launch the standalone \`IR-Remote-Studio.exe\`.
Python is not required to run the packaged executable.

In v0.2.8-beta the Windows learning panel provides **Save** to
confirm the selected command is saved without starting another capture, and
**Save & Learn another** to save and continue learning. Captures remain
autosaved immediately after successful receiving, as in the previous version.

The **IR LEARNER CONNECTION** panel now has prominent **Refresh USB**
(read-only enumeration) and **Connect / Retry** (protocol handshake)
controls. **Red** indicates NOT CONNECTED/FAILED, **amber** means
USB detected or connecting, and **green** indicates a verified
READY handshake or a receiver actively armed for capture.
On connection failures, a popup recommends unplugging/reconnecting the\nreceiver or trying another USB port, followed by Refresh USB and\nConnect / Retry. Diagnostic details remain available.\nUSB handshake checks open and release a session; green after a
successful check is the **last verified state**, not a promise that a
permanent USB connection remains open. Green **READY — PRESS A BUTTON
NOW** is shown only while a capture session has armed the receiver.

Use the **IR learning hardware** selector to choose:
- **Auto detect supported learner**: read-only detection of registered
  device adapters; asks you to select if more than one is connected
- **Harmony 300/350 — standalone HID**: built-in MH protocol, with no
  Concordance DLL required
- **Ocrustar / ElkSmart — USB bulk (experimental)**: separate protocol
  adapter for VID:PID 045C:02AA; *38 kHz carrier is assumed, not measured*
- **Concordance — legacy DLL**: original external libconcord/x86
  PowerShell backend, supporting the broader documented Harmony list

**USB IR dongles are not interchangeable.** Unknown dongles need their
own verified adapters; no learning commands are sent to unrecognized
hardware. Windows USB driver installation/replacement is never performed
automatically. Follow \`WINDOWS-SETUP.txt\` and keep a library backup.

## SofaBaton U1 Bluetooth — stored IR read-back (Windows beta)

The SofaBaton U1 learns IR using its own receiver, controlled by the
official Bluetooth-connected app. In November 2026, a Pixel HCI capture
revealed its proprietary A5 5A packet framing and allowed reconstruction
of two infrared recordings which successfully played in IRPlus.

In Windows v0.3.1-beta, **SofaBaton U1 Bluetooth...** offers:

- BLE scanning and read-only GATT structure inspection (same as v0.2.5).
- **Advanced → Read stored IR (test)**: for a positively identified U1, query a
  previously learned IR command from an occupied slot (1–8), initially
  try slot 7 or 8 from the verified recording.
- **Advanced → Save to selected button**: save the validated read-back IR timings
  directly to the selected device/button in IR Remote Studio's local
  library. Export that device as .irplus for replay testing.

This *read-back* operation sends only observed official-app **device
status inquiry (0x01)** and **stored IR read inquiry (0x0C)** frames.
It verifies exact U1 FFF0/FFF1/FFF2 characteristic UUIDs, checks all
Bluetooth frame checksums and fragment ordering, assembles the complete
IR waveform, and validates aggregate checksum, carrier and durations.
It **does not initiate new IR learning or send U1 profile/firmware
configuration writes**. Malformed/incomplete reads are rejected, and
the local library changes only when you explicitly choose Save.

To test: close the SofaBaton phone app; put batteries in the U1 and
open the BLE window, scan and select the identified SB-prefixed U1.
Open **Advanced**, choose a populated slot, click **Read stored IR (test)**, confirm,
select a device/button in the Windows main app, and choose
**Save to selected button**. Verify playback after .irplus export.
This experimental Windows BLE read-back path needs physical testing
and is not yet a native live Bluetooth IR learner.

## Android Learn & Library (v0.2.1-alpha)

Android's working Harmony 300 IR capture pipeline can learn a button,
preview/test it using the phone's IR hardware, save named buttons to a
remote, import and export .irplus, and **Save & Learn Another**. On actual
hardware, users have confirmed exporting an expanded .irplus profile,
importing it into IRPlus and replaying successfully.

Download a successful build from
[Android Actions](https://github.com/MixterVee/IR-Remote-Studio/actions/workflows/android.yml).
This is a debug-signed test APK, not a Play Store release. See
[android/README.md](android/README.md).

## Features in Windows v0.1

- Unlimited devices, custom button labels, button ordering, rename/delete.
- Capture IR commands with documented-compatible Harmony remotes in an isolated helper process (no firmware/configuration writes).
- Reject incomplete NEC repeat-only signals, validate carrier and mark/space durations.
- Decode standard and extended NEC when supported; show address, command and bytes.
- Save multiple captures for each button and identify matching NEC frames.
- Import previous Harmony capture JSON and existing PRONTO_HEX `.irplus` devices.
- Export whole devices to `.irplus` with optional NEC repeat frames.
- Autosave human-readable library JSON; make a manual library backup and restore with an automatic pre-restore safety copy.
- Inspect all captures for a button and choose the specific sample to export.
- Edit per-button widths and preview the 12-column grid used by .irplus (2, 3, 4, 6, or 12 column buttons).
- Logs for native USB operations.

### Quick start

1. Run the app; choose **+ Add** under **Devices**, enter a name such as `Kitchen Clock`, manufacturer `Xflyee`, model `099`.
2. Choose **+ Add** under **Buttons** and type your own label, such as `SET`, `Alarm`, `HR`, `MIN`.
3. Click **Check Harmony IR learner**, then select a button, click **Capture selected button** and press its original remote button.
4. Repeat the capture twice for comparison. A `2/2` count means the samples agree at the decoded NEC command level.
5. Select **Export .irplus**, then import the file into `irplus` on Android.

**Previously verified SET command:** `06 AB 01 FE` (extended NEC, address `0xAB06`, command `0x01`). See `examples/` for two matching captures, one repeat-only sample and the known-working `.irplus` remote. The compatible Android phone transmitted the SET command successfully in prior testing.

## Build locally

- Windows 11 x64; Python 3.12 or later with Tkinter.
- Run: `python run.py`
- Tests: `python -m unittest discover -s tests -v`
- Build EXE: `python -m pip install pyinstaller==6.16.0` followed by:
  `pyinstaller --noconfirm --onefile --windowed --name IR-Remote-Studio --add-data "irstudio/Xflyee_IR_Worker.ps1;." run.py`

GitHub Actions builds and uploads the executable on every push to `main`.

## Hardware and safety

The Windows beta offers a direct HID backend for Harmony 300/350 and an independent Ocrustar USB bulk backend. Both are hardware-gated and experimental on Windows; the Harmony 300 MH protocol is already proven on Android. The optional legacy Concordance mode still invokes the existing native DLL through a separate 32-bit PowerShell process. Never run other USB remote-control software simultaneously. Inspect USB IDs and drivers before attempting untested dongles.

New raw formats are preserved in the local library even when a protocol decoder does not recognize them. Repeat-only NEC captures are rejected rather than falsely marked valid. Other protocols are marked raw/unidentified until verified.

The project does not ship Logitech firmware or Concordance DLLs. The existing installed native library is used only on the user's machine.

## Roadmap

- More learning hardware and protocol decoders, editing capture selection and raw timing inspection.
- Dedicated Android app to import/organize/edit/transmit remotes with a built-in IR emitter.
- Test USB-C dongle learning on Android (device model/protocol must be verified).
- Additional export formats and configurable remote-grid layouts.

## Licensing

IR Remote Studio's original source code and included generated artwork are published under the **MIT License** (see [LICENSE](LICENSE)). Its optional external Harmony learning dependency, **Concordance/libconcord**, is a separate GPLv2 project; its binaries are **not** included. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Harmony IR-learning compatibility

IR Remote Studio uses the IR-learning API in an existing local installation of
[Concordance](https://github.com/jaymzh/concordance). Models are **recognized by exact
model name** to avoid initiating an unsupported capture on a device that cannot learn IR.

**Documented by Concordance as working:** Harmony 200/200i, 300/300i, 350,
515, 520, 522, 525, 550, 552, 555, 600/600i, 628, 650/650i, 655,
659, 665, 670, 675, 676, 680, 688, 720, 745, 748, 768, 785,
880/880 Pro, 882, 885, 900, One, and Link/Link (EMEA).

**Experimental:** Harmony 700/700i (Concordance says *believed working*).
Capture twice and compare the signals before trusting an export.

**Not enabled:** Harmony 800 prototype; 890/895; 1000/1100 and Xbox models;
Touch, Ultimate, Ultimate One, 950 and Hub-based models. Their learning function is
either documented as unsupported or not verified by this backend.

The compatibility registry lives in `irstudio/harmony_models.json`. The application
uses Concordance's detected model name rather than guessing from USB identifiers.

**Important:** 'Documented working' describes Concordance's own compatibility
table, not an end-to-end hardware test for every model in IR Remote Studio.
Harmony 300 and 350 have both been directly verified by device owners in end-to-end capture and playback testing.
A compatible learning remote is only used to *receive* IR codes; normal capture
does not rewrite its configuration or firmware.

## Windows v0.1.7 improvements

- **Remote layout editor:** choose a button, set its width, and preview how it fits on a 12-column irplus grid. The saved width is used in .irplus exports and preserved when compatible files are imported.
- **Capture history:** review all stored recordings of a button, choose the sample used for export or delete a bad sample. Existing automatic best-match selection is retained.
- **Library recovery:** File → Back up library saves a JSON copy; File → Restore library backup validates the input and creates a timestamped safety backup of the current library before replacing it. Backups live under Documents/IR Remote Studio/Backups.
- **USB troubleshooting:** connection errors now remind users to check Harmony batteries, USB cable, and port.
- **Neutral labels:** exports refer to a compatible phone rather than a specific device model.
- **Window resizing:** capture controls stay accessible in the lower panel.

**Hardware verified in field testing:** Harmony 300 and Harmony 350 both completed USB connection, IR learning, .irplus export and playback from a compatible phone. Other documented-compatible models are not individually hardware verified.

**Planned, not yet delivered:** Android app, additional protocol decoders, and multi-button drag/drop layout.

## Windows v0.1.5 — irplus layout fix

The Windows preview and exported .irplus file now use the same 12-column row wrapping.
When a button cannot fit in a partially filled row, the exporter inserts an
irplus-supported `<space multiple="N" />` placeholder to finish that row.
It also fills the last partial row. This fixes the phone clipping / horizontal
row overflow observed with a 6-column button followed by a 12-column button.
Re-export and re-import the file to see the fix; existing captures are unchanged.

## Windows v0.1.6 — phone-style layout editor and capture visibility

- **Phone Preview** (default): a scrollable portrait phone mockup with a remote title,
  dark/light themes, screen-size choices, text-size choices, and selection highlighting.
  This visual preview is approximate; Android irplus controls its own typography.
- **Grid Preview**: switch to a labeled 12-column layout for debugging column spans.
  Both previews use the same `core.layout_rows` planner as `.irplus` export, including
  the automatic row wraps and omission of unlearned buttons.
- **Capture checklist:** the button list now distinguishes *Not learned*, *Captured
  once*, *Verified* (two or more matching captures), *Mismatch*, and *Imported*.
  Verification means matching recordings, not independently verified playback.
- **Import/export labels:** Import IR Capture (.json), Import Device (.irplus),
  and Export Device (.irplus).
- **Export confirmation:** shows device name, number of exported buttons, skipped
  unlearned buttons, and destination file.

The Android application itself remains future work. The Windows phone preview
is a visualization, not a phone emulator or IR transmitter.

## v0.1.7 branding update

- Bundles the approved IR Remote Studio remote-and-brush logo (transparent PNG) with
  the Windows application. The same artwork appears in the app header and window icon.
- Windows builds generate a multi-size `.ico` using Pillow and embed it into the
  `.exe` using PyInstaller's `--icon` option.
- The branding asset is stored under `assets/ir_remote_studio.png`; source-mode
  runs and packaged builds load the same image. A failed icon load does not prevent
  IR Remote Studio from starting.
- This is a **branding-only beta** with no changes to Harmony capture, exported
  signals, or the library file format. The first GitHub Release is v0.1.7 (public beta).

## Current development priorities

Android Harmony 300/350 **USB IR learning** and an independent **Windows
standalone learning backend** are equal priorities. The Android USB detector
is an experimental read-only diagnostic; it is not yet a learning feature.
See [the engineering milestones](docs/ENGINEERING_PRIORITIES.md).

### Laptop display layout fix (Windows 0.2.8-beta)

On shorter laptop screens or Windows desktops with a visible taskbar,
the main window reserves bottom space for Devices and Buttons action
controls rather than letting the list/tree consume the remaining area.
Likewise, the Layout Editor pins Save/Cancel and width controls above
the work area. CI tests these controls at 610px / 540px viewport heights.

### Windows 0.2.8-beta: U1 Bluetooth connection and readiness

Use **SofaBaton U1 Bluetooth... → Scan Bluetooth → Connect U1**.
The application verifies the real U1 FFF0 BLE service and its identity
response before closing the dialog and displaying **green CONNECTED —
READY (SB...)** on the Windows main window. Read stored IR (test) reuses
this active link if you reopen the dialog, rather than disconnecting
and reconnecting for each read. The main window offers **Disconnect
U1**; unexpected BLE loss immediately clears the connected state.
USB readiness is shown separately from Bluetooth readiness.

Windows OS pairing is optional and OFF by default because this tested
U1 supports direct connection without pairing. A **Request Windows
pairing (only if needed)** checkbox is available during Connect; use
only after normal connection fails. Pairing can prompt for user input.
There is no U1 firmware/profile programming, nor new live IR learning.

### Windows 0.3.1-beta: simplified SofaBaton connection screen

The normal U1 window now focuses on **Scan Bluetooth**, **Connect U1**,
**Disconnect**, a high-contrast connected indicator and the identified
remote list. It presents a disabled **Learn IR** action with explicit
explanation: direct SofaBaton U1 learning has **not** yet been implemented
or safety-validated. This is not a working learn function.

Choose **Advanced ▸** only when you need existing stored-IR slot
read-back, Save to selected button, GATT inspection, optional Windows
pairing or diagnostic reports. The Advanced area is hidden by default;
opening it never sends Bluetooth commands. Existing proven stored-code
reads continue to use the persistent connected session. Main-window USB
capture is labeled **(USB)**, separately from the U1 Bluetooth status.
All U1 programming and firmware writes remain disabled.

### Windows 0.3.1-beta: one connection indicator

The main window now uses a **single compact connection line** below the
receiver controls. It displays **red NOT CONNECTED** when no learner is
verified, or **green CONNECTED — device name** when a U1 Bluetooth link
or supported USB learner has been verified. The connected BLE U1 takes
priority over irrelevant USB not-connected/failure messages. An available
USB learner is shown if the U1 disconnects and USB verification is still
valid. Temporary connecting operations use amber instead of falsely
claiming connected. The U1 Disconnect button occupies the same status
row only while a live U1 session exists.

Note: unlike U1's persistent BLE connection, a USB connection
verification is a successful handshake; the USB handle closes between
learn operations and reopens when capture begins. As before, a hot
unplug requires Refresh USB / Connect to update that state. Protocols
and stored IR behavior are unchanged.

### Windows 0.3.1-beta: automatic U1 discovery and connection

Click **SofaBaton U1 Bluetooth...** from the main window. The popup
immediately shows the pairing-mode instructions (fit batteries; press
and hold **-** and **E** until **Pairing** appears; close the official
SofaBaton phone app), then **starts BLE scanning automatically**.

When exactly one advertisement has an U1-style `SB<hex serial>` name,
the app selects it, opens a persistent BLE connection, checks the
expected FFF0/FFF1/FFF2 GATT interface, sends only the previously
verified identity inquiry, and automatically closes the popup to
return to the main window with green **CONNECTED — SofaBaton U1 (...)**.

For an ordinary connect, Windows OS bonding is not necessary and is
not requested, retaining the known-working U1 path. If the device
explicitly requires Bluetooth authentication, the app automatically
retries using Windows pairing, which may show a Windows confirmation
dialog. Auto-connection never sends U1 IR learning/programming writes.
A matching name alone is insufficient to report connected: GATT and
the U1 identity response must both verify first.

If multiple candidate U1 advertisements are found, the app does not
choose blindly: select the correct device and use **Connect U1**.
If no device is found or the link fails, follow the visible instructions
and choose **Scan again**. Reopening the popup while already connected
does not rescan; **Advanced** remains available for stored-code reads.

### Windows 0.3.2-beta: disconnect button immediately beside U1 status

The main window places the **Disconnect U1** button just to the right of the
single green **CONNECTED — SofaBaton U1 (SB...)** line, rather than pushing it
to the far right edge. The button now uses a contrasting red background and
white label. It disappears when the BLE link is no longer connected. This is
a visual-only change; BLE pairing, reading stored IR and USB capture remain
unchanged.

### Windows v0.3.3-beta: responsive action buttons and high-risk U1 live IR experiment

**Narrow Windows layout fix:** At laptop widths the Devices and Buttons
action bars now wrap their controls into two columns so **Delete** and
**Layout editor** no longer disappear beyond the right-hand pane edge.
When there is enough width, buttons return to three columns. This
preserves the vertical-taskbar sizing fix.

**Experimental U1 live learning, NOT production-ready:** The normal
**Learn IR** remains disabled because the U1 learning sequence has not
yet been verified on live hardware. A guarded
**Advanced → Experimental: learn IR in slot 8 (risk of overwrite)**
operation is now available after connecting a verified U1. The
only learning sequence currently implemented is the exact one
observed in the official SofaBaton phone app's captured BLE exchange:

- Recorded start: `a5 5a 09 04 08 18 02 00 00 00 00 00 2e`
- Observed ready acknowledgment: `a5 5a 00 04 03`
- Observed capture event: `a5 5a 01 06 00 06`
- Strict ordered and checksummed IR waveform fragments use `0x0d`.
- Recorded finish/exit: `a5 5a 01 05 01 06`

The app **refuses to start** until it successfully reads slot 8,
writes a timestamped JSON backup to `Documents/IR Remote Studio/
U1-Learn-Backups`, fsyncs it, and verifies it is importable. The
user must then accept a warning and **type LEARN 8**. The experimental
learning operation may overwrite U1 slot 8 and alter an unknown
physical key/device assignment. The local IR backup **cannot be
automatically restored to the U1**. The test does NOT send unknown
0x07/0x08/0x14 configuration/firmware instructions; it uses
only the observed start/finish commands and record stream. A successful
capture still requires selecting the destination button and pressing
**Advanced → Save to selected button** to save it in the local
library. Cancel, failed backup, and invalid reply never send start.

**Important:** The tested protocol slot range 1–8 is an application
restriction, not a documented limit of the U1 or a known 1:1 mapping
to physical buttons. Real-hardware validation remains outstanding.

### Windows v0.3.9-beta: verified v0.3.3 backend, main-screen capture

On Nov 9, a fresh physical U1 test of Windows v0.3.3 **learned four
IR commands**, exported an `.irplus` remote and verified the commands
worked in IRplus. This demonstrates the original U1 capture pathway
still functions with the user's current remote.

Unlike attempted v0.3.4–v0.3.8 revisions, v0.3.9 **branches directly
from v0.3.3** and preserves both the original U1 BLE session and the
original connection dialog `_run` task-scheduling path. The dialog
runs withdrawn (not visible) during learning, allowing the user to
select any device/button and **Capture directly from the main screen**.
The old LEARN 8 typed-confirmation UI is removed entirely. A completed,
validated capture autosaves to the original selected library button
and can be exported to IRplus. Explicit USB selection still uses USB.

The preflight reads and backs up the previous slot-8 waveform as before;
the stored U1 profile cannot be restored from it. No speculative
0x14/0x07/0x08 configuration commands or altered BLE parsers were
added. Automated tests exercise the original task route and main
library autosave. Physical U1 validation of the *new UI flow* remains
required; success with the same backend in v0.3.3 is not proof of
success in v0.3.9.

### Windows v0.3.10-beta — disk-verified autosave and mismatch recovery

When the original v0.3.3 U1 backend completes a validated learning
operation, the main screen atomically saves the IR capture into
`library.json`, **reads it back and checks the same sample exists**
before confirming success. It then offers **Learn another NEW button**
or **Finish learning** (leave U1 connected). A save failure rolls the
sample back in memory and never displays a success confirmation.

An unrecognized waveform is now marked **Raw IR (saved)** instead of
the contradictory **Not learned**, because many legitimate captured
remotes are not NEC-decodable but export as Pronto to IRplus.

For short-press mismatches, **Remove last** undoes the newest sample
without deleting the button. **Clear captures for selected button...**
removes all its captured samples with confirmation while preserving
its label and layout. Then select the same button and capture again.
Neither correction sends BLE commands to the U1 or changes its
programming. Both modifications save the library immediately; a failed
disk save restores the previous list in memory.

The original v0.3.3 U1 BLE session, learning coroutine and original
dialog worker are unchanged. Physical confirmation on the main-screen
workflow still matters.

### Windows v0.3.11-beta — learn into any existing library button

The successful U1 autosave dialog now has **three explicit choices**:
**Learn selected existing**, **New button**, and **Finish**.
A dropdown lists all existing buttons of the just-saved remote,
defaulting to the button just learned. Choosing an existing button
selects it on the main screen and uses the same Capture pathway as
before, adding another sample without creating or deleting its label,
layout or other samples. To replace old/mismatched samples first, use
**Clear captures for selected button**.

**New button** uses the existing naming workflow. **Finish** (also
window Close) ends this learning sequence while keeping U1 connected.
Only actions chosen by the user can trigger additional learning.
The known-working v0.3.3 U1 Bluetooth learning engine is unchanged.

### Windows v0.3.12-beta — layout editing belongs in Layout Editor

Per user request, the main **Buttons** area is about adding, renaming,
deleting, selecting and learning IR commands. **Move Up** and
**Move Down** no longer clutter the main window. One **Layout editor...**
entry provides appearance configuration.

Inside the Layout Editor, **Move Up / Move Down** are directly next to
the existing width controls. Reordering and width changes are **staged**
until **Save layout**; the Phone/Grid preview uses the staged order.
Cancel discards both types of change. Save commits both in one atomic
library write, with rollback of the in-memory original layout if disk
saving fails. The order and widths determine the resulting `.irplus`
export, without touching captured IR codes, devices or U1 programming.

The original v0.3.3 physical-U1-tested Bluetooth capture engine and
the v0.3.11 post-capture choices (Existing/New/Finish) remain unchanged.

### Windows v0.3.13-beta — remember Layout Editor placement

The Layout Editor no longer reappears in the desktop's top-left corner.
It remembers **last position and size** when closed via Save layout,
Cancel, or the title-bar X, and restores them even after restarting
IR Remote Studio. Placement is stored independently in
`Documents/IR Remote Studio/layout-editor-window.json`; it is a
UI preference, not part of the device library.

Multiple Windows monitors are supported, including monitors with
negative virtual-desktop coordinates (e.g. left of the primary).
If a monitor is later disconnected or saved placement is invalid,
the editor reopens centered over the main application on a visible
monitor. No Bluetooth, button order, raw IR captures or .irplus export
logic changed. GUI tests cover persistence, invalid/offscreen fallback,
and separation from saved remote data.
