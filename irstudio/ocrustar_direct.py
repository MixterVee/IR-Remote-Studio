"""Experimental standalone Ocrustar / ElkSmart USB learning adapter.

Recognizes *only* VID:PID 045C:02AA with a matching bulk interface.
Protocol: https://github.com/deadboy18/Ocrustar-USB-IR (MIT).
No firmware, persistent configuration, USB driver, or transmit operations.

Learning pulses do not include measured carrier frequency. 38 kHz is an
ASSUMPTION and must be independently verified by actual target replay.
"""
from __future__ import annotations

import time
import uuid
from pathlib import Path
from typing import Callable, Protocol

VID, PID = 0x045C, 0x02AA
USB_IN, USB_OUT = 0x81, 0x01
HELLO = b"\xfc" * 4
HELLO_ACK = b"\xfa" * 4
LEARN_START = b"\xfe" * 4
LEARN_STOP = b"\xfd" * 4
READ_TIMEOUT_MS = 300
CAPTURE_TIMEOUT = 15.0
MAX_RAW_BYTES = 8192
CARRIER_ASSUMED_HZ = 38000


class BulkTransport(Protocol):
    def write(self, value: bytes) -> None: ...
    def read(self, timeout_ms: int) -> bytes | None: ...
    def close(self) -> None: ...


def decode_timings(raw: bytes) -> list[int]:
    """Decode documented 16-µs units; 0xFF adds 4080-µs overflow."""
    if not raw or len(raw) > MAX_RAW_BYTES:
        raise ValueError("Invalid IR learning packet length.")
    values: list[int] = []
    carry = 0
    for byte in raw:
        if byte == 0xFF:
            carry += 4080
            if carry > 200_000:
                raise ValueError("IR signal contains an implausibly long pulse.")
        else:
            duration = byte * 16 + carry
            carry = 0
            if not 16 <= duration <= 200_000:
                raise ValueError("Captured timing duration out of range.")
            values.append(duration)
    if carry:
        raise ValueError("Incomplete IR timing overflow marker.")
    if len(values) < 12 or len(values) % 2:
        raise ValueError("Incomplete or uneven mark/space IR waveform.")
    if sum(values) >= 2_000_000:
        raise ValueError("IR waveform exceeds the two-second limit.")
    return values


def _handshake(bus: BulkTransport, trace: list[str]) -> str:
    # Single authentication attempt, not a loop of unsolicited USB writes.
    bus.write(HELLO)
    response = bus.read(1500)
    if not response or len(response) < 6 or response[:4] != HELLO:
        raise RuntimeError("Ocrustar authentication reply missing or unexpected.")
    variant = response[4:6]
    if variant == b"\x02\xaa":
        model = "Ocrustar/ElkSmart D226"
    elif variant == b"\x70\x01":
        model = "Ocrustar/ElkSmart D552"
    else:
        raise RuntimeError("Unknown Ocrustar firmware variant; no learning attempted.")
    bus.write(HELLO_ACK)
    trace.append(f"Device authenticated: {model}; no configuration modified")
    return model


def receive_from_transport(bus: BulkTransport, mode: str = "Capture",
                           on_ready: Callable[[], None] | None = None,
                           clock: Callable[[], float] = time.monotonic) -> dict:
    """Bounded learn operation. STOP attempted in finally after START."""
    if mode not in ("Probe", "Capture"):
        raise ValueError("Unknown Ocrustar operation.")
    trace: list[str] = []
    model = _handshake(bus, trace)
    result = {"success": True, "mode": mode, "model": model,
              "compatibility": "experimental",
              "backend": "Ocrustar USB bulk (WinUSB/libusb)",
              "carrier_hz": 0, "timings_us": [],
              "carrier_note": "38 kHz assumed; device does not report carrier",
              "message": "", "trace": trace}
    if mode == "Probe":
        result["message"] = "Ocrustar handshake confirmed. Learning not started."
        return result
    started = False
    complete = False
    try:
        # Protect against an exception between USB transfer and started flag.
        started = True
        bus.write(LEARN_START)
        trace.append("IR learning started")
        if on_ready:
            on_ready()
        raw = bytearray()
        expected_len: int | None = None
        deadline = clock() + CAPTURE_TIMEOUT
        while clock() < deadline:
            remaining_ms = int((deadline - clock()) * 1000)
            if remaining_ms <= 0:
                break
            reply = bus.read(min(READ_TIMEOUT_MS, remaining_ms))
            if not reply:
                continue
            if expected_len is None:
                if len(reply) < 6 or reply[:4] != LEARN_START:
                    trace.append("Unexpected pre-capture packet ignored")
                    continue
                expected_len = int.from_bytes(reply[4:6], "big")
                if not 12 <= expected_len <= MAX_RAW_BYTES:
                    raise RuntimeError("Invalid learned IR payload size.")
                raw.extend(reply[6:])
                trace.append(f"Learning header: expecting {expected_len} data bytes")
            else:
                raw.extend(reply)
            if len(raw) >= expected_len:
                complete = True
                break
        if not complete or expected_len is None:
            raise RuntimeError("Ocrustar IR capture timed out or was incomplete.")
        timings = decode_timings(bytes(raw[:expected_len]))
        trace.append(f"Captured {len(timings) // 2} timing pairs; "
                     "carrier 38 kHz ASSUMED (not measured)")
        result["carrier_hz"] = CARRIER_ASSUMED_HZ
        result["timings_us"] = timings
        result["message"] = ("IR timings captured. Carrier assumed 38 kHz: "
                             "test playback before relying on the code.")
    finally:
        if started:
            bus.write(LEARN_STOP)
            trace.append("IR learning STOP transmitted (no documented ACK)")
    return result


class PyUsbBulk:
    """Exact documented bulk endpoints; WinUSB/libusb access required."""
    def __init__(self, device, ep_in, ep_out):
        self.device, self.ep_in, self.ep_out = device, ep_in, ep_out

    def write(self, value: bytes) -> None:
        count = self.ep_out.write(value, timeout=500)
        if count != len(value):
            raise RuntimeError(f"USB bulk OUT incomplete ({count}/{len(value)}).")

    def read(self, timeout_ms: int) -> bytes | None:
        import usb.core
        try:
            return bytes(self.ep_in.read(MAX_RAW_BYTES + 64, timeout=timeout_ms))
        except usb.core.USBTimeoutError:
            return None

    def close(self) -> None:
        import usb.util
        usb.util.dispose_resources(self.device)


def backend():
    try:
        import libusb_package
        return libusb_package.get_libusb1_backend()
    except ImportError:
        return None


def find_ocrustar():
    """Returns an enumerated matching device, or None. No writes to USB."""
    try:
        import usb.core
    except ImportError as exc:
        raise RuntimeError("Missing bundled PyUSB. Reinstall IR Remote Studio.") from exc
    try:
        return usb.core.find(idVendor=VID, idProduct=PID, backend=backend())
    except Exception as exc:
        raise RuntimeError("Unable to inspect Ocrustar USB device. "
                           "Check its current Windows driver.") from exc


def open_ocrustar() -> PyUsbBulk:
    import usb.util
    device = find_ocrustar()
    if device is None:
        raise RuntimeError("No supported Ocrustar 045C:02AA learning dongle detected.")
    cfg = device.get_active_configuration()
    intf = cfg[(0, 0)]
    eps = list(intf)
    ep_in = next((ep for ep in eps
                  if ep.bEndpointAddress == USB_IN and ep.bmAttributes & 0x3 == 2), None)
    ep_out = next((ep for ep in eps
                   if ep.bEndpointAddress == USB_OUT and ep.bmAttributes & 0x3 == 2), None)
    if ep_in is None or ep_out is None:
        raise RuntimeError("Ocrustar expected bulk endpoints 81/01 not present; "
                           "no learning commands sent.")
    return PyUsbBulk(device, ep_in, ep_out)


def invoke_ocrustar(mode: str, logs_folder: Path,
                    on_ready: Callable[[], None] | None = None) -> dict:
    """Capture with a bounded diagnostic saved to app Logs, not device flash."""
    if mode not in ("Probe", "Capture"):
        raise ValueError("Unsupported Ocrustar mode.")
    logs_folder.mkdir(parents=True, exist_ok=True)
    path = logs_folder / f"ocrustar-{mode.lower()}-{uuid.uuid4().hex[:10]}.log"
    transport = None
    result = None
    error = None
    try:
        transport = open_ocrustar()
        result = receive_from_transport(transport, mode, on_ready=on_ready)
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
        lines = list(result.get("trace", [])) if result else []
        if error is not None:
            lines.append(f"ERROR: {error}")
        try:
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        except OSError:
            pass
