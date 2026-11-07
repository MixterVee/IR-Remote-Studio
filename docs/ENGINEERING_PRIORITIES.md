# IR Remote Studio — engineering priorities

**Approved priorities (equal):**

1. Learn IR from Harmony 300/350 directly in the Android application via USB-C OTG.
2. Make the Windows capture backend truly standalone, with no Concordance
   installation and ideally no code/runtime dependency on Concordance.

Remote UI polish, colour themes, macros, extra export formats and database
features are on hold. Preserve the working Windows v0.1.7 release and the
verified Android v0.1.1-alpha IRPLUS import/transmit functionality.

## Android USB milestones

- A1: USB host discovery of Harmony 300/350, identify VID/PID and HID interface
  descriptors. Do not send commands. Added to v0.1.2-alpha as a laboratory page.
- A2: Secure USB access permission; read-only device open; verify endpoint
  accessibility.
- A3: Implement and exercise identity requests, based on documented protocol
  behavior; prohibit firmware or configuration writes.
- A4: Implement the IR learning command, receive marks/spaces and carrier,
  validate signals, and associate recordings with buttons.
- A5: Repeatability tests, cancellation, error recovery and safe disconnection.

**Hardware gate:** a connected Harmony 300/350 must capture a previously
unknown button and the resulting code must control the actual device.
USB enumeration alone does not count.

## Windows standalone milestones

- W1: Enumerate hardware and driver interfaces natively, without invoking
  Concordance. `scripts/inspect_harmony_usb_windows.ps1` is read-only; it
  depends only on built-in Windows PowerShell/PnP functionality.
- W2: Develop a native HID communication backend (Windows HID APIs / HIDAPI),
  inspect endpoint/report sizes and reproduce model identification.
- W3: Capture actual IR data and decode signals, using the existing core
  validation/export tests.
- W4: Bundle the complete native learning backend into the portable EXE, then
  test on a clean Windows 11 machine with no Concordance DLL or installer.
- W5: Keep Concordance fallback optional during transition, remove when the
  native path is hardware-verified.

**Hardware gate:** Harmony 300 and 350 independently detect and capture from
a clean Windows PC without any Concordance installation. A standalone
installer that simply bundles a GPL library is not the same as an independent
capture backend.

## Known USB information

- The Logitech Harmony 300/350 family is commonly listed with
  VID:PID `046D:C124`; verify on the actual hardware.
- Concordance's HID discovery has documented Harmony device IDs in Logitech
  VID `046D`, product range `C110–C14F`. These are *candidate* identifiers.
- Android has USB Host device enumeration, user permission and USB transfers,
  but actual ability to communicate with the Harmony HID interface still
  requires hardware tests.
- Harmony remote batteries matter for USB initialization on tested devices.

## Licensing and safeguards

- Concordance is a separately GPL-licensed project. Do not copy its source
  into our MIT-licensed code or ship its binaries without a deliberate GPL
  compliance plan.
- A clean implementation referencing hardware behavior/protocol documentation
  is a possible path, but must be independently tested and legally reviewed
  before redistribution.
- All prototype tools are **read-only** until USB transport is understood.
  No configuration, memory or firmware write operations should be added to
  prototypes.
- Keep the latest verified Windows and Android builds installable.

## Research references

- [Concordance upstream](https://github.com/jaymzh/concordance)
- [Android USB Host guide](https://developer.android.com/develop/connectivity/usb/host)
- [HIDAPI project](https://github.com/libusb/hidapi)
