"""Safe, simulated SofaBaton BLE inspection tests. No Bluetooth adapter needed."""
import asyncio
import sys
import types
import unittest
from unittest.mock import patch

from irstudio import sofabaton_ble


class DummyDevice:
    def __init__(self, name, address):
        self.name, self.address = name, address


class DummyAdvertisement:
    def __init__(self, name, rssi=-58, uuids=()):
        self.local_name, self.rssi = name, rssi
        self.service_uuids = list(uuids)


class BleDiagnosticsTests(unittest.TestCase):
    def test_scan_prioritizes_sofabaton_and_only_enumerates(self):
        raw = {
            "one": (DummyDevice("Headphones", "AA:BB"),
                    DummyAdvertisement(None)),
            "two": (DummyDevice("BLE device", "CC:DD"),
                    DummyAdvertisement("SofaBaton U1", -40, ["1234"])),
        }
        result = sofabaton_ble.normalize_discoveries(raw)
        self.assertEqual(result[0].name, "SofaBaton U1")
        self.assertTrue(result[0].likely_sofabaton)
        self.assertEqual(result[0].advertised_services, ("1234",))
        self.assertFalse(result[1].likely_sofabaton)

    def test_scanner_passively_collects_advertisements_without_connect(self):
        events = []

        class MockScanner:
            @staticmethod
            async def discover(*, timeout, return_adv):
                events.append(("scan", timeout, return_adv))
                return {"x": (DummyDevice("SofaBaton U1", "X"),
                              DummyAdvertisement("SofaBaton U1"))}

        with patch.dict(sys.modules, {"bleak": types.SimpleNamespace(
                BleakScanner=MockScanner)}):
            result = sofabaton_ble.scan_ble(3.0)
        self.assertEqual(len(result), 1)
        self.assertEqual(events, [("scan", 3.0, True)])

    def test_inspect_only_connects_and_discovers_uuid_metadata(self):
        events = []

        class Descriptor:
            uuid = "00002902"
        class Characteristic:
            uuid = "0000a001"
            properties = ["notify", "write"]
            descriptors = [Descriptor()]
        class Service:
            uuid = "0000b001"
            characteristics = [Characteristic()]
        class MockClient:
            def __init__(self, address, pair, timeout):
                events.append(("init", address, pair, timeout))
                self.is_connected = True
                self.services = [Service()]
            async def __aenter__(self):
                events.append("connect")
                return self
            async def __aexit__(self, *exc):
                events.append("disconnect")
        with patch.dict(sys.modules, {"bleak": types.SimpleNamespace(
                BleakClient=MockClient)}):
            result = sofabaton_ble.inspect_gatt("A1:B2")
        self.assertEqual(events, [("init", "A1:B2", False, 12.0),
                                  "connect", "disconnect"])
        self.assertEqual(result, [
            "Service 0000b001",
            "  Characteristic 0000a001 [notify, write]",
            "    Descriptor 00002902"])
        self.assertNotIn("value", "\n".join(result))

    def test_bad_duration_or_missing_selection_fails_without_access(self):
        for duration in (0, 1, 21):
            with self.assertRaises(ValueError):
                sofabaton_ble.scan_ble(duration)
        with self.assertRaises(ValueError):
            sofabaton_ble.inspect_gatt("")

    def test_ui_button_starts_read_only_lab_without_arming_ir(self):
        import os
        if sys.platform != "win32" and not os.environ.get("DISPLAY"):
            self.skipTest("GUI test requires virtual display")
        from irstudio.ui import Studio
        from irstudio import core
        with patch("irstudio.ui.core.load_library",
                   return_value=core.default_library()), \
             patch("irstudio.ui.core.save_library"):
            app = Studio()
            app.withdraw()
            try:
                self.assertIn("SofaBaton U1 Bluetooth",
                              app.sofabaton_ble_btn.cget("text"))
                with patch("irstudio.ui.SofaBatonBleDialog") as dialog:
                    app.sofabaton_ble_btn.invoke()
                    dialog.assert_called_once_with(app)
                self.assertFalse(app.running)
                self.assertEqual(app.connection_state_var.get(),
                                 "NOT CONNECTED")
            finally:
                app.destroy()


if __name__ == "__main__":
    unittest.main()
