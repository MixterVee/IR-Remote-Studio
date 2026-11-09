"""SofaBaton U1 Bluetooth stored-code reader and guarded live learning test.

The protocol is derived from the official-app BLE HCI recording.
The read-back path has already been verified on the user's physical U1.

LIMITED SAFETY SCOPE:
- Requires an identity-verified U1 FFF0/FFF1/FFF2 Bluetooth session.
- Normal read operations send only observed 0x01 status and 0x0C inquiry.
- Main-screen learning invokes the exact v0.3.3 receive-and-decode
  routine, using observed 0x04 start and 0x05 finish for slot 8 after a
  verified local IR backup. No unobserved/conditional preparation writes.
  Live capture has been validated with the physical U1 and IRPlus.
  This MAY overwrite the U1's slot 8; the local backup CANNOT restore
  internal U1 programming.
- Never sends unverified 0x07/0x08/0x14 configuration/firmware writes.
- Validates frame checksums, fragment ordering and IR timing waveforms.
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
# Single observed U1 live-learning sequence, not a general learning API.
# These commands MAY change the U1 programmed slot. The caller must
# save and verify the preflight backup before sending learn-start.
EXPERIMENT_SLOT = 8
LEARN_START = 0x04
LEARN_FINISH = 0x05
LEARN_READY = 0x04
LEARN_CAPTURED = 0x06
LEARN_ACK = 0x03
EXPERIMENT_START_PAYLOAD = bytes.fromhex("081802000000000000")
EXPERIMENT_FINISH_PAYLOAD = b"\x01"
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


async def experimental_learn_connected_ir(client, inbox: asyncio.Queue[bytes],
                                          slot: int = EXPERIMENT_SLOT,
                                          timeout: float = 25) -> IrRead:
    """One explicit, high-risk experimental learn using the recorded slot-8 frames.

    WARNING: May overwrite the remote's slot 8. Must not be called until
    a local backup has been written AND the user explicitly accepts that
    the remote's original programming may not be recoverable.

    Sends only the observed 0x04 start and 0x05 finish/exit, not the
    associated unverified 0x07/0x08/0x14 configuration transactions.
    Requires 0x04 ready, 0x06 completion and valid 0x0D fragments.
    """
    if slot != EXPERIMENT_SLOT:
        raise ValueError("Experimental learning is limited to recorded slot 8.")
    if not client.is_connected:
        raise U1ReadError("Bluetooth connection lost before learning.")
    if not 8 <= timeout <= 40:
        raise ValueError("Invalid learn timeout.")
    while not inbox.empty():
        inbox.get_nowait()

    started = False
    result = None
    ready = False
    learned = False
    assembler = IrAssembler(slot)
    try:
        await client.write_gatt_char(
            WRITE, encode_frame(LEARN_START, EXPERIMENT_START_PAYLOAD),
            response=False)
        started = True
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while loop.time() < deadline:
            if not client.is_connected:
                raise U1ReadError("U1 disconnected during experimental learning.")
            try:
                raw = await asyncio.wait_for(
                    inbox.get(), timeout=max(.05, deadline-loop.time()))
            except asyncio.TimeoutError:
                break
            command, payload = decode_frame(raw)
            if command == LEARN_READY and payload == b"":
                ready = True
            elif command == LEARN_CAPTURED and payload == b"\x00":
                if not ready:
                    raise U1ReadError("Learning completed without ready indication.")
                learned = True
            elif command == IR_DATA and learned:
                result = assembler.add(payload)
                if result:
                    result.model = "SofaBaton U1 — live IR (experimental)"
                    break
        if result is None:
            raise U1ReadError(
                "No complete validated live IR arrived. "
                f"Ready: {ready}; captured: {learned}; "
                f"fragments: {assembler.next_fragment-1}. "
                "The app will attempt the observed finish command.")
    finally:
        if started and client.is_connected:
            # The official app sent 0x05 [01] after the 0x0D capture.
            # On a timeout we try the same finish/exit to avoid leaving
            # the U1 armed. Do not send any profile/configuration writes.
            await client.write_gatt_char(
                WRITE, encode_frame(LEARN_FINISH, EXPERIMENT_FINISH_PAYLOAD),
                response=False)
    return result


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
