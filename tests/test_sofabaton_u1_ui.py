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

    def test_main_window_green_only_on_verified_u1_connection(self):
        self.app.u1_session._state(
            "CONNECTED", "SB-Test", "MOCK",
            "Identity handshake verified.")
        self.app._refresh_u1_status()
        self.assertIn("CONNECTED — READY", self.app.u1_state_var.get())
        self.assertEqual(str(self.app.u1_disconnect_btn.cget("state")), "normal")
        self.app.u1_session._state(
            "LOST", "SB-Test", "MOCK", "Device dropped BLE.")
        self.app._refresh_u1_status()
        self.assertIn("CONNECTION LOST", self.app.u1_state_var.get())
        self.assertEqual(str(self.app.u1_disconnect_btn.cget("state")), "disabled")

    def test_one_connection_indicator_prefers_active_u1_over_usb_warning(self):
        # Connecting U1 must hide an unused USB receiver's red warning.
        self.assertEqual(self.app.connection_state_var.get(), "NOT CONNECTED")
        self.assertEqual(str(self.app.connection_heading.cget("foreground")), "#bc2525")
        self.app._set_connection("failed", "USB handshake failed.")
        self.app.u1_session._state(
            "CONNECTED", "SB202006231D9E", "MOCK", "U1 identity verified.")
        self.app._refresh_u1_status()
        self.assertEqual(
            self.app.connection_state_var.get(),
            "CONNECTED — SofaBaton U1 (SB202006231D9E)")
        self.assertEqual(str(self.app.connection_heading.cget("foreground")), "#147b3b")
        self.assertEqual(self.app.u1_disconnect_btn.winfo_manager(), "pack")
        # A fresh USB failure must not overwrite a healthy Bluetooth link.
        self.app._set_connection("not-connected", "No supported USB learner.")
        self.assertIn("CONNECTED — SofaBaton U1", self.app.connection_state_var.get())
        self.app.u1_session._state("LOST", "SB202006231D9E", "MOCK", "Dropped.")
        self.app._refresh_u1_status()
        self.assertEqual(self.app.connection_state_var.get(), "NOT CONNECTED")
        self.assertEqual(str(self.app.connection_heading.cget("foreground")), "#bc2525")
        self.assertEqual(self.app.u1_disconnect_btn.winfo_manager(), "")

    def test_usb_verified_is_green_when_bluetooth_is_not_connected(self):
        self.app.connected_model = "Harmony 300/350 (direct HID)"
        self.app._set_connection("ready", "USB handshake verified.")
        self.assertEqual(
            self.app.connection_state_var.get(),
            "CONNECTED — Harmony 300/350 (direct HID)")
        self.assertEqual(str(self.app.connection_heading.cget("foreground")), "#147b3b")
        # An unexpected U1 disconnect must not erase verified USB readiness.
        self.app.u1_session._state("LOST", "SB-Test", "MOCK", "Disconnected.")
        self.app._refresh_u1_status()
        self.assertIn("Harmony 300/350", self.app.connection_state_var.get())
        self.app.connected_model = None
        self.app._set_connection("not-connected", "USB unplugged.")
        self.assertEqual(self.app.connection_state_var.get(), "NOT CONNECTED")

    def test_main_window_has_one_visible_connection_banner(self):
        # Not two competing banners and not an always-visible BLE detail.
        self.assertFalse(hasattr(self.app, "u1_connection_label"))
        self.assertFalse(hasattr(self.app, "connection_description"))
        self.assertFalse(hasattr(self.app, "u1_connection_description"))
        self.assertFalse(hasattr(self.app, "connection_detail_var"))
        self.assertEqual(str(self.app.connection_heading.cget("textvariable")),
                         str(self.app.connection_state_var))

    def test_explicit_connect_prompt_does_not_pair_when_cancelled(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        try:
            self.assertFalse(dlg.pair_var.get())
            dlg._devices = [BleEntry(
                name="SB-TEST", address="MOCK", rssi=-40,
                advertised_services=("0000fff0-0000-1000-8000-00805f9b34fb",),
                likely_sofabaton=True)]
            dlg.device_list.insert("end", "SB-TEST")
            dlg.device_list.selection_set(0)
            dlg._select_device()
            with patch("irstudio.sofabaton_ble_ui.messagebox.askokcancel",
                       return_value=False), \
                 patch.object(dlg, "_run") as background:
                dlg.connect_u1()
                background.assert_not_called()
        finally:
            dlg.destroy()

    def test_simple_screen_hides_slot_and_diagnostics_by_default(self):
        """Normal users only see Connect / Disconnect and Learn IR status."""
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dialog = SofaBatonBleDialog(self.app, auto_scan=False)
        dialog.withdraw()
        try:
            self.assertEqual(dialog.advanced_panel.winfo_manager(), "")
            self.assertFalse(dialog.advanced_open)
            self.assertEqual(str(dialog.learn_button.cget("state")), "disabled")
            self.assertEqual(str(dialog.learn_button.cget("text")), "Learn IR")
            self.assertEqual(str(dialog.connect_button.cget("text")), "Connect U1")
            self.assertEqual(str(dialog.disconnect_button.cget("text")), "Disconnect")
            # Neither slot selection nor optional Windows pairing belongs
            # in the normal user-facing Bluetooth connection workflow.
            self.assertEqual(dialog.slot_combo.master, dialog.read_button.master)
            self.assertNotEqual(dialog.pair_check.master, dialog)
            with patch.object(self.app.u1_session, "connect") as connect, \
                 patch.object(self.app.u1_session, "read_saved_ir") as read:
                dialog.toggle_advanced()
                self.assertTrue(dialog.advanced_open)
                self.assertEqual(dialog.advanced_panel.winfo_manager(), "pack")
                dialog.toggle_advanced()
                self.assertFalse(dialog.advanced_open)
                self.assertEqual(dialog.advanced_panel.winfo_manager(), "")
                connect.assert_not_called()
                read.assert_not_called()
        finally:
            dialog.destroy()

    def test_advanced_read_controls_track_verified_session(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dialog = SofaBatonBleDialog(self.app, auto_scan=False)
        dialog.withdraw()
        try:
            device = BleEntry(
                name="SB-TEST", address="TEST", rssi=-40,
                advertised_services=("0000fff0-0000-1000-8000-00805f9b34fb",),
                likely_sofabaton=True)
            dialog._devices = [device]
            dialog.device_list.insert("end", "SB-TEST")
            dialog.device_list.selection_set(0)
            dialog._select_device()
            self.assertEqual(str(dialog.read_button.cget("state")), "disabled")
            self.app.u1_session._state(
                "CONNECTED", "SB-TEST", "TEST", "Identity verified.")
            dialog._poll_connection()
            self.assertEqual(str(dialog.read_button.cget("state")), "normal")
            self.app.u1_session._state("LOST", "SB-TEST", "TEST", "Dropped.")
            dialog._poll_connection()
            self.assertEqual(str(dialog.read_button.cget("state")), "disabled")
        finally:
            dialog.destroy()

    def test_open_starts_scan_without_prompting_for_connection(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        import unittest.mock as mock
        svc = "0000fff0-0000-1000-8000-00805f9b34fb"
        target = BleEntry("SB202006231D9E", "U1-ADDRESS", -51, (svc,), True)
        other = BleEntry("Other-device", "OTHER", -20, (), False)
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        try:
            self.assertIn("Hold the - and E buttons", dlg.winfo_children()[0].winfo_children()[1].cget("text"))
        def connected(address, name, pair):
            self.app.u1_session._state(
                "CONNECTED", name, address, "U1 identity verified.")
            return self.app.u1_session.snapshot()
        with patch("irstudio.sofabaton_ble_ui.ble.scan_ble",
                   return_value=[other, target]) as scan, \
             patch.object(self.app.u1_session, "connect",
                          side_effect=connected) as connect, \
             patch.object(dlg, "_run",
                          side_effect=lambda name, task, complete: complete(task())), \
             patch("irstudio.sofabaton_ble_ui.messagebox.askokcancel") as popup:
            dlg.scan()
        scan.assert_called_once_with(7.0)
        connect.assert_called_once_with(
            "U1-ADDRESS", "SB202006231D9E", pair=False)
        popup.assert_not_called()
        self.assertFalse(dlg.winfo_exists())
        self.assertEqual(
            self.app.connection_state_var.get(),
            "CONNECTED — SofaBaton U1 (SB202006231D9E)")

    def test_auto_scan_on_open_skips_redundant_scan_if_connected(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        try:
            with patch.object(dlg, "scan") as scan:
                dlg._auto_scan_on_open()
                scan.assert_called_once()
                self.app.u1_session._state(
                    "CONNECTED", "SB202006231D9E", "U1", "identity verified")
                dlg._auto_scan_on_open()
                scan.assert_called_once()
        finally:
            if dlg.winfo_exists():
                dlg.destroy()

    def test_multiple_sb_devices_remain_manual_without_auto_connect(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        svc = ("0000fff0-0000-1000-8000-00805f9b34fb",)
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        try:
            devices = [
                BleEntry("SB202006231D9E", "ONE", -51, svc, True),
                BleEntry("SB202006231D9F", "TWO", -49, svc, True)]
            with patch("irstudio.sofabaton_ble_ui.ble.scan_ble",
                       return_value=devices), \
                 patch.object(self.app.u1_session, "connect") as connect, \
                 patch.object(dlg, "_run",
                              side_effect=lambda name, task, complete: complete(task())):
                dlg.scan()
            connect.assert_not_called()
            self.assertTrue(dlg.winfo_exists())
            self.assertIn("More than one U1", dlg.status_var.get())
        finally:
            dlg.destroy()

    def test_auto_connection_retries_windows_pairing_only_on_auth_error(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        svc = ("0000fff0-0000-1000-8000-00805f9b34fb",)
        device = BleEntry("SB202006231D9E", "U1", -51, svc, True)
        calls = []
        def connect(address, name, pair):
            calls.append(pair)
            if not pair:
                raise RuntimeError("GATT authentication required")
            self.app.u1_session._state(
                "CONNECTED", name, address, "Identity verified")
            return self.app.u1_session.snapshot()
        with patch.object(self.app.u1_session, "connect",
                          side_effect=connect), \
             patch.object(dlg, "_run",
                          side_effect=lambda name, task, complete: complete(task())):
            dlg._connect_device(device, automatic=True)
        self.assertEqual(calls, [False, True])
        self.assertEqual(
            self.app.connection_state_var.get(),
            "CONNECTED — SofaBaton U1 (SB202006231D9E)")
        self.assertFalse(dlg.winfo_exists())

    def test_u1_reader_action_prompts_before_queries(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dialog = SofaBatonBleDialog(self.app, auto_scan=False)
        dialog.withdraw()
        try:
            self.assertEqual(dialog.read_button.cget("text"), "Read stored IR (test)")
            self.assertEqual(str(dialog.save_capture_button.cget("state")), "disabled")
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
