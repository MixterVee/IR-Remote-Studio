# IR Remote Studio

**Portable IR remote learning and management for Windows, with Android planned.**

Create devices, use custom labels for every button, capture commands from a Logitech Harmony 300 or 350, compare repeated recordings, and export **`.irplus`** remotes for your Android phone.

> **Status:** Windows v0.1.2 beta source and Windows build workflow. The actual Windows executable is built by GitHub Actions and is an **artifact**, not yet a GitHub Release. Direct Android learning and the Android app are future work.

## Download Windows beta

1. Open [Actions → Windows executable](https://github.com/MixterVee/IR-Remote-Studio/actions/workflows/windows.yml).
2. Open the latest successful workflow run.
3. Under **Artifacts**, download **IR-Remote-Studio-v0.1.2-Windows-x64**.
4. Extract the ZIP and run `IR-Remote-Studio.exe` on Windows 11 x64.
5. Read `WINDOWS-SETUP.txt` for the Harmony prerequisites.

The one-file `.exe` includes the Python UI and capture worker. Python is **not** required on the target PC. The Harmony capture integration **does require** an existing compatible Concordance `libconcord-6.dll` installation and 32-bit Windows PowerShell, both used by the earlier successful prototype.

## Features in Windows v0.1

- Unlimited devices, custom button labels, button ordering, rename/delete.
- Capture Harmony 300/350 IR commands in an isolated helper process (no firmware/configuration writes).
- Reject incomplete NEC repeat-only signals, validate carrier and mark/space durations.
- Decode standard and extended NEC when supported; show address, command and bytes.
- Save multiple captures for each button and identify matching NEC frames.
- Import previous Harmony capture JSON and existing PRONTO_HEX `.irplus` devices.
- Export whole devices to `.irplus` with optional NEC repeat frames.
- Autosave human-readable library JSON; make a manual library backup.
- Logs for native USB operations.

### Quick start

1. Run the app; choose **+ Add** under **Devices**, enter a name such as `Kitchen Clock`, manufacturer `Xflyee`, model `099`.
2. Choose **+ Add** under **Buttons** and type your own label, such as `SET`, `Alarm`, `HR`, `MIN`.
3. Click **Check Harmony 300/350**, then select a button, click **Capture selected button** and press its original remote button.
4. Repeat the capture twice for comparison. A `2/2` count means the samples agree at the decoded NEC command level.
5. Select **Export .irplus**, then import the file into `irplus` on Android.

**Previously verified SET command:** `06 AB 01 FE` (extended NEC, address `0xAB06`, command `0x01`). See `examples/` for two matching captures, one repeat-only sample and the known-working `.irplus` remote. The OnePlus 15 transmitted the SET command successfully in prior testing.

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
