# Third-party components and trademarks

IR Remote Studio original source files and included generated artwork are
licensed under the project's MIT License.

## Concordance / libconcord — optional, separately installed

IR Remote Studio supports IR capture through the **Concordance** project and
its `libconcord-6.dll` native library. Concordance is an independently distributed
third-party project, advertised as licensed under the GNU General Public License (the current upstream GitHub LICENSE is GPLv3;
older SourceForge metadata describes GPLv2). See its project licensing terms and source at:

- https://github.com/jaymzh/concordance
- https://sourceforge.net/projects/concordance/

**This repository and Windows ZIP do not ship, embed, copy, or redistribute
Concordance DLLs or other Concordance binaries.** A compatible 32-bit Concordance
installation is supplied separately by the user. Our PowerShell bridge invokes
the API of the user's locally installed library in a separate PowerShell process.
The MIT License does not relicense Concordance.

## HIDAPI, PyUSB and libusb — direct USB learning backends

The standalone Windows Harmony 300/350 backend uses Python `hidapi`
and the Ocrustar/ElkSmart learning adapter uses `pyusb` with
`libusb-package`, bundled with the Windows executable. Their licenses
and notices are maintained by their upstream maintainers:
- https://github.com/trezor/cython-hidapi (HIDAPI Python binding)
- https://github.com/libusb/hidapi (native HIDAPI)
- https://github.com/pyusb/pyusb (PyUSB)
- https://github.com/pyocd/libusb-package (libusb-package)

The Ocrustar timing protocol information is based on the MIT-licensed
independent research by deadboy18 (no copied source is shipped):
https://github.com/deadboy18/Ocrustar-USB-IR

## Other third-party components

Python and Tkinter form the app runtime. PyInstaller, Pillow, and GitHub Actions
are used to build the Windows executable. Their upstream licenses apply to them.
The packaged executable may contain third-party components with their own notices.
Check the upstream license texts before redistributing a customized build.

Logitech, Harmony, Android and irplus are trademarks/names of their respective
owners. IR Remote Studio is an independent project and is not endorsed by them.
