# IR Remote Studio v1.0.0 — Release Candidate 3

**Windows x64, portable executable.** Prepared November 10, 2026.

This is the proposed first stable Windows release. It remains a **release candidate** until final hands-on testing and acceptance; do not treat its GitHub Actions artifact as an officially tagged stable release.

## Highlights

- **Working SofaBaton U1 learning engine preserved** from v0.3.3, the version that learned four IR commands successfully on hardware and exported four working buttons to IRplus. No speculative profile-creation or configuration writes were added.
- **Cleaner main window** focused on receiver connection, devices, named buttons, sample captures and IRplus export. Captures autosave; the redundant Save buttons are gone.
- **Bluetooth connection made simpler.** Removed the obsolete disabled experimental Learn controls; ordinary learning happens directly from the main screen. Optional readback and logs remain in collapsed **Troubleshooting**.
- **Clearer wording**: USB receiver buttons distinguish detection from connection; captured raw IR is shown as saved rather than “Not learned”; sample agreement and signal format are separated.
- **Real About / Version window** with the actual application version, supported backends, license, data folder and GitHub project link.
- **Layout Editor** owns button appearance (order and width), previews changes before saving and remembers its last position and size.
- **Recovery-friendly learning**: undo the latest mismatched sample, clear all samples without deleting a button, or learn into any existing button after autosave.
- **Backups and export**: maintain original Windows JSON library backups and import/export `.irplus` compatibility.

## Limits and verification

Hardware verification was completed for the original v0.3.3 SofaBaton learning engine, and v0.3.12 layout behavior was tested by the user. The new v1 main-window wording, About dialog and clean Bluetooth connection UI need a final physical U1/UI check before declaring this stable.

USB hardware compatibility depends on the device and driver. The Ocrustar/ElkSmart adapter remains **experimental**. U1 slot-8 backups preserve the previous raw waveform only and **cannot restore the remote's full internal programming**.

Automated checks include the complete protocol and GUI test modules in isolated processes on Windows and under a Linux virtual display. Both must pass before distributing the candidate.

## Installation

Download the successful `IR-Remote-Studio-v1.0.0-rc3-Windows-x64` GitHub Actions artifact, extract the ZIP and run `IR-Remote-Studio.exe`. No Python install is needed. Keep `library.json` backed up before updating.

There is no installer or digital code signature yet; Windows may show a publisher warning. The ZIP includes `WINDOWS-SETUP.txt`, `README.md`, `LICENSE`, this document and a SHA-256 checksum for the EXE.

[Project](https://github.com/MixterVee/IR-Remote-Studio) | [MIT License](LICENSE) | [Setup guide](WINDOWS-SETUP.txt)

### Final acceptance checklist

- [ ] Existing `library.json` opens unchanged with all devices/buttons and samples
- [ ] U1 connects once and can learn at least one new signal from the main page
- [ ] Captured signal autosaves and exports to working IRplus code
- [ ] Existing / New / Finish follow-up works without extra reconnects
- [ ] Layout Editor order/width and remembered position work
- [ ] About shows v1.0.0-rc3 and expected supported hardware
- [ ] File backup/restore and portable Windows startup are satisfactory

After acceptance, the stable tag and GitHub Release can be issued as `v1.0.0` with the reviewed build and release notes.

## RC2 Bluetooth connection presentation (retained)

The SofaBaton connection window now shows **Searching for U1 → Connecting → Connected** without displaying unrelated nearby Bluetooth devices, MAC addresses, or device lists during the normal auto-connect workflow. A manual choice appears only if more than one plausible U1 is found or the automatic U1 connection cannot complete. Manual candidates are restricted to U1-like advertisements. The Bluetooth scanner, identity verification, connection session, backup and proven IR learning engine are not changed.

The parallel Android v0.3.0-alpha parity update is being validated separately before the Windows 1.0 stable tag.

## RC3: Fix wrong receiver during capture after U1 connects

Fixes a real Windows 11 hardware regression reported on a verified, connected
SofaBaton U1 (SB202006231D9E). The main connection banner was green, yet
the Receiver dropdown still had **Harmony 300/350 — standalone HID** selected;
Capture therefore attempted to find a USB Harmony and showed
"No Harmony 300/350 HID device (046D:C124) found" even though U1 was online.

- A **SofaBaton U1 — Bluetooth** option now appears in the Receiver dropdown.
  As soon as a U1 session is verified CONNECTED, this option becomes the selected
  learning receiver, even if a Harmony had previously been selected.
- The main Capture button now explicitly says **Capture selected button
  (SofaBaton U1)**, and the connection action reads **Connect U1**.
- Capture on a connected U1 always uses the already working BLE learner and
  per-slot backup-before-learning pathway; it does NOT check for a Harmony.
- If U1 is explicitly selected but disconnected, Capture requests a new U1
  connection instead of silently trying to use Harmony USB.
- Users can still **intentionally select Harmony 300/350 or another USB
  receiver** from the dropdown after U1 connects. Explicit USB selection is
  respected, and switching back to U1 is one click.
- New GUI regression tests cover auto-switching from stale Harmony to U1,
  intentional USB override, disconnected U1 blocking USB fallback and
  Connect U1 button routing.

The v0.3.3-tested SofaBaton GATT learning protocol, backup-before-learning,
notification and session code were **not modified**.
