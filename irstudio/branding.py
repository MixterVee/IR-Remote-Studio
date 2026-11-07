"""Shared transparent application branding for Windows and source-mode launches."""
from __future__ import annotations

import sys
import tkinter as tk
from pathlib import Path


def brand_image_path() -> Path:
    # PyInstaller extracts bundled --add-data files into _MEIPASS.
    root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return root / "assets" / "ir_remote_studio.png"


def install_icon(window: tk.Tk) -> tk.PhotoImage | None:
    """Install a persistent PNG title/taskbar icon; stay usable if asset is absent."""
    path = brand_image_path()
    try:
        img = tk.PhotoImage(master=window, file=str(path))
        window.iconphoto(True, img)
        return img
    except (OSError, tk.TclError):
        return None
