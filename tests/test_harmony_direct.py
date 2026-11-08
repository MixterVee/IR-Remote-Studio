"""Windows direct Harmony 300 MH protocol tests; no physical HID device required."""
import unittest

from irstudio import core
from irstudio import harmony_direct as direct


SAMPLE = [
    10, 4008, 153, 7996, 483, 2472, 483, 2472, 483, 2473,
    482, 2472, 483, 1499, 483, 1499, 483, 2472, 483, 1499,
    483, 1500, 482, 2472, 483, 2472, 483, 2472, 483, 1499,
    483, 1500, 482, 1499, 483, 1499, 483, 2472, 483, 2472,
    483, 1499, 483, 2473, 483, 2472, 483, 1499, 483, 1499,
    483, 1499, 483, 8771, 4008, 7996, 483
]


def reply(prefix):
    return direct.padded(bytes.fromhex(prefix))


def ir_packet(sequence, words):
    assert len(words) <= 30
    payload = bytearray(64)
    payload[0] = 0x90
    payload[1] = sequence
    for i, value in enumerate(words):
        payload[2 + 2*i:4 + 2*i] = value.to_bytes(2, "big")
    payload[63] = 2 + 2*len(words)
    return bytes(payload)


class SimulatedHid:
    def __init__(self, responses):
        self.responses = list(responses)
        self.writes = []
        self.closed = False

    def write_report(self, command):
        self.writes.append(direct.padded(command))

    def read_report(self, timeout_ms):
        return self.responses.pop(0) if self.responses else None

    def close(self):
        self.closed = True


def full_sequence(*, duplicate=False, missing_stop=False, invalid_ack=False):
    handshake = [reply("FF FF 81 00 81")]
    if duplicate:
        handshake.append(reply("FF FF 81 00 81"))
    handshake += [reply("FF 00 82 00 7D"),
                  reply("FF 01 80 02 01 0C 04 00 00 00 00 75"),
                  reply("FF 04 81 02 01 0C 01 00 74" if not invalid_ack
                        else "FF 04 80 02 01 0C")]
    if not invalid_ack:
        handshake += [ir_packet(0x90, SAMPLE[:30]),
                      ir_packet(0xA0, SAMPLE[30:])]
    if missing_stop:
        handshake += [reply("FF 00 00")]
    else:
        handshake += [reply("F0 70")]
    handshake += [reply("FF 07 83 01 01 0C 77")]
    return handshake


class WindowsDirectLearningTests(unittest.TestCase):
    def test_hidapi_report_id_and_padding(self):
        class FakeDevice:
            def __init__(self):
                self.writes = []
                self.data = []
                self.closed = False
            def write(self, payload):
                self.writes.append(bytes(payload))
                return len(payload)
            def read(self, amount, timeout):
                return self.data.pop(0) if self.data else []
            def close(self):
                self.closed = True
        device = FakeDevice()
        bus = direct.HidApiTransport(device)
        bus.write_report(bytes.fromhex("FF 00 02"))
        self.assertEqual(len(device.writes[0]), 65)
        self.assertEqual(device.writes[0][:5], bytes.fromhex("00 FF 00 02 00"))
        device.data = [list(b"\x00" + reply("FF 00 82 00 7D"))]
        self.assertEqual(bus.read_report(300), reply("FF 00 82 00 7D"))
        bus.close()
        self.assertTrue(device.closed)

    def test_real_hardware_fixture_captures_and_exports(self):
        self.assertEqual(len(SAMPLE), 57)
        self.assertEqual(direct.first_repeat_boundary(SAMPLE), 54)
        self.assertIsNone(direct.first_repeat_boundary(SAMPLE[:53]))
        carrier, timing = direct.decode_first_frame(SAMPLE, 54)
        self.assertEqual(carrier, 38173)
        self.assertEqual(len(timing), 52)
        self.assertEqual(timing[:4], [4008, 3988, 483, 1989])
        self.assertEqual(timing[-2:], [483, 8288])
        capture = core.make_capture(carrier, timing, "Standalone Windows HID")
        pronto = core.pronto_from_raw(capture["carrier_hz"], capture["timings_us"])
        self.assertTrue(pronto.startswith("0000 006D 001A 0000 "))
        self.assertEqual(len(pronto.split()), 56)

    def test_full_capture_checks_stop_and_close(self):
        bus = SimulatedHid(full_sequence())
        ready = []
        result = direct.learn_from_transport(bus, on_ready=lambda: ready.append(True))
        self.assertTrue(result["success"])
        self.assertEqual(result["carrier_hz"], 38173)
        self.assertEqual(len(result["timings_us"]), 52)
        self.assertEqual(ready, [True])
        self.assertEqual(len(bus.writes), 11)
        self.assertEqual(bus.writes[5][:6], direct.MH_INITIAL)
        self.assertEqual(bus.writes[6][:3], direct.MH_CONTINUE)
        self.assertEqual(bus.writes[7][:5], bytes.fromhex("FF 01 00 02 80"))
        self.assertEqual(bus.writes[-2][:3], bytes.fromhex("FF 06 02"))
        self.assertEqual(bus.writes[-1][:3], bytes.fromhex("FF 07 03"))
        self.assertIn("CLOSE: verified", "\n".join(result["trace"]))

    def test_probe_only_sends_handshake_commands(self):
        bus = SimulatedHid([reply("FF FF 81 00 81"), reply("FF 00 82 00 7D")])
        result = direct.learn_from_transport(bus, mode="Probe")
        self.assertTrue(result["success"])
        self.assertEqual(len(bus.writes), 7)
        self.assertFalse(any(b"/ir/ir_cap" in p for p in bus.writes))

    def test_one_duplicate_stage_one_then_valid_continuation(self):
        bus = SimulatedHid(full_sequence(duplicate=True))
        result = direct.learn_from_transport(bus)
        self.assertTrue(result["success"])
        self.assertEqual(len(bus.writes), 11)

    def test_unexpected_stage_two_fails_closed(self):
        bus = SimulatedHid([reply("FF FF 81 00 81"), reply("FF 00 84 00 00")])
        with self.assertRaisesRegex(RuntimeError, "stage-two"):
            direct.learn_from_transport(bus)
        self.assertEqual(len(bus.writes), 7)
        self.assertFalse(any(b"/ir/ir_cap" in p for p in bus.writes))

    def test_invalid_receiver_ack_still_sends_stop_and_close(self):
        bus = SimulatedHid(full_sequence(invalid_ack=True))
        with self.assertRaisesRegex(RuntimeError, "ACK not confirmed"):
            direct.learn_from_transport(bus)
        self.assertEqual(len(bus.writes), 11)
        self.assertEqual(bus.writes[-1][:3], bytes.fromhex("FF 07 03"))

    def test_missing_stop_ack_refuses_to_save_capture(self):
        bus = SimulatedHid(full_sequence(missing_stop=True))
        with self.assertRaisesRegex(RuntimeError, "STOP/CLOSE"):
            direct.learn_from_transport(bus)
        self.assertEqual(len(bus.writes), 11)

    def test_normalize_rejects_short_frames(self):
        with self.assertRaisesRegex(RuntimeError, "64-byte"):
            direct.normalize_input([0xFF, 0x01])
        with self.assertRaises(ValueError):
            direct.padded(b"\xff" * 65)


if __name__ == "__main__":
    unittest.main()
