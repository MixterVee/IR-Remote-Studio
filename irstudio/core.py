"""IR Remote Studio: portable data, protocol checks and irplus interoperability."""
from __future__ import annotations

import copy
import datetime as dt
import json
import math
import os
import re
import tempfile
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

SCHEMA = 1


def new_device(name: str, manufacturer: str = "", model: str = "") -> dict:
    name = name.strip()
    if not name:
        raise ValueError("Device name cannot be empty.")
    return {"id": str(uuid.uuid4()), "name": name, "manufacturer": manufacturer.strip(),
            "model": model.strip(), "buttons": []}


def new_button(label: str) -> dict:
    label = label.strip()
    if not label:
        raise ValueError("Button label cannot be empty.")
    return {"id": str(uuid.uuid4()), "label": label, "captures": [], "selected": 0,
            "pronto_hex": ""}


def default_library() -> dict:
    return {"schema": SCHEMA, "devices": []}


def validate_library(lib: dict) -> dict:
    if not isinstance(lib, dict) or lib.get("schema") != SCHEMA:
        raise ValueError("Unsupported library format. Expected schema 1.")
    if not isinstance(lib.get("devices"), list):
        raise ValueError("Library is missing the device list.")
    return lib


def load_library(path: Path) -> dict:
    if not path.exists():
        return default_library()
    return validate_library(json.loads(path.read_text(encoding="utf-8-sig")))


def save_library(path: Path, library: dict) -> None:
    validate_library(library)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Atomic replacement; a failed write won't corrupt an existing library.
    fd, tmp = tempfile.mkstemp(prefix=".ir-studio-", suffix=".json", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(library, f, ensure_ascii=False, indent=2)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def good_carrier(carrier: int) -> bool:
    return 10000 <= carrier <= 100000


def normalize_timings(timings: list[int]) -> list[int]:
    """Discard only unusable terminal idle, preserve all actual marks and gaps."""
    t = [int(x) for x in timings]
    if len(t) < 2 or len(t) % 2 or any(x <= 0 for x in t):
        raise ValueError("Capture must contain positive alternating mark/space durations.")
    if len(t) > 10000:
        raise ValueError("Capture is too long.")
    # Concordance often appends >0.5 s of idle silence after the last mark.
    if t[-1] > 200000:
        t[-1] = 40000
    return t


def decode_nec(timings: list[int]) -> dict | None:
    """Decode 32-bit NEC/extended NEC lead frame, ignoring subsequent repeat frames."""
    t = timings
    if len(t) < 67 or not (7600 <= t[0] <= 10400 and 3600 <= t[1] <= 5400):
        return None
    bits = []
    for i in range(32):
        mark, gap = t[2 + i*2: 4 + i*2]
        if not 350 <= mark <= 800:
            return None
        if 350 <= gap <= 950:
            bits.append(0)
        elif 1250 <= gap <= 2050:
            bits.append(1)
        else:
            return None
    if not 350 <= t[66] <= 800:
        return None
    vals = [sum(bits[8*j+k] << k for k in range(8)) for j in range(4)]
    if (vals[2] ^ vals[3]) != 255:
        return None
    standard = (vals[0] ^ vals[1]) == 255
    return {"protocol": "NEC" if standard else "NEC extended", "address":
            f"0x{vals[0]:02X}" if standard else f"0x{vals[1]:02X}{vals[0]:02X}",
            "command": f"0x{vals[2]:02X}", "bytes": " ".join(f"{b:02X}" for b in vals),
            "signature": bytes(vals).hex()}


def is_repeat_only(timings: list[int]) -> bool:
    return (len(timings) < 9 and len(timings) >= 3 and
            7600 <= timings[0] <= 10400 and 1600 <= timings[1] <= 3000)


def assess_capture(carrier: int, timings: list[int]) -> dict:
    if not good_carrier(carrier):
        return {"valid": False, "reason": f"Implausible carrier: {carrier} Hz", "decoded": None}
    try:
        t = normalize_timings(timings)
    except ValueError as exc:
        return {"valid": False, "reason": str(exc), "decoded": None}
    if is_repeat_only(t):
        return {"valid": False, "reason": "Repeat frame only - press once again to capture the full command.", "decoded": None}
    if len(t) < 12:
        return {"valid": False, "reason": "Too few pulses for a reliable command.", "decoded": None}
    decoded = decode_nec(t)
    if decoded:
        return {"valid": True, "reason": "Full NEC frame decoded", "decoded": decoded}
    return {"valid": True, "reason": "Raw capture; protocol not identified (verify by repetition)", "decoded": None}


def signature_of_capture(capture: dict) -> str:
    decoded = decode_nec(capture["timings_us"])
    if decoded:
        return "nec:" + decoded["signature"]
    t = normalize_timings(capture["timings_us"])
    # Fallback: coarse normalized mark/space fingerprint. Human review recommended.
    end = min(len(t), 66)
    return "raw:" + ",".join(str(int(round(x/110))) for x in t[:end])


def make_capture(carrier: int, timings: list[int], source: str = "Harmony 350") -> dict:
    rating = assess_capture(carrier, timings)
    if not rating["valid"]:
        raise ValueError(rating["reason"])
    return {"carrier_hz": int(carrier), "timings_us": normalize_timings(timings),
            "captured_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "source": source, "decoded": rating["decoded"], "note": rating["reason"]}


def selected_capture(button: dict) -> dict | None:
    captures = button.get("captures", [])
    selected = button.get("selected", 0)
    if not isinstance(selected, int) or selected < 0 or selected >= len(captures):
        selected = 0
    return captures[selected] if captures else None


def match_count(button: dict) -> tuple[int, int]:
    caps = button.get("captures", [])
    if not caps:
        return (0, 0)
    keys = [signature_of_capture(c) for c in caps]
    return (max(keys.count(k) for k in keys), len(keys))


def best_capture_index(button: dict) -> int:
    caps = button.get("captures", [])
    if not caps:
        return 0
    keys = [signature_of_capture(c) for c in caps]
    return max(range(len(keys)), key=lambda i: (keys.count(keys[i]), -i))


def shorten_capture_for_export(timings: list[int], repeats: int = 0) -> list[int]:
    """For NEC, export complete lead frame plus optional short NEC repeats.

    For other protocols, remove impossible terminal silence but preserve the waveform.
    """
    t = normalize_timings(timings)
    if decode_nec(t):
        # 9 ms + 4.5 ms leader, 32 bit-pairs, final mark, optional gap.
        # A clean one-shot preserves the gap immediately following the terminal mark.
        head = t[:68] if len(t) >= 68 else (t[:67] + [40000])
        head[-1] = max(8000, min(head[-1], 70000))
        remaining = t[68:]
        added = 0
        for i in range(0, len(remaining) - 3, 4):
            seq = remaining[i:i + 4]
            if (7600 <= seq[0] <= 10400 and 1600 <= seq[1] <= 3000 and
                    350 <= seq[2] <= 800):
                if added >= repeats:
                    break
                gap = min(70000, max(8000, seq[3]))
                head += [seq[0], seq[1], seq[2], gap]
                added += 1
        return head
    # Other protocols: export full waveform, strip absurd terminal idle.
    return t


def pronto_from_raw(carrier: int, timings: list[int], repeats: int = 0) -> str:
    if not good_carrier(carrier):
        raise ValueError(f"Carrier out of supported range: {carrier}")
    t = shorten_capture_for_export(timings, repeats)
    freq_word = round(1000000.0 / (carrier * 0.241246))
    if not 1 <= freq_word <= 65535:
        raise ValueError("Pronto carrier cannot be represented")
    pairs = len(t) // 2
    if pairs > 65535:
        raise ValueError("Too many timing pairs")
    words = [0, freq_word, pairs, 0]
    for duration in t:
        cycles = round(duration * carrier / 1000000.0)
        if not 1 <= cycles <= 65535:
            raise ValueError(f"Pronto duration out of range: {duration} microseconds")
        words.append(cycles)
    return " ".join(f"{w:04X}" for w in words)


def validate_pronto(text: str) -> str:
    words = text.strip().split()
    try:
        numbers = [int(w, 16) for w in words]
    except ValueError as exc:
        raise ValueError("Invalid Pronto Hex value") from exc
    if len(numbers) < 8 or any(n < 0 or n > 65535 for n in numbers) or numbers[0] != 0:
        raise ValueError("Only raw (0000) Pronto Hex codes are supported")
    pairs = numbers[2] + numbers[3]
    if len(numbers) != 4 + 2*pairs or pairs < 1:
        raise ValueError("Pronto Hex header length does not match timing values")
    return " ".join(f"{x:04X}" for x in numbers)


def pronto_for_button(button: dict, repeats: int = 0) -> str:
    cap = selected_capture(button)
    if cap:
        return pronto_from_raw(cap["carrier_hz"], cap["timings_us"], repeats)
    pronto = button.get("pronto_hex", "")
    if pronto:
        return validate_pronto(pronto)
    raise ValueError(f"'{button.get('label', '?')}' has no captured command")


def export_irplus(device: dict, path: Path, repeats: int = 0) -> int:
    root = ET.Element("irplus")
    el = ET.SubElement(root, "device", {
        "manufacturer": device.get("manufacturer") or device["name"],
        "model": device.get("model") or device["name"],
        "columns": "12", "format": "PRONTO_HEX"})
    exported = 0
    for button in device.get("buttons", []):
        if not button.get("captures") and not button.get("pronto_hex"):
            continue
        code = pronto_for_button(button, repeats)
        ET.SubElement(el, "button", {"label": button["label"], "span": "6"}).text = code
        exported += 1
    if not exported:
        raise ValueError("No buttons with IR commands to export")
    ET.indent(root, space="  ")
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)
    return exported


def import_irplus(path: Path) -> list[dict]:
    root = ET.parse(path).getroot()
    if root.tag != "irplus":
        raise ValueError("Not an irplus XML file")
    devices = []
    for d in root.findall("device"):
        if d.get("format") != "PRONTO_HEX":
            continue
        device = new_device(d.get("model", "Imported remote"), d.get("manufacturer", ""), d.get("model", ""))
        for b in d.findall("button"):
            if not (b.text or "").strip():
                continue
            button = new_button(b.get("label", "Button"))
            button["pronto_hex"] = validate_pronto(b.text or "")
            device["buttons"].append(button)
        if device["buttons"]:
            devices.append(device)
    if not devices:
        raise ValueError("No supported PRONTO_HEX devices in this .irplus file")
    return devices


def import_capture_json(path: Path) -> tuple[str, dict]:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(data, dict) or "carrier_hz" not in data or "timings_us" not in data:
        raise ValueError("Not a Concordance capture JSON with carrier_hz and timings_us")
    cap = make_capture(int(data["carrier_hz"]), data["timings_us"], "Imported capture")
    return str(data.get("key") or path.stem), cap
