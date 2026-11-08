"""Regression tests for persistent Windows U1 Bluetooth connection handling.

All BLE traffic is mocked; no real hardware or pairing is invoked in CI.
"""
import threading
import unittest

from irstudio import sofabaton_u1_read as u1
from irstudio.sofabaton_u1_session import U1Session
from irstudio import core
import math

def packets(slot=8):
    values = [3996, 3999] + [469, 2000]*51
    size = len(values)*4
    fragment_count = math.ceil((20 + size + 1) / 12)
    blob = (bytes([1, 0, fragment_count, slot]) + bytes(8)
            + size.to_bytes(4, "big") + (38095).to_bytes(4, "big")
            + b"".join(x.to_bytes(4, "big") for x in values))
    blob += bytes([sum(blob) & 0xff])
    return [u1.encode_frame(u1.IR_DATA, bytes([1, 0, i+1]) + blob[i*12:i*12+12])
            for i in range(fragment_count)]

class Characteristic:
    def __init__(self, uuid, props):
        self.uuid, self.properties = uuid, props

class Service:
    def __init__(self, uuid, chars):
        self.uuid, self.characteristics = uuid, chars



class FakePersistentBleClient:
    instances = []
    accept_service = True
    connect_fails = False

    def __init__(self, address, pair, timeout, disconnected_callback):
        self.address = address
        self.pair = pair
        self.timeout = timeout
        self.on_drop = disconnected_callback
        self.is_connected = False
        self.requests = []
        self.notify_cb = None
        self.stopped = []
        self.services = ([Service(u1.SVC, [
            Characteristic(u1.NOTIFY, ["notify"]),
            Characteristic(u1.WRITE, ["write-without-response"])])]
            if self.accept_service else [Service("00001800", [])])
        self.instances.append(self)

    async def connect(self):
        if self.connect_fails:
            raise RuntimeError("No BLE adapter")
        self.is_connected = True

    async def disconnect(self):
        self.is_connected = False
        self.on_drop(self)

    async def start_notify(self, _uuid, callback):
        self.notify_cb = callback

    async def stop_notify(self, uuid):
        self.stopped.append(uuid)

    async def write_gatt_char(self, uuid, data, response):
        self.requests.append((uuid, bytes(data), response))
        cmd, payload = u1.decode_frame(data)
        if cmd == u1.HELLO:
            self.notify_cb(1, u1.encode_frame(u1.BANNER, bytes(16)))
        elif cmd == u1.READ_IR:
            for frame in packets(payload[0]):
                self.notify_cb(1, frame)

    def remote_drop(self):
        self.is_connected = False
        self.on_drop(self)


class PersistentU1SessionTests(unittest.TestCase):
    def setUp(self):
        FakePersistentBleClient.instances = []
        FakePersistentBleClient.accept_service = True
        FakePersistentBleClient.connect_fails = False
        self.session = U1Session(client_factory=FakePersistentBleClient)

    def tearDown(self):
        self.session.shutdown()

    def test_verified_connect_stays_active_when_read_finishes(self):
        initial = self.session.snapshot()
        self.assertFalse(initial.connected)
        live = self.session.connect("TEST-U1", "SB-TEST")
        self.assertEqual(live.state, "CONNECTED")
        self.assertTrue(self.session.snapshot().connected)
        r1 = self.session.read_saved_ir(8)
        r2 = self.session.read_saved_ir(8)
        self.assertEqual(r1.carrier_hz, 38095)
        self.assertEqual(r2.fragments, 37)
        self.assertTrue(self.session.snapshot().connected)
        self.assertEqual(len(FakePersistentBleClient.instances), 1)
        sent = [u1.decode_frame(raw)[0]
                for _, raw, _ in FakePersistentBleClient.instances[0].requests]
        self.assertEqual(sent, [u1.HELLO, u1.READ_IR, u1.READ_IR])
        self.assertFalse(any(cmd in (0x04, 0x05, 0x07, 0x08, 0x14)
                             for cmd in sent))
        self.session.disconnect()
        self.assertFalse(self.session.snapshot().connected)
        self.assertEqual(FakePersistentBleClient.instances[0].stopped,
                         [u1.NOTIFY])

    def test_optional_pairing_is_explicit_and_default_false(self):
        self.session.connect("TEST-U1", "SB-TEST")
        self.assertFalse(FakePersistentBleClient.instances[-1].pair)
        self.session.disconnect()
        self.session.connect("TEST-U1", "SB-TEST", pair=True)
        self.assertTrue(FakePersistentBleClient.instances[-1].pair)

    def test_disconnect_callback_clears_ready_immediately(self):
        self.session.connect("TEST-U1", "SB-TEST")
        FakePersistentBleClient.instances[-1].remote_drop()
        snap = self.session.snapshot()
        self.assertEqual(snap.state, "LOST")
        self.assertFalse(snap.connected)
        with self.assertRaisesRegex(RuntimeError, "not connected"):
            self.session.read_saved_ir(8)

    def test_wrong_ble_interface_fails_closed(self):
        FakePersistentBleClient.accept_service = False
        with self.assertRaisesRegex(RuntimeError, "No U1 command was sent"):
            self.session.connect("TEST-U1", "NOT-U1")
        self.assertEqual(FakePersistentBleClient.instances[0].requests, [])
        self.assertFalse(self.session.snapshot().connected)

    def test_connection_error_never_sets_green(self):
        FakePersistentBleClient.connect_fails = True
        with self.assertRaisesRegex(RuntimeError, "No BLE adapter"):
            self.session.connect("TEST-U1", "SB-TEST")
        self.assertEqual(self.session.snapshot().state, "FAILED")
        self.assertFalse(self.session.snapshot().connected)


if __name__ == "__main__":
    unittest.main()
