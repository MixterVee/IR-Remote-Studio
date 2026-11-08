"""Hardware-specific USB IR receiver registry for Windows.

A reusable back-end interface, not an assumption that arbitrary IR dongles
share a protocol. Unrecognized USB devices are never sent IR commands.
"""
from __future__ import annotations

from pathlib import Path
from typing import Callable

AUTO = "Auto detect supported learner"
HARMONY = "Harmony 300/350 — standalone HID"
OCRUSTAR = "Ocrustar / ElkSmart — USB bulk (experimental)"
LEGACY = "Concordance — legacy DLL"
BACKENDS = (AUTO, HARMONY, OCRUSTAR, LEGACY)


def harmony_attached() -> bool:
    """Enumerate USB HID descriptors without opening or writing to devices."""
    try:
        import hid
        from irstudio.harmony_direct import VID, PID
        return bool(hid.enumerate(VID, PID))
    except (ImportError, OSError, ValueError):
        return False


def ocrustar_attached() -> bool:
    try:
        from irstudio.ocrustar_direct import find_ocrustar
        return find_ocrustar() is not None
    except (ImportError, RuntimeError, OSError, ValueError):
        return False


def choose_backend(choice: str,
                   detect_harmony: Callable[[], bool] = harmony_attached,
                   detect_ocrustar: Callable[[], bool] = ocrustar_attached) -> str:
    if choice not in BACKENDS:
        raise ValueError("Unknown USB IR receiver selection.")
    if choice != AUTO:
        return choice
    found = []
    if detect_harmony():
        found.append(HARMONY)
    if detect_ocrustar():
        found.append(OCRUSTAR)
    if len(found) == 1:
        return found[0]
    if len(found) > 1:
        raise RuntimeError("Multiple supported IR learners detected. "
                           "Choose one receiver from the selection list.")
    raise RuntimeError(
        "No recognized standalone USB IR learner. Supported direct backends: "
        "Harmony 300/350 (046D:C124) and Ocrustar/ElkSmart (045C:02AA). "
        "Other dongles require a separate documented protocol adapter. "
        "The existing Concordance backend remains selectable."
    )


def invoke_receiver(choice: str, mode: str, logs_folder: Path,
                    on_ready: Callable[[], None] | None = None) -> dict:
    selected = choose_backend(choice)
    if selected == HARMONY:
        from irstudio.harmony_direct import invoke_harmony_direct
        return invoke_harmony_direct(mode, logs_folder, on_ready=on_ready)
    if selected == OCRUSTAR:
        from irstudio.ocrustar_direct import invoke_ocrustar
        return invoke_ocrustar(mode, logs_folder, on_ready=on_ready)
    if selected == LEGACY:
        from irstudio.harmony import invoke_harmony
        return invoke_harmony(mode, logs_folder)
    raise AssertionError("Unsupported IR receiver backend.")
