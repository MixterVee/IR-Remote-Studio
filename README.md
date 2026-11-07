# IR Remote Studio

**Portable IR remote learning and management for Windows, with Android planned.**

Create devices, use custom labels for every button, capture commands from a compatible Logitech Harmony IR learner, compare repeated recordings, and export **`.irplus`** remotes for your Android phone.

> **Status:** Windows v0.1.4 beta source and Windows build workflow. The actual Windows executable is built by GitHub Actions and is an **artifact**, not yet a GitHub Release. Direct Android learning and the Android app are future work.

## Download Windows beta

1. Open [Actions → Windows executable](https://github.com/MixterVee/IR-Remote-Studio/actions/workflows/windows.yml).
2. Open the latest successful workflow run.
3. Under **Artifacts**, download **IR-Remote-Studio-v0.1.4-Windows-x64**.
4. Extract the ZIP and run `IR-Remote-Studio.exe` on Windows 11 x64.
5. Read `WINDOWS-SETUP.txt` for the Harmony prerequisites.

The one-file `.exe` includes the Python UI and capture worker. Python is **not** required on the target PC. The Harmony capture integration **does require** an existing compatible Concordance `libconcord-6.dll` installation and 32-bit Windows PowerShell, both used by the earlier successful prototype.

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

The Harmony learning interface is based on libconcord and invokes the helper in a **separate 32-bit Windows PowerShell process** (the earlier x86 prototype worked with that DLL). Different Harmony models and USB ports may behave differently. Never run Concordance and this app simultaneously. Ensure a normal working USB connection before learning.

New raw formats are preserved in the local library even when a protocol decoder does not recognize them. Repeat-only NEC captures are rejected rather than falsely marked valid. Other protocols are marked raw/unidentified until verified.

The project does not ship Logitech firmware or Concordance DLLs. The existing installed native library is used only on the user's machine.

## Roadmap

- More learning hardware and protocol decoders, editing capture selection and raw timing inspection.
- Dedicated Android app to import/organize/edit/transmit remotes with a built-in IR emitter.
- Test USB-C dongle learning on Android (device model/protocol must be verified).
- Additional export formats and configurable remote-grid layouts.

## Licensing

License decision pending. Code is publicly readable, but reuse permission has not been granted by a LICENSE file yet.

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

## Windows v0.1.4 improvements

- **Remote layout editor:** choose a button, set its width, and preview how it fits on a 12-column irplus grid. The saved width is used in .irplus exports and preserved when compatible files are imported.
- **Capture history:** review all stored recordings of a button, choose the sample used for export or delete a bad sample. Existing automatic best-match selection is retained.
- **Library recovery:** File → Back up library saves a JSON copy; File → Restore library backup validates the input and creates a timestamped safety backup of the current library before replacing it. Backups live under Documents/IR Remote Studio/Backups.
- **USB troubleshooting:** connection errors now remind users to check Harmony batteries, USB cable, and port.
- **Neutral labels:** exports refer to a compatible phone rather than a specific device model.
- **Window resizing:** capture controls stay accessible in the lower panel.

**Hardware verified in field testing:** Harmony 300 and Harmony 350 both completed USB connection, IR learning, .irplus export and playback from a compatible phone. Other documented-compatible models are not individually hardware verified.

**Planned, not yet delivered:** Android app, additional protocol decoders, multi-button drag/drop layout, a formal tagged release, and license selection.
