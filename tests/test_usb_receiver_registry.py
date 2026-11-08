"""Tests of Windows USB IR receiver registry and experimental Ocrustar learner.

All tests use simulated USB transport; no actual dongle or driver is required.
"""
import unittest

from irstudio import core, ocrustar_direct, receivers


class SimulatedBulk:
    def __init__(self, packets):
        self.packets = list(packets)
        self.writes = []

    def write(self, message):
        self.writes.append(bytes(message))

    def read(self, timeout_ms):
        return self.packets.pop(0) if self.packets else None

    def close(self):
        return None


def encode_raw(durations):
    data = bytearray()
    for duration in durations:
        assert duration % 16 == 0
        while duration >= 4080:
            data.append(0xFF)
            duration -= 4080
        data.append(duration // 16)
    return bytes(data)


class ReceiverRegistryTests(unittest.TestCase):
    def test_auto_chooses_only_recognized_receiver(self):
        self.assertEqual(receivers.choose_backend(receivers.AUTO, lambda: True,
                          lambda: False), receivers.HARMONY)
        self.assertEqual(receivers.choose_backend(receivers.AUTO, lambda: False,
                          lambda: True), receivers.OCRUSTAR)
        with self.assertRaisesRegex(RuntimeError, "Multiple"):
            receivers.choose_backend(receivers.AUTO, lambda: True, lambda: True)
        with self.assertRaisesRegex(RuntimeError, "No recognized"):
            receivers.choose_backend(receivers.AUTO, lambda: False, lambda: False)

    def test_explicit_user_selection_is_respected(self):
        self.assertEqual(receivers.choose_backend(receivers.LEGACY), receivers.LEGACY)
        self.assertEqual(receivers.choose_backend(receivers.HARMONY), receivers.HARMONY)
        with self.assertRaises(ValueError):
            receivers.choose_backend("Random USB IR dongle")


class OcrustarLearningTests(unittest.TestCase):
    def setUp(self):
        # 12 durations / 6 mark-space pairs, a minimal raw sample.
        self.durations = [9008, 4512, 560, 560, 560, 1680,
                          560, 560, 560, 1680, 560, 560]
        self.raw = encode_raw(self.durations)
        self.ident = b"\xfc\xfc\xfc\xfc\x02\xaa"

    def packets(self):
        return [self.ident, b"\xfe" * 4 +
                len(self.raw).to_bytes(2, "big") + self.raw[:6],
                self.raw[6:]]

    def test_decode_timings_preserves_long_leader(self):
        self.assertEqual(ocrustar_direct.decode_timings(self.raw), self.durations)
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            ocrustar_direct.decode_timings(b"\xff")

    def test_probe_only_authenticates(self):
        bus = SimulatedBulk([self.ident])
        result = ocrustar_direct.receive_from_transport(bus, mode="Probe")
        self.assertTrue(result["success"])
        self.assertEqual(bus.writes, [b"\xfc" * 4, b"\xfa" * 4])
        self.assertFalse(any(b"\xfe" * 4 == p for p in bus.writes))

    def test_learning_stops_before_returning_data(self):
        bus = SimulatedBulk(self.packets())
        ready = []
        result = ocrustar_direct.receive_from_transport(
            bus, on_ready=lambda: ready.append(True))
        self.assertTrue(result["success"])
        self.assertEqual(result["timings_us"], self.durations)
        self.assertEqual(result["carrier_hz"], 38000)
        self.assertIn("assumed", result["carrier_note"].lower())
        self.assertEqual(ready, [True])
        self.assertEqual(bus.writes, [b"\xfc" * 4, b"\xfa" * 4,
                                      b"\xfe" * 4, b"\xfd" * 4])
        # Integration with the existing Windows capture/export pipeline.
        saved = core.make_capture(result["carrier_hz"], result["timings_us"],
                                  result["model"])
        self.assertTrue(core.pronto_from_raw(
            saved["carrier_hz"], saved["timings_us"]).startswith("0000 "))

    def test_rejects_unknown_variant_before_start_learning(self):
        bus = SimulatedBulk([b"\xfc" * 4 + b"\x10\x00"])
        with self.assertRaisesRegex(RuntimeError, "Unknown Ocrustar"):
            ocrustar_direct.receive_from_transport(bus)
        self.assertEqual(bus.writes, [b"\xfc" * 4])

    def test_stop_attempted_for_incomplete_waveform(self):
        malformed = b"\x00" * 12
        bus = SimulatedBulk([self.ident, b"\xfe" * 4 +
                len(malformed).to_bytes(2, "big") + malformed[:6],
                malformed[6:]])
        with self.assertRaisesRegex(ValueError, "Invalid|Incomplete|uneven|duration"):
            ocrustar_direct.receive_from_transport(bus)
        self.assertEqual(bus.writes[-1], b"\xfd" * 4)

    def test_stop_attempted_after_timeout(self):
        bus = SimulatedBulk([self.ident])
        moments = iter([0.0, 0.0, 14.9, 15.0, 15.0, 15.1])
        def clock():
            return next(moments, 16.0)
        with self.assertRaisesRegex(RuntimeError, "timed out"):
            ocrustar_direct.receive_from_transport(bus, clock=clock)
        self.assertEqual(bus.writes[-1], b"\xfd" * 4)


if __name__ == "__main__":
    unittest.main()
