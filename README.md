# IR Remote Studio

**Portable IR learning and remote management for Windows, with an experimental Android companion.**

Create devices, use custom labels for every button, capture commands from a compatible Logitech Harmony IR learner, compare repeated recordings, and export **`.irplus`** remotes for compatible Android phones.

> **Status (November 2026):** Windows v0.1.7 is the established GitHub release.
> **Windows v0.2.2-beta** builds on direct USB learning backends for Harmony 300/350
> (shared HID ID 046D:C124) and a documented Ocrustar/ElkSmart D226/D552
> learning family (045C:02AA). Both Windows USB transports need real-device
> validation before they can be treated as production-ready. The existing
> Concordance/PowerShell backend remains selectable. Android v0.2.1-alpha
> has passed real-world Harmony 300 learning, saving, .irplus export and
> replay in IRPlus.

## Download Windows

For the tested Windows v0.1.7 release, use
[GitHub Releases](https://github.com/MixterVee/IR-Remote-Studio/releases).

For the **experimental v0.2.2-beta direct USB learner**, obtain the
Windows ZIP from the latest successful
[Windows Actions build](https://github.com/MixterVee/IR-Remote-Studio/actions/workflows/windows.yml).
Extract the ZIP and launch the standalone \`IR-Remote-Studio.exe\`.
Python is not required to run the packaged executable.

In v0.2.2-beta the Windows learning panel provides **Save** to
confirm the selected command is saved without starting another capture, and
**Save & Learn another** to save and continue learning. Captures remain
autosaved immediately after successful receiving, as in the previous version.

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
