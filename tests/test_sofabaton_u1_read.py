"""SofaBaton U1 stored-IR query and payload regression tests.

No real Bluetooth activity. Test fixtures contain generated synthetic IR
timings, not personally identifiable remote commands or BLE addresses.
"""
import asyncio
import math
import unittest
from unittest.mock import patch

from irstudio import core
from irstudio import sofabaton_u1_read as u1


def sample_blob(slot=8, values=None):
    if values is None:
        values = [3996, 3999] + [469, 2000] * 51
    size = len(values) * 4
    fragments = math.ceil((20 + size + 1) / 12)
    payload = (bytearray(b"\x01\x00")
               + bytes([fragments, slot])
               + bytes(8)
               + size.to_bytes(4, "big")
               + (38095).to_bytes(4, "big")
               + b"".join(x.to_bytes(4, "big") for x in values))
    return bytes(payload) + bytes((sum(payload) & 0xFF,))


def packets(slot=8, values=None):
    blob = sample_blob(slot, values)
    fragments = [blob[i:i+12] for i in range(0, len(blob), 12)]
    return [u1.encode_frame(u1.IR_DATA, b"\x01\x00" + bytes([i]) + item)
            for i, item in enumerate(fragments, 1)]


class TestU1Protocol(unittest.TestCase):
    def test_frame_checksum_and_length_from_real_trace(self):
        # Observed benign inquiry; sum includes A5 5A and command.
        self.assertEqual(u1.encode_frame(0x0C, b"\x07\xff"),
                         bytes.fromhex("a55a020c07ff13"))
        self.assertEqual(u1.decode_frame(bytes.fromhex("a55a020c07ff13")),
                         (0x0C, bytes.fromhex("07ff")))
        with self.assertRaisesRegex(u1.U1ReadError, "checksum"):
            u1.decode_frame(bytes.fromhex("a55a020c07ff12"))
        with self.assertRaises(u1.U1ReadError):
            u1.decode_frame(b"\xa5\x5a\x01")

    def test_assemble_ir_uses_only_0d_frames_and_validates_checksum(self):
        part = packets()
        decoder = u1.IrAssembler(8)
        received = None
        for frame in part:
            cmd, payload = u1.decode_frame(frame)
            self.assertEqual(cmd, u1.IR_DATA)
            received = decoder.add(payload)
        self.assertIsNotNone(received)
        self.assertEqual(received.fragments, len(part))
        self.assertEqual(received.carrier_hz, 38095)
        self.assertEqual(received.slot, 8)
        self.assertEqual(len(received.timings_us), 104)
        code = core.pronto_from_raw(received.carrier_hz, received.timings_us)
        self.assertTrue(code.startswith("0000 006D 0034 0000"))

    def test_rejects_out_of_order_or_wrong_slot(self):
        seq = packets()
        d = u1.IrAssembler(8)
        d.add(u1.decode_frame(seq[0])[1])
        with self.assertRaisesRegex(u1.U1ReadError, "out-of-order"):
            d.add(u1.decode_frame(seq[2])[1])
        d = u1.IrAssembler(7)
        with self.assertRaisesRegex(u1.U1ReadError, "not requested"):
            for raw in seq:
                d.add(u1.decode_frame(raw)[1])

    def test_rejects_aggregate_checksum_error(self):
        b = bytearray(sample_blob())
        b[-1] ^= 0x02
        d = u1.IrAssembler(8)
        with self.assertRaisesRegex(u1.U1ReadError, "checksum"):
            for i, index in enumerate(range(0, len(b), 12), 1):
                d.add(bytes([1, 0, i]) + b[index:index+12])

    def test_refuse_slots_outside_observed_catalog(self):
        for slot in (0, -1, 9, 256):
            with self.assertRaises(ValueError):
                u1.IrAssembler(slot)
        self.assertEqual(u1.encode_frame(u1.HELLO).hex(), "a55a000100")


class Characteristic:
    def __init__(self, uuid, props):
        self.uuid, self.properties = uuid, props


class Service:
    def __init__(self, uuid, chars):
        self.uuid, self.characteristics = uuid, chars


class FakeBleClient:
    sessions = []
    configured_services = True

    def __init__(self, address, pair, timeout):
        self.address = address
        self.is_connected = True
        self.writes = []
        self.stopped = []
        self.callback = None
        self.services = ([Service(u1.SVC, [
            Characteristic(u1.NOTIFY, ["notify"]),
            Characteristic(u1.WRITE, ["write-without-response", "notify"])])]
            if self.configured_services else [Service("00001800", [])])
        FakeBleClient.sessions.append(self)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def start_notify(self, uuid, callback):
        self.callback = callback
        self.uuid = uuid

    async def stop_notify(self, uuid):
        self.stopped.append(uuid)

    async def write_gatt_char(self, uuid, data, response):
        self.writes.append((uuid, bytes(data), response))
        cmd, payload = u1.decode_frame(data)
        if cmd == u1.HELLO:
            self.callback(1, u1.encode_frame(u1.BANNER, bytes(16)))
        elif cmd == u1.READ_IR:
            for response_data in packets(payload[0]):
                self.callback(1, response_data)


class U1TransportTests(unittest.TestCase):
    def setUp(self):
        FakeBleClient.sessions = []
        FakeBleClient.configured_services = True

    def test_exactly_two_inquiries_and_no_programming_commands(self):
        result = asyncio.run(
            u1._read_ir_async("test-address", 8, client_factory=FakeBleClient))
        self.assertEqual(result.carrier_hz, 38095)
        session = FakeBleClient.sessions[-1]
        commands = [u1.decode_frame(data)[0] for _, data, _ in session.writes]
        self.assertEqual(commands, [u1.HELLO, u1.READ_IR])
        self.assertEqual([w[1].hex() for w in session.writes],
                         ["a55a000100", "a55a020c08ff14"])
        self.assertTrue(all(not response for _, _, response in session.writes))
        self.assertEqual(session.stopped, [u1.NOTIFY])

    def test_unknown_service_fails_before_any_write(self):
        FakeBleClient.configured_services = False
        with self.assertRaisesRegex(u1.U1ReadError, "No commands sent"):
            asyncio.run(u1._read_ir_async(
                "test-address", 8, client_factory=FakeBleClient))
        self.assertEqual(FakeBleClient.sessions[-1].writes, [])

    def test_reject_unknown_slot_before_connect(self):
        with self.assertRaises(ValueError):
            asyncio.run(u1._read_ir_async(
                "test-address", 10, client_factory=FakeBleClient))
        self.assertFalse(FakeBleClient.sessions)


if __name__ == "__main__":
    unittest.main()
