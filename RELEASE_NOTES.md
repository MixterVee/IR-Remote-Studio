# IR Remote Studio v0.1.7 — Windows beta

The first official downloadable release of **IR Remote Studio**, a portable
Windows tool for learning and organizing infrared remote-control commands.

## Included

- Capture original IR commands using supported Logitech Harmony learning remotes,
  isolated in a 32-bit PowerShell worker to protect the app from native crashes.
- Organize any number of devices, custom button labels and multiple captures.
- Compare recordings and identify matched NEC / NEC Extended frames.
- Review capture history and choose the best recording for export.
- Import capture JSON and PRONTO_HEX `.irplus` devices.
- Export a device to `.irplus` for use on compatible Android phones.
- Design remote buttons on a 12-column grid with Phone and Grid previews;
  the exporter pads incomplete rows correctly.
- Show capture status (Not learned / Captured once / Verified / Mismatch / Imported).
- Save a portable JSON library backup and restore it with a safety copy.
- New official transparent remote-and-paintbrush branding in the app and EXE.

## Hardware verified

**Harmony 300** and **Harmony 350** were tested end-to-end: USB connection,
IR learning, `.irplus` export, and playback from an IR-capable Android phone.
Other listed Harmony models depend on Concordance compatibility documentation
and have **not** all been tested in this project.

## Installation

1. Download `IR-Remote-Studio-v0.1.7-Windows-x64.zip` below.
2. Extract the ZIP to a folder; run `IR-Remote-Studio.exe` on Windows 11 x64.
3. To learn from a Harmony, install compatible Concordance separately. The app
   expects its 32-bit `libconcord-6.dll` under `C:\Program Files (x86)\Concordance`.
4. Keep batteries installed in the Harmony, use a working USB data cable/port,
   and ensure other Harmony utilities are closed.
5. Import/export `.irplus` files with an IR-capable Android phone.

The executable includes Python but does **not** include the Concordance DLL.
Library data is stored in `Documents\IR Remote Studio\library.json`.

## Limitations

- Windows beta; Windows 11 x64 is the tested platform.
- Only NEC variants are currently decoded; other captured protocols are kept as
  raw signal data, and their exported playback requires device testing.
- Direct Android app/learning is a **future milestone**, not included in this ZIP.
- IR capture confidence labels indicate matching samples, not guaranteed playback.
- No installer or digital code-signing certificate is currently provided.

MIT License applies to our original code; optional Concordance is a **separate
GPLv2 project** and is not bundled. See `LICENSE` and
`THIRD_PARTY_NOTICES.md`.
