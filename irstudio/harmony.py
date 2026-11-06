"""Out-of-process Harmony USB capture using the previously proven libconcord PowerShell bridge."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path


def asset_path(filename: str) -> Path:
    root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    # Source checkout: worker is packaged next to harmony.py; PyInstaller root: bundled at root.
    candidate = root / filename
    return candidate if candidate.exists() else Path(__file__).resolve().parent / filename


def find_ps32() -> Path:
    windir = Path(os.environ.get("WINDIR", r"C:\Windows"))
    # A 64-bit GUI must use x86 PowerShell to load the existing x86 Concordance DLL.
    exe = windir / "SysWOW64" / "WindowsPowerShell" / "v1.0" / "powershell.exe"
    if not exe.is_file():
        raise RuntimeError("32-bit Windows PowerShell is unavailable (required for libconcord).")
    return exe


def invoke_harmony(mode: str, logs_folder: Path) -> dict:
    if mode not in ("Probe", "Capture"):
        raise ValueError("Unsupported Harmony operation")
    exe = find_ps32()
    script = asset_path("Xflyee_IR_Worker.ps1")
    if not script.exists():
        raise RuntimeError("Bundled Harmony capture worker is missing")
    logs_folder.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="irremote-") as temp:
        out = Path(temp) / "result.json"
        log = logs_folder / f"{mode.lower()}-{os.getpid()}-{next(tempfile._get_candidate_names())}.log"
        argv = [str(exe), "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                str(script), "-Mode", mode, "-OutputPath", str(out), "-LogPath", str(log)]
        try:
            run = subprocess.run(argv, capture_output=True, text=True, timeout=40 if mode == "Capture" else 20,
                                 creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(f"Harmony {mode} timed out. See log: {log}") from exc
        if not out.is_file():
            raise RuntimeError(f"Harmony helper failed (exit {run.returncode}). See log: {log}")
        result = json.loads(out.read_text(encoding="utf-8-sig"))
        if not result.get("success"):
            raise RuntimeError(f"{result.get('message', 'IR operation failed')}\nTrace: {log}")
        return result
