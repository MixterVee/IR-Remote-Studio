"""Experimental standalone Harmony 300/350 MH IR learning via Windows HIDAPI.

Ported from the successfully tested Android receiver, but the Windows HID
transport and 0x00 report-ID framing MUST be validated on real Windows hardware.

Only the shared Harmony 300/350 USB VID:PID 046D:C124 is supported here.
No Harmony configuration, firmware, persistent storage or playback writes.
The existing Concordance PowerShell capture backend remains available.
"""
from __future__ import annotations

import os
import time
import uuid
from pathlib import Path
from typing import Callable, Protocol

VID, PID = 0x046D, 0xC124
REPORT_LEN = 64
MAX_REPORTS = 128
MAX_WORDS = 480
MAX_CAPTURE_SECONDS = 15.0
MAX_EMPTY_AFTER_DATA = 24
MH_START = bytes.fromhex("FF 00 00 01 01")
MH_INITIAL = bytes.fromhex("FF FF 01 01 01 66")
MH_CONTINUE = bytes.fromhex("FF 00 02")
IR_OPEN = bytes.fromhex("FF 01 00 02 80") + b"/ir/ir_cap\x00" + bytes((0x80, ord("R"), 0))
STAGE_ONE = bytes.fromhex("FF FF 81 00 81")
STAGE_TWO = bytes.fromhex("FF 00 82 00 7D")


class ReportTransport(Protocol):
    def write_report(self, prefix: bytes) -> None: ...
    def read_report(self, timeout_ms: int) -> bytes | None: ...
    def close(self) -> None: ...


def padded(prefix: bytes) -> bytes:
    if not prefix or len(prefix) > REPORT_LEN:
        raise ValueError("Invalid MH HID output report length.")
    return prefix.ljust(REPORT_LEN, b"\x00")


def normalize_input(data: bytes | list[int]) -> bytes:
    raw = bytes(data)
    # HIDAPI on Windows may return 64 payload bytes, or 0 report-ID + payload.
    if len(raw) == 65 and raw[0] == 0:
        raw = raw[1:]
    if len(raw) != REPORT_LEN:
        raise RuntimeError(f"Expected 64-byte Harmony input; got {len(raw)}.")
    return raw


def first_repeat_boundary(words: list[int]) -> int | None:
    """Recreate the conservative, real-device-tested Android frame heuristic."""
    if len(words) < 26:
        return None
    first_on, first_period = words[1], words[3]
    if not 300 <= first_on <= 20000 or first_period <= first_on:
        return None
    on_tol = max(80, first_on // 25)
    period_tol = max(120, first_period // 25)
    gap_limit = max(6000, first_period * 3 // 4)
    for i in range(24, len(words) - 1):
        if (words[i-1] >= gap_limit
                and abs(words[i] - first_on) <= on_tol
                and abs(words[i+1] - first_period) <= period_tol):
            return i
    return None


def decode_first_frame(words: list[int], repeat_at: int) -> tuple[int, list[int]]:
    """Decode MH first mark, carrier count, then mark + total-period pairs."""
    if (repeat_at < 26 or repeat_at > len(words) - 2
            or repeat_at % 2 or len(words) > MAX_WORDS):
        raise ValueError("No complete-looking IR frame with a repeat boundary.")
    first_mark, cycles = words[1], words[2]
    if not 100 <= first_mark <= 20000 or not 1 <= cycles <= 5000:
        raise ValueError("Malformed first carrier burst.")
    carrier = round(cycles * 1_000_000 / first_mark)
    if not 18000 <= carrier <= 80000:
        raise ValueError("Unsupported IR carrier estimate.")
    timings: list[int] = []
    mark = first_mark
    for i in range(3, repeat_at, 2):
        total = words[i]
        space = total - mark
        if not 50 <= mark <= 100000 or not 50 <= space <= 100000:
            raise ValueError("Malformed IR pulse or total period.")
        timings.extend((mark, space))
        if i + 1 < repeat_at:
            mark = words[i+1]
    if len(timings) < 24 or len(timings) % 2 or sum(timings) >= 2_000_000:
        raise ValueError("IR frame has invalid duration.")
    return carrier, timings


def is_stage_one(reply: bytes) -> bool:
    return len(reply) == 64 and reply[:5] == STAGE_ONE


def is_stage_two(reply: bytes) -> bool:
    return len(reply) == 64 and reply[:5] == STAGE_TWO


def is_done(reply: bytes) -> bool:
    return len(reply) == 64 and reply[:2] == b"\xF0\x70"


def is_close_ack(reply: bytes, parameter: int) -> bool:
    return (len(reply) == 64
            and reply[:6] == bytes((0xFF, 0x07, 0x83, 0x01, 0x01, parameter)))


def _require_read(bus: ReportTransport, timeout_ms: int, label: str) -> bytes:
    data = bus.read_report(timeout_ms)
    if data is None:
        raise RuntimeError(f"{label}: no HID reply within {timeout_ms} ms.")
    return normalize_input(data)


def _exchange(bus: ReportTransport, command: bytes, label: str,
              timeout_ms: int = 3500) -> bytes:
    bus.write_report(command)
    return _require_read(bus, timeout_ms, label)


def _handshake(bus: ReportTransport, trace: list[str]) -> None:
    for _ in range(5):
        bus.write_report(MH_START)
    trace.append("Five MH session-start reports sent")
    stage1 = _exchange(bus, MH_INITIAL, "MH stage one", 6000)
    trace.append("Stage one: " + stage1[:8].hex(" ").upper())
    if not is_stage_one(stage1):
        raise RuntimeError("Unexpected MH stage-one reply; no capture initiated.")
    bus.write_report(MH_CONTINUE)
    stage2 = _require_read(bus, 4000, "MH stage two")
    trace.append("Stage two: " + stage2[:8].hex(" ").upper())
    if is_stage_one(stage2):
        trace.append("Duplicate stage-one reply; checking ONE more IN report")
        stage2 = _require_read(bus, 1000, "additional MH stage-two reply")
        trace.append("Additional reply: " + stage2[:8].hex(" ").upper())
    if not is_stage_two(stage2):
        raise RuntimeError("Unexpected MH stage-two reply; no capture initiated.")
    trace.append("MH session handshake confirmed")


def _word_packet(packet: bytes, words: list[int]) -> int:
    length = packet[63]
    if not 2 <= length <= 62 or length % 2:
        raise RuntimeError("Malformed Harmony IR packet length.")
    new_words = (length - 2) // 2
    if len(words) + new_words > MAX_WORDS:
        raise RuntimeError("IR timing word limit reached.")
    for i in range(2, length, 2):
        words.append(packet[i] << 8 | packet[i+1])
    return new_words


def _scan_reply(bus: ReportTransport, expect: Callable[[bytes], bool],
                label: str, trace: list[str]) -> bool:
    deadline = time.monotonic() + 3.5
    stale = 0
    other = 0
    for _ in range(160):
        wait = min(0.4, deadline - time.monotonic())
        if wait <= 0:
            break
        report = bus.read_report(max(1, int(wait * 1000)))
        if report is None:
            break
        packet = normalize_input(report)
        if expect(packet):
            trace.append(f"{label}: verified; stale IR packets={stale}, other={other}")
            return True
        if packet[0] == 0x90:
            stale += 1
        else:
            other += 1
    trace.append(f"{label}: NOT CONFIRMED; stale IR packets={stale}, other={other}")
    return False


def learn_from_transport(bus: ReportTransport,
                         mode: str = "Capture",
                         on_ready: Callable[[], None] | None = None,
                         clock: Callable[[], float] = time.monotonic) -> dict:
    """Protocol logic isolated from HID library for deterministic unit tests."""
    if mode not in ("Probe", "Capture"):
        raise ValueError("Invalid direct Harmony operation.")
    trace: list[str] = []
    result = {"success": False, "mode": mode, "model": "Harmony 300/350 (direct HID)",
              "compatibility": "experimental", "backend": "Standalone Windows HID",
              "carrier_hz": 0, "timings_us": [], "message": "", "trace": trace}
    opened = False
    parameter = 0
    caught: BaseException | None = None
    carrier = 0
    timings: list[int] = []
    verified_stop = verified_close = False
    try:
        _handshake(bus, trace)
        if mode == "Probe":
            result["success"] = True
            result["message"] = "Harmony 300/350 MH session handshake succeeded without Concordance."
            return result
        opened_reply = _exchange(bus, IR_OPEN, "Open /ir/ir_cap")
        trace.append("IR open reply: " + opened_reply[:8].hex(" ").upper())
        if opened_reply[:5] != bytes.fromhex("FF 01 80 02 01"):
            raise RuntimeError("IR receive-open not confirmed.")
        opened = True
        parameter = opened_reply[5]
        ack = _exchange(bus, bytes((0xFF, 4, 1, 2, 1, parameter, 1, 0)),
                        "IR receive ACK")
        trace.append("IR ACK reply: " + ack[:9].hex(" ").upper())
        if ack[:6] != bytes((0xFF, 4, 0x81, 2, 1, parameter)):
            raise RuntimeError("IR receive ACK not confirmed.")
        if on_ready:
            on_ready()
        trace.append("READY: receiver armed; press and release source IR button")
        words: list[int] = []
        seq = 0x90
        packets = 0
        idle = 0
        boundary: int | None = None
        start = clock()
        for _ in range(MAX_REPORTS):
            remaining = MAX_CAPTURE_SECONDS - (clock() - start)
            if remaining <= 0:
                break
            wait_ms = min(int(remaining * 1000), 10000 if packets == 0 else 1200)
            packet = bus.read_report(max(1, wait_ms))
            if packet is None:
                trace.append("IR read timed out")
                break
            packet = normalize_input(packet)
            if is_done(packet):
                trace.append("IR end marker during capture")
                break
            if packet[0] != 0x90 or packet[1] != seq:
                raise RuntimeError(f"Unexpected IR packet header/sequence: {packet[:2].hex()}")
            before = len(words)
            _word_packet(packet, words)
            packets += 1
            seq = (seq + 0x10) & 0xFF
            if before == len(words):
                idle += 1
                if words and idle >= MAX_EMPTY_AFTER_DATA:
                    break
            else:
                idle = 0
                boundary = first_repeat_boundary(words)
                if boundary is not None:
                    break
        trace.append(f"IR packets={packets}, raw words={len(words)}, first repeat={boundary}")
        if boundary is None:
            raise RuntimeError("No complete-looking first IR frame; nothing saved.")
        carrier, timings = decode_first_frame(words, boundary)
        trace.append(f"Candidate: {carrier} Hz, {len(timings)//2} timing pairs")
    except Exception as error:
        caught = error
    finally:
        if opened:
            try:
                bus.write_report(bytes((0xFF, 6, 2, 2, 1, parameter, 1, 6)))
                verified_stop = _scan_reply(bus, is_done, "STOP/DONE", trace)
            except Exception as error:
                trace.append(f"STOP failed: {type(error).__name__}: {error}")
            try:
                bus.write_report(bytes((0xFF, 7, 3, 1, 1, parameter)))
                verified_close = _scan_reply(
                    bus, lambda p: is_close_ack(p, parameter), "CLOSE", trace)
            except Exception as error:
                trace.append(f"CLOSE failed: {type(error).__name__}: {error}")
    if caught:
        raise RuntimeError(f"{caught}\n" + "\n".join(trace)) from caught
    if not verified_stop or not verified_close:
        raise RuntimeError("IR capture received, but STOP/CLOSE could not both be verified.\n"
                           + "\n".join(trace))
    result["carrier_hz"] = carrier
    result["timings_us"] = timings
    result["success"] = True
    result["message"] = "Complete-looking IR frame captured and MH session closed."
    return result


class HidApiTransport:
    """Windows HIDAPI transport: 0 report ID + exactly 64 payload bytes."""
    def __init__(self, device):
        self.device = device

    def write_report(self, prefix: bytes) -> None:
        output = b"\x00" + padded(prefix)
        sent = self.device.write(output)
        if sent != len(output):
            raise RuntimeError(f"Windows HID OUT incomplete ({sent}/65 bytes).")

    def read_report(self, timeout_ms: int) -> bytes | None:
        received = self.device.read(65, timeout_ms)
        return normalize_input(received) if received else None

    def close(self) -> None:
        self.device.close()


def open_harmony_transport() -> HidApiTransport:
    if os.name != "nt":
        raise RuntimeError("Standalone Harmony HID learning is supported on Windows only.")
    try:
        import hid
    except ImportError as error:
        raise RuntimeError("Bundled HIDAPI backend unavailable. Reinstall the Windows app.") from error
    matches = hid.enumerate(VID, PID)
    if not matches:
        raise RuntimeError("No Harmony 300/350 HID device (046D:C124) found. Check USB.")
    if len(matches) != 1:
        raise RuntimeError(f"Found {len(matches)} matching HID interfaces. "
                           "Not choosing a potentially wrong interface automatically.")
    device = hid.device()
    try:
        device.open_path(matches[0]["path"])
        return HidApiTransport(device)
    except Exception:
        device.close()
        raise


def invoke_harmony_direct(mode: str, logs_folder: Path,
                          on_ready: Callable[[], None] | None = None) -> dict:
    """Opens a single USB session and writes a bounded diagnostic trace."""
    if mode not in ("Probe", "Capture"):
        raise ValueError("Unsupported direct Harmony mode")
    logs_folder.mkdir(parents=True, exist_ok=True)
    path = logs_folder / f"direct-{mode.lower()}-{uuid.uuid4().hex[:10]}.log"
    transport = None
    result = None
    error = None
    try:
        transport = open_harmony_transport()
        result = learn_from_transport(transport, mode, on_ready=on_ready)
        return result
    except Exception as exc:
        error = exc
        raise
    finally:
        if transport is not None:
            try:
                transport.close()
            except Exception:
                pass
        entries = result.get("trace", []) if result else []
        if error is not None:
            entries = entries + [f"ERROR: {error}"]
        try:
            path.write_text("\n".join(entries) + "\n", encoding="utf-8")
        except OSError:
            pass
