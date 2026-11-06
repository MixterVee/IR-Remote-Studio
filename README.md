# IR Remote Studio

Open-source IR remote learning and management for **Windows** and **Android**.

Build a personal library of infrared-controlled devices, assign **custom button labels**, capture commands with compatible learners, and export reusable remote layouts.

> **Project status: early development.** This repository is being established for a cross-platform application. The items below describe goals, not a released implementation.

## Project goals

- Create, rename, organize and back up multiple devices and their button maps.
- Give every command a custom button label (e.g. `SET`, `Alarm SET`, `Timer ST/SP`).
- Capture infrared timings (carrier frequency plus mark/space durations) from compatible learning hardware.
- Validate repeated captures and flag empty or repeat-only frames.
- Preserve the original raw captures, alongside decoded protocols where supported.
- Import and export [irplus](https://irplus-remote.github.io/) (`.irplus`) remote layouts.
- Export Pronto Hex and machine-readable JSON, with other formats added where tested.
- On Android, transmit commands through a device's **built-in IR emitter**, where supported.
- On Windows, support Logitech Harmony learning where independently verified, starting with the Harmony 350; explore compatible USB learners.
- Investigate Android USB-C learning dongles, including hardware-specific Ocrustar models.

## Proof of concept

The initial experiments used a **Logitech Harmony 350** connected to a Windows PC to capture a **Xflyee model 099** wall-clock remote's `SET` command. The signal was converted into an `.irplus` remote and successfully retransmitted from a **OnePlus 15** to operate the clock. This validates the general workflow but does **not** establish universal Harmony/USB compatibility.

## Planned structure

| Area | Purpose |
| --- | --- |
| Windows desktop | Device library, learning workflows, capture verification and exports |
| Android | Personal remote library, editing, import/export and IR transmission |
| Shared format | Device/button definitions with lossless raw timing capture data |
| Hardware integrations | Hardware-specific capture backends, kept separate from the user interface |

## Safety and compatibility

Learning receiver support must be validated **by exact hardware version**, not merely by a seller's claim that a dongle supports IR. The Harmony integration must not rewrite the remote's configuration or firmware during normal capture operations. Raw carrier and timing data should remain available even when a protocol decoder cannot identify a command.

## License

A license has not been selected yet. Do not assume a particular open-source license until a `LICENSE` file is added.

## Development

Implementation, build instructions and first installable releases will be added as the project is developed.
