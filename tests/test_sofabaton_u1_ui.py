"""No-hardware GUI tests of U1 stored IR -> Windows library save action."""
import os
import sys
import unittest
from unittest.mock import patch

from irstudio import core
from irstudio.sofabaton_u1_read import IrRead
from irstudio.sofabaton_ble import BleEntry


class U1SaveUITests(unittest.TestCase):
    def setUp(self):
        if sys.platform != "win32" and not os.environ.get("DISPLAY"):
            self.skipTest("Tk GUI requires display")
        from irstudio.ui import Studio
        self.loader = patch("irstudio.ui.core.load_library",
                            return_value=core.default_library())
        self.saver = patch("irstudio.ui.core.save_library")
        self.loader.start()
        self.save_mock = self.saver.start()
        self.app = Studio()
        self.app.withdraw()

    def tearDown(self):
        if hasattr(self, "app"):
            self.app.destroy()
        if hasattr(self, "saver"):
            self.saver.stop()
        if hasattr(self, "loader"):
            self.loader.stop()

    def prepare_button(self):
        device = core.new_device("Test remote")
        button = core.new_button("Power")
        device["buttons"].append(button)
        self.app.library["devices"].append(device)
        self.app._reload_devices(device["id"])
        self.app._reload_buttons(button["id"])
        return device, button

    def test_save_ble_ir_to_selected_button_and_rollback_on_disk_failure(self):
        _, button = self.prepare_button()
        timings = [4000, 4000] + [560, 1690] * 25
        self.assertEqual(self.app.store_external_capture(
            38095, timings, "U1 BLE (stored slot 8)"), "Power")
        self.assertEqual(len(button["captures"]), 1)
        self.assertEqual(button["captures"][0]["carrier_hz"], 38095)
        self.save_mock.side_effect = OSError("Disk full")
        with self.assertRaises(OSError):
            self.app.store_external_capture(
                38095, timings, "U1 BLE (stored slot 8)")
        self.assertEqual(len(button["captures"]), 1)

    def test_save_requires_selected_existing_button(self):
        with self.assertRaisesRegex(ValueError, "Select a destination"):
            self.app.store_external_capture(
                38000, [4000, 4000]+[560, 1600]*25, "U1")

    def test_u1_reader_action_prompts_before_queries(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dialog = SofaBatonBleDialog(self.app)
        dialog.withdraw()
        try:
            self.assertEqual(dialog.read_button.cget("text"), "Read stored IR (test)")
            self.assertEqual(dialog.save_capture_button.cget("state"), "disabled")
            dialog._devices = [BleEntry(
                name="SB-synthetic", address="FAKE-U1", rssi=-40,
                advertised_services=("0000fff0-0000-1000-8000-00805f9b34fb",),
                likely_sofabaton=True)]
            dialog.device_list.insert("end", "SB-synthetic")
            dialog.device_list.selection_set(0)
            dialog._select_device()
            with patch("irstudio.sofabaton_ble_ui.messagebox.askokcancel",
                       return_value=False), \
                 patch.object(dialog, "_run") as work:
                dialog.read_ir()
                work.assert_not_called()
        finally:
            dialog.destroy()


if __name__ == "__main__":
    unittest.main()
