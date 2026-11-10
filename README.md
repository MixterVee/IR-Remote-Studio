# IR Remote Studio

**Windows IR learning, remote-button organization, and IRplus export.**

IR Remote Studio is a portable Windows application for building infrared remote-control libraries from learned signals. Create devices and named buttons, capture commands, compare samples, arrange button positions and widths, then export `.irplus` files for Android.

## Windows v1.0.0 release candidate

The first stable Windows release is being finalized. Until the v1.0.0 release is published, download and test the **v1.0.0 release-candidate** ZIP from the latest successful [Windows build](https://github.com/MixterVee/IR-Remote-Studio/actions/workflows/windows.yml). Windows x64 is the packaged target. Extract the ZIP and run `IR-Remote-Studio.exe` — Python does not need to be installed.

**Keep a copy of your existing `library.json` before upgrading.** Your library normally lives under `Documents/IR Remote Studio/`. The app does not erase a library or stored IR codes on startup.

### Get started

1. Open **SofaBaton U1...** to connect over Bluetooth, or select a supported USB learner and click **Detect USB** / **Connect USB**. Close the official SofaBaton mobile app while learning from Windows.
2. Select or add a device and select or add its named button.
3. Click **Capture selected button**. Aim the original remote at the learner and press the corresponding button when the device indicates learning readiness.
4. Each valid capture saves automatically in your local library. The U1 workflow checks the saved sample on disk before reporting success and then offers **Existing button**, **New button**, or **Finish**.
5. For inconsistent samples, choose **Undo last** or **Clear all samples for this button...** without deleting the button. Compare two or three samples before exporting.
6. Select **Layout editor...** to set the order and width of your buttons. Changes are previewed and committed only when you click **Save layout**; Cancel discards them. The editor remembers its window size and desktop position.
7. Click **Export Device (.irplus)**, transfer the file to Android, and test the remote in IRplus.

### Hardware support and limits

| Learner | Windows support | Important note |
| --- | --- | --- |
| **SofaBaton U1 (Bluetooth)** | Live learning and stored-command readback | Original v0.3.3 backend physically captured four commands successfully; the resulting `.irplus` buttons were verified in IRplus. Capture uses the U1's internal slot 8 after backing up the existing raw signal. |
| **Logitech Harmony 300/350 (USB HID)** | Direct learning backend | Compatibility depends on the actual HID device and Windows access. |
| **Ocrustar / ElkSmart USB bulk** | Experimental adapter | Hardware/driver dependent; an assumed carrier frequency may be used rather than measured. |
| **Legacy Concordance** | Optional legacy backend | Requires the appropriate external/native components. |

The U1 pre-learning backup preserves a **single raw IR waveform**, not all button mappings or the remote's full configuration. The application does **not** send the speculative profile-modification commands tested in earlier beta builds. Saving or clearing button samples changes the Windows library, not the remote's programming. A green USB status reflects the most recently verified USB handshake, not necessarily a continuously open USB session.

**Sample status:** “Verified” means multiple recorded samples agree; it does not prove the appliance responded. An unidentified format may still appear as **Raw IR (saved)** and export correctly through Pronto Hex. Always test the exported command on your device.

### Data, backups and troubleshooting

The Windows app stores its library at `Documents/IR Remote Studio/library.json`; use **File → Back up library JSON...** before significant changes and **File → Restore library backup...** if needed. Layout Editor window placement is stored separately as `layout-editor-window.json`. U1 waveform backups are saved under `U1-Learn-Backups/`.

The Bluetooth connection window is for scanning and connecting. Optional **Troubleshooting** controls allow stored-slot reads, Bluetooth service inspection, pairing requests, and saving the Bluetooth activity log. Routine learning happens in the main window; no typed `LEARN 8` prompt is needed.

For detailed setup, see [WINDOWS-SETUP.txt](WINDOWS-SETUP.txt). For what changed in 1.0, see [release notes](RELEASE-NOTES-v1.0.0-rc1.md). Earlier experimental progress and documentation were preserved in [pre-v1 development history](docs/DEVELOPMENT-HISTORY.md).

### Build from source

Install Python 3.12 and compatible dependencies, then run `python run.py`. Automated tests:

```bash
python -m unittest discover -s tests -v
```

Windows binaries are packaged by [GitHub Actions](.github/workflows/windows.yml). The standalone PyInstaller executable is not digitally code-signed; Windows may display a publisher warning.

## Project and license

Source repository: [MixterVee/IR-Remote-Studio](https://github.com/MixterVee/IR-Remote-Studio).

Original IR Remote Studio source and artwork: **MIT License** (see [LICENSE](LICENSE)). Android is a separate experimental companion, not included in this Windows ZIP.
