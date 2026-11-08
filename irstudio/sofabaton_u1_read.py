"""Experimental SofaBaton U1 Bluetooth *read-back* of an existing IR command.

The protocol was derived from the user's official-app BLE HCI recording and
validated against two identical, successfully replayed IRPlus waveforms.

LIMITED SAFETY SCOPE:
- Connects only after explicit selection in the Bluetooth diagnostics UI.
- Verifies the U1's observed proprietary GATT 0xFFF0 service/characteristics.
- Sends only official-app observed inquiry frames:
    0x01 []        device-info handshake
    0x0C [slot,FF] read stored IR code for an existing slot
- Does NOT send learning start (0x04), learning finish (0x05), or any
  configuration-write commands (0x07, 0x08, 0x14).
- Does not modify saved U1 remote programming or firmware intentionally.
- Validates every received frame, fragment ordering, assembled checksum,
  carrier frequency, and actual IR durations before returning any capture.
- Supports both the original one-shot read and a persistent, verified U1 session.

Reading a saved command is a stepping stone to future direct IR learning.
It is NOT live IR capture, and BLE interoperability requires field testing.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Callable

PREFIX = b"\xA5\x5A"
SVC = "0000fff0-0000-1000-8000-00805f9b34fb"
NOTIFY = "0000fff1-0000-1000-8000-00805f9b34fb"
WRITE = "0000fff2-0000-1000-8000-00805f9b34fb"
HELLO = 0x01
BANNER = 0x02
READ_IR = 0x0C
IR_DATA = 0x0D
MAX_FRAGMENTS = 80
MAX_SAMPLES = 4096
ALLOWED_SLOTS = tuple(range(1, 9))


class U1ReadError(RuntimeError):
    pass


def encode_frame(command: int, payload: bytes = b"") -> bytes:
    if not 0 <= command <= 255 or len(payload) > 255:
        raise ValueError("Invalid U1 message.")
    raw = PREFIX + bytes((len(payload), command)) + payload
    return raw + bytes((sum(raw) & 0xFF,))


def decode_frame(raw: bytes) -> tuple[int, bytes]:
    raw = bytes(raw)
    if (len(raw) < 5 or raw[:2] != PREFIX
            or len(raw) != raw[2] + 5
            or (sum(raw[:-1]) & 0xFF) != raw[-1]):
        raise U1ReadError("Invalid U1 Bluetooth packet length, header or checksum.")
    return raw[3], raw[4:-1]


@dataclass
class IrRead:
    slot: int
    carrier_hz: int
    timings_us: list[int]
    fragments: int
    model: str = "SofaBaton U1 — Bluetooth (stored IR)"


class IrAssembler:
    """Strict ordered 0x0D fragments into the original 4-byte timing values."""

    def __init__(self, slot: int):
        if slot not in ALLOWED_SLOTS:
            raise ValueError("Select an existing U1 slot from 1 through 8.")
        self.slot = slot
        self.next_fragment = 1
        self.expected = None
        self.data = bytearray()

    def add(self, payload: bytes) -> IrRead | None:
        if len(payload) < 4 or payload[:2] != b"\x01\x00":
            raise U1ReadError("Unexpected U1 IR fragment header.")
        index = payload[2]
        if index != self.next_fragment:
            raise U1ReadError(f"Missing or out-of-order U1 fragment: expected "
                              f"{self.next_fragment}, received {index}.")
        self.next_fragment += 1
        self.data.extend(payload[3:])
        if self.expected is None:
            if len(self.data) < 4:
                raise U1ReadError("Missing IR data header.")
            self.expected = self.data[2]
            if not 1 <= self.expected <= MAX_FRAGMENTS:
                raise U1ReadError("Invalid U1 fragment count.")
        if index < self.expected:
            if len(self.data) > 65536:
                raise U1ReadError("U1 IR response is unexpectedly large.")
            return None
        if index != self.expected:
            raise U1ReadError("U1 IR stream exceeds announced fragment count.")

        b = bytes(self.data)
        if len(b) < 25 or b[0:2] != b"\x01\x00" or b[2] != self.expected:
            raise U1ReadError("U1 IR response header invalid.")
        if b[3] != self.slot:
            raise U1ReadError(f"U1 returned slot {b[3]}, not requested slot {self.slot}.")
        size = int.from_bytes(b[12:16], "big")
        carrier = int.from_bytes(b[16:20], "big")
        if not 48 <= size <= MAX_SAMPLES * 4 or size % 8:
            raise U1ReadError("Stored IR timing length is invalid.")
        if len(b) != 21 + size:
            raise U1ReadError("Stored IR data size does not match header.")
        if (sum(b[:-1]) & 0xFF) != b[-1]:
            raise U1ReadError("Stored IR aggregate checksum failed.")
        values = [int.from_bytes(b[i:i+4], "big")
                  for i in range(20, 20 + size, 4)]
        # Reuse the same signal acceptance rules as Harmony and IRPlus.
        from irstudio import core
        core.make_capture(carrier, values, "SofaBaton U1 BLE stored IR")
        return IrRead(self.slot, carrier, values, self.expected)


def is_u1_service(services) -> bool:
    """Check exact service *and* characteristic UUIDs/properties."""
    for service in services:
        if str(service.uuid).lower() != SVC:
            continue
        chars = {str(c.uuid).lower(): set(c.properties) for c in service.characteristics}
        return (NOTIFY in chars and "notify" in chars[NOTIFY]
                and WRITE in chars and bool(chars[WRITE] & {"write", "write-without-response"}))
    return False


async def verify_identity(client, inbox: asyncio.Queue[bytes]) -> bytes:
    """Verify official-app U1 0x02 reply after the benign 0x01 inquiry."""
    await client.write_gatt_char(WRITE, encode_frame(HELLO), response=False)
    loop = asyncio.get_running_loop()
    deadline = loop.time() + 5.0
    while loop.time() < deadline:
        try:
            raw = await asyncio.wait_for(
                inbox.get(), timeout=max(.05, deadline - loop.time()))
        except asyncio.TimeoutError:
            break
        cmd, payload = decode_frame(raw)
        if cmd == BANNER:
            if len(payload) < 6:
                raise U1ReadError("U1 device-identification reply too short.")
            return payload
    raise U1ReadError("U1 identification timed out; no IR request was sent.")


async def read_connected_ir(client, inbox: asyncio.Queue[bytes],
                            slot: int) -> IrRead:
    """Read already-stored code via an open, previously verified connection.

    Caller owns notify subscription and BLE session; this never disconnects.
    """
    if slot not in ALLOWED_SLOTS:
        raise ValueError("Select an existing U1 slot 1 through 8.")
    if not client.is_connected:
        raise U1ReadError("U1 Bluetooth session is no longer connected.")
    # Remove stale notifications from an earlier operation.
    while not inbox.empty():
        inbox.get_nowait()
    await client.write_gatt_char(
        WRITE, encode_frame(READ_IR, bytes((slot, 0xFF))), response=False)
    fragments = IrAssembler(slot)
    loop = asyncio.get_running_loop()
    deadline = loop.time() + 12.0
    while loop.time() < deadline:
        try:
            raw = await asyncio.wait_for(
                inbox.get(), timeout=max(.05, deadline - loop.time()))
        except asyncio.TimeoutError:
            break
        cmd, payload = decode_frame(raw)
        if cmd != IR_DATA:
            continue
        result = fragments.add(payload)
        if result:
            return result
    raise U1ReadError(
        f"No complete IR code received from U1 slot {slot}. "
        f"Received {fragments.next_fragment-1} of "
        f"{fragments.expected or '?'} fragment(s). "
        "Use a slot containing an existing learned IR command.")


async def _read_ir_async(address: str, slot: int,
                         status: Callable[[str], None] | None = None,
                         client_factory=None) -> IrRead:
    """Previous one-shot read-back remains available for compatibility."""
    if slot not in ALLOWED_SLOTS:
        raise ValueError("Select an existing U1 slot 1 through 8.")
    if not address or len(address) > 128:
        raise ValueError("Select an identified SofaBaton U1 from Bluetooth Scan.")
    if client_factory is None:
        try:
            from bleak import BleakClient
        except ImportError as exc:
            raise RuntimeError("Bluetooth library missing from Windows build.") from exc
        client_factory = BleakClient

    async with client_factory(address, pair=False, timeout=12.0) as client:
        if not client.is_connected:
            raise U1ReadError("Could not connect to the selected U1.")
        if not is_u1_service(client.services):
            raise U1ReadError("The selected device does not expose the expected U1 "
                              "FFF0 / FFF1 / FFF2 Bluetooth interface. No commands sent.")
        inbox: asyncio.Queue[bytes] = asyncio.Queue()

        def receive(sender, data):
            inbox.put_nowait(bytes(data))

        await client.start_notify(NOTIFY, receive)
        try:
            if status:
                status("Connected; checking U1 device identity...")
            await verify_identity(client, inbox)
            if status:
                status(f"U1 verified; reading saved IR slot {slot} (no new learning)...")
            result = await read_connected_ir(client, inbox, slot)
            if status:
                status(f"Recovered {len(result.timings_us)} timings "
                       f"at {result.carrier_hz:,} Hz from stored slot {slot}.")
            return result
        finally:
            try:
                await client.stop_notify(NOTIFY)
            except Exception:
                pass


def read_saved_ir(address: str, slot: int,
                  status: Callable[[str], None] | None = None) -> IrRead:
    """Thread-safe sync entry point; call from worker, never Tk thread."""
    return asyncio.run(_read_ir_async(address, slot, status=status))
