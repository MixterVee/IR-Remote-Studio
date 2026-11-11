"""No-hardware GUI tests of U1 stored IR -> Windows library save action."""
import os
import sys
import unittest
from unittest.mock import patch

from irstudio import core
# Save the unmocked functions before setUp patches the shared core module.
from irstudio.core import load_library as real_load_library
from irstudio.core import save_library as real_save_library
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

    def test_disconnect_is_red_and_immediately_beside_connected_name(self):
        # Regression for previously far-right disconnect control.
        # This test suite withdraws Studio by default. Geometry measurements
        # are meaningless until the window is actually mapped to the display.
        self.app.deiconify()
        self.app.geometry("1490x900")
        self.app.u1_session._state(
            "CONNECTED", "SB202006231D9E", "MOCK", "Identity verified.")
        self.app._refresh_u1_status()
        self.app.update()
        button = self.app.u1_disconnect_btn
        heading = self.app.connection_heading
        self.assertEqual(button.cget("background"), "#b42318")
        self.assertEqual(button.cget("foreground"), "#ffffff")
        self.assertEqual(str(button.cget("state")), "normal")
        self.assertEqual(button.winfo_manager(), "pack")
        horizontal_gap = button.winfo_rootx() - (
            heading.winfo_rootx() + heading.winfo_width())
        self.assertGreaterEqual(horizontal_gap, 8)
        self.assertLessEqual(horizontal_gap, 24)
        # The button shares the heading's row, not a right-aligned column.
        self.assertLessEqual(
            abs(button.winfo_rooty() + button.winfo_height() / 2 -
                heading.winfo_rooty() - heading.winfo_height() / 2), 12)
        self.app.u1_session._state(
            "LOST", "SB202006231D9E", "MOCK", "Link dropped.")
        self.app._refresh_u1_status()
        self.app.update()
        self.assertEqual(button.winfo_manager(), "")

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
            self.assertFalse(hasattr(dialog, "learn_button"))
            self.assertFalse(hasattr(dialog, "experimental_learn_button"))
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
        svc = "0000fff0-0000-1000-8000-00805f9b34fb"
        target = BleEntry("SB202006231D9E", "U1-ADDRESS", -51, (svc,), True)
        other = BleEntry("Other-device", "OTHER", -20, (), False)
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        self.assertIn(
            "Hold the - and E buttons",
            dlg.winfo_children()[0].winfo_children()[1].cget("text"))
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

    def test_scan_shows_only_searching_then_connected_not_other_devices(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        svc = ("0000fff0-0000-1000-8000-00805f9b34fb",)
        target = BleEntry("SB202006231D9E", "U1-MAC", -49, svc, True)
        unrelated = BleEntry("Neighbor's Smart TV", "TV-MAC", -36, (), False)
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        try:
            self.assertEqual(dlg.manual_frame.winfo_manager(), "")
            self.assertEqual(dlg.connect_button.winfo_manager(), "")
            self.assertIn("SEARCHING", dlg.link_var.get())
            def connected(address, name, pair):
                self.app.u1_session._state(
                    "CONNECTED", name, address, "Verified U1")
                return self.app.u1_session.snapshot()
            with patch("irstudio.sofabaton_ble_ui.ble.scan_ble",
                       return_value=[unrelated, target]), \
                 patch.object(self.app.u1_session, "connect",
                              side_effect=connected), \
                 patch.object(dlg, "_run",
                              side_effect=lambda name, task, complete:
                                  complete(task())):
                dlg.scan()
            self.assertFalse(dlg.winfo_exists())
            self.assertEqual(self.app.u1_session.snapshot().name, target.name)
        finally:
            if dlg.winfo_exists():
                dlg.destroy()

    def test_ambiguous_u1_scan_shows_only_u1_candidates_in_manual_fallback(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        svc = ("0000fff0-0000-1000-8000-00805f9b34fb",)
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        try:
            devices = [BleEntry("Nearby phone", "PHONE", -30, (), False),
                       BleEntry("SB11111111", "U1-A", -61, svc, True),
                       BleEntry("SB22222222", "U1-B", -62, svc, True)]
            with patch("irstudio.sofabaton_ble_ui.ble.scan_ble",
                       return_value=devices), \
                 patch.object(dlg, "_run",
                              side_effect=lambda name, task, complete:
                                  complete(task())):
                dlg.scan()
            self.assertEqual(dlg.manual_frame.winfo_manager(), "pack")
            self.assertEqual(dlg.connect_button.winfo_manager(), "pack")
            self.assertEqual(list(dlg.device_list.get(0, "end")),
                             ["SB11111111", "SB22222222"])
            self.assertNotIn("Nearby phone", str(dlg.device_list.get(0, "end")))
            self.assertIn("MULTIPLE U1", dlg.link_var.get())
        finally:
            dlg.destroy()

    def test_empty_scan_hides_others_and_explains_retry(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        try:
            with patch("irstudio.sofabaton_ble_ui.ble.scan_ble",
                       return_value=[]), \
                 patch.object(dlg, "_run",
                              side_effect=lambda name, task, complete:
                                  complete(task())):
                dlg.scan()
            self.assertIn("NO U1 FOUND", dlg.link_var.get())
            self.assertEqual(dlg.manual_frame.winfo_manager(), "")
        finally:
            dlg.destroy()

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

    def test_main_screen_reuses_proven_dialog_worker_without_confirmation(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dlg = SofaBatonBleDialog(self.app, auto_scan=False)
        dlg.withdraw()
        try:
            svc = ("0000fff0-0000-1000-8000-00805f9b34fb",)
            dlg._devices = [BleEntry(
                "SB202006231D9E", "U1", -50, svc, True)]
            dlg.device_list.insert("end", "SB202006231D9E")
            dlg.device_list.selection_set(0)
            self.app.u1_session._state(
                "CONNECTED", "SB202006231D9E", "U1", "Identity verified.")
            dlg._poll_connection()
            self.assertFalse(hasattr(dlg, "experimental_learn_button"))
            with patch.object(dlg, "_run") as worker, \
                 patch("irstudio.sofabaton_ble_ui.messagebox.askokcancel") as ask:
                dlg.start_main_capture(lambda value: None, lambda err: None)
                worker.assert_called_once()
                self.assertEqual(worker.call_args.args[0], "SofaBaton learning")
                ask.assert_not_called()
        finally:
            dlg.destroy()

    def test_main_screen_capture_dispatches_to_connected_u1_in_auto_mode(self):
        self.prepare_button()
        self.app.u1_session._state(
            "CONNECTED", "SB202006231D9E", "U1", "Identity verified.")
        self.app._refresh_u1_status()
        with patch.object(self.app, "_capture_ble_main") as bluetooth, \
             patch.object(self.app, "_background") as usb:
            self.app.capture()
            bluetooth.assert_called_once_with()
            usb.assert_not_called()

    def test_connecting_u1_switches_capture_away_from_stale_harmony_choice(self):
        from irstudio.ui import U1_RECEIVER
        from irstudio.receivers import HARMONY
        self.prepare_button()
        self.app.receiver_var.set(HARMONY)
        self.app._receiver_changed()
        self.assertIn("USB", str(self.app.capture_btn.cget("text")))
        self.app.u1_session._state(
            "CONNECTED", "SB202006231D9E", "U1", "Identity verified.")
        self.app._refresh_u1_status()
        self.assertEqual(self.app.receiver_var.get(), U1_RECEIVER)
        self.assertIn("SofaBaton U1", str(self.app.capture_btn.cget("text")))
        self.assertEqual(str(self.app.probe_btn.cget("text")), "Connect U1")
        with patch.object(self.app, "_capture_ble_main") as bluetooth, \
             patch.object(self.app, "_background") as usb:
            self.app.capture()
            bluetooth.assert_called_once_with()
            usb.assert_not_called()

    def test_connected_u1_can_still_explicitly_select_harmony_usb(self):
        from irstudio.ui import U1_RECEIVER
        from irstudio.receivers import HARMONY
        self.prepare_button()
        self.app.u1_session._state(
            "CONNECTED", "SB202006231D9E", "U1", "Identity verified.")
        self.app._refresh_u1_status()
        self.assertEqual(self.app.receiver_var.get(), U1_RECEIVER)
        self.app.receiver_var.set(HARMONY)
        self.app._receiver_changed()
        self.assertIn("USB", str(self.app.capture_btn.cget("text")))
        with patch.object(self.app, "_capture_ble_main") as bluetooth, \
             patch.object(self.app, "_background") as usb:
            self.app.capture()
            bluetooth.assert_not_called()
            usb.assert_called_once()

    def test_disconnected_u1_never_falls_back_to_harmony(self):
        from irstudio.ui import U1_RECEIVER
        self.prepare_button()
        self.app.receiver_var.set(U1_RECEIVER)
        self.app._receiver_changed()
        with patch.object(self.app, "_capture_ble_main") as bluetooth, \
             patch.object(self.app, "_background") as usb, \
             patch("irstudio.ui.messagebox.showwarning") as warning:
            self.app.capture()
            bluetooth.assert_not_called()
            usb.assert_not_called()
            self.assertIn("Connect U1", warning.call_args.args[1])

    def test_connect_u1_button_never_runs_usb_probe(self):
        from irstudio.ui import U1_RECEIVER
        self.app.receiver_var.set(U1_RECEIVER)
        self.app._receiver_changed()
        with patch.object(self.app, "open_sofabaton_ble") as connect, \
             patch.object(self.app, "_background") as usb:
            self.app.probe()
            connect.assert_called_once()
            usb.assert_not_called()

    def test_ble_main_capture_saves_to_original_selected_button(self):
        device, button = self.prepare_button()
        # The same valid, non-NEC raw IR waveform v0.3.3 can capture.
        ir = IrRead(8, 38095, [3996, 3999] + [469, 2000] * 51, 37)
        self.app.running = True
        with patch("irstudio.ui.core.load_library",
                   return_value=self.app.library), \
             patch.object(self.app, "after") as later:
            self.app._finish_ble_main(
                device["id"], button["id"], (ir, "U1-Learn-Backups/old.json"))
            later.assert_called_once()
        self.assertFalse(self.app.running)
        self.assertEqual(len(button["captures"]), 1)
        self.assertEqual(button["captures"][0]["carrier_hz"], 38095)
        self.assertIn("AUTO-SAVED", self.app.status_var.get())
        self.assertEqual(self.app.button_tree.item(button["id"], "values")[3],
                         "Raw IR (saved)")
        self.save_mock.assert_called()

    def test_auto_save_is_read_back_from_disk_before_success_prompt(self):
        import tempfile
        from pathlib import Path
        device, button = self.prepare_button()
        ir = IrRead(8, 38095, [3996, 3999] + [469, 2000] * 51, 37)
        with tempfile.TemporaryDirectory() as tmp:
            self.app.library_path = Path(tmp) / "library.json"
            self.app.running = True
            with patch("irstudio.ui.core.save_library",
                       side_effect=real_save_library), \
                 patch("irstudio.ui.core.load_library",
                       side_effect=real_load_library), \
                 patch.object(self.app, "after") as prompt:
                self.app._finish_ble_main(
                    device["id"], button["id"], (ir, "backup.json"))
                prompt.assert_called_once()
            saved = real_load_library(self.app.library_path)
            codes = saved["devices"][0]["buttons"][0]["captures"]
            self.assertEqual(len(codes), 1)
            self.assertEqual(codes[0]["carrier_hz"], 38095)
            self.assertEqual(codes[0]["timings_us"], ir.timings_us)
            self.assertIn("AUTO-SAVED", self.app.status_var.get())

    def test_failed_save_rolls_back_without_success_prompt(self):
        device, button = self.prepare_button()
        ir = IrRead(8, 38095, [3996, 3999] + [469, 2000] * 51, 37)
        self.save_mock.side_effect = OSError("Disk full")
        self.app.running = True
        with patch.object(self.app, "after") as prompt, \
             patch("irstudio.ui.messagebox.showerror") as error:
            self.app._finish_ble_main(
                device["id"], button["id"], (ir, "backup.json"))
            prompt.assert_not_called()
            error.assert_called_once()
        self.assertEqual(button["captures"], [])
        self.assertIn("saving it failed", self.app.status_var.get())

    def test_autosave_followup_offers_new_existing_finish(self):
        from tkinter import ttk
        device, button = self.prepare_button()
        older = core.new_button("Previous button")
        device["buttons"].append(older)
        self.app._reload_buttons(button["id"])
        dialog = self.app._after_successful_ble_capture(
            device["id"], button["id"], 2)
        self.assertTrue(dialog.winfo_exists())
        self.assertEqual(dialog.title(), "IR command auto-saved")
        widgets = []
        def walk(parent):
            for child in parent.winfo_children():
                widgets.append(child)
                walk(child)
        walk(dialog)
        labels = [x.cget("text") for x in widgets
                  if isinstance(x, ttk.Button)]
        self.assertEqual(labels, [
            "Learn selected existing", "New button", "Finish"])
        combo = next(x for x in widgets if isinstance(x, ttk.Combobox))
        self.assertEqual(tuple(combo["values"]),
                         ("1. Power", "2. Previous button"))
        self.assertEqual(combo.current(), 0)
        combo.current(1)
        with patch.object(self.app, "after") as schedule, \
             patch.object(self.app, "capture") as do_capture:
            next_existing = next(
                x for x in widgets if isinstance(x, ttk.Button)
                and x.cget("text") == "Learn selected existing")
            next_existing.invoke()
            self.assertFalse(dialog.winfo_exists())
            self.assertEqual(self.app.active_button, older["id"])
            self.assertEqual(len(device["buttons"]), 2)
            schedule.assert_called_once_with(0, self.app.capture)
            do_capture.assert_not_called()
        self.assertIn("Previous button", self.app.status_var.get())

    def test_new_button_choice_still_uses_existing_name_prompt(self):
        from tkinter import ttk
        device, button = self.prepare_button()
        dialog = self.app._after_successful_ble_capture(
            device["id"], button["id"], 2)
        with patch.object(self.app, "after") as scheduled:
            def find_action(parent):
                for child in parent.winfo_children():
                    if (isinstance(child, ttk.Button)
                            and child.cget("text") == "New button"):
                        return child
                    found = find_action(child)
                    if found:
                        return found
            find_action(dialog).invoke()
            scheduled.assert_called_once_with(0, self.app.learn_another)
        self.assertFalse(dialog.winfo_exists())
        self.assertEqual(len(device["buttons"]), 1)

    def test_finish_choice_does_not_start_capture(self):
        from tkinter import ttk
        device, button = self.prepare_button()
        dialog = self.app._after_successful_ble_capture(
            device["id"], button["id"], 2)
        with patch.object(self.app, "after") as scheduled:
            def find_action(parent):
                for child in parent.winfo_children():
                    if (isinstance(child, ttk.Button)
                            and child.cget("text") == "Finish"):
                        return child
                    found = find_action(child)
                    if found:
                        return found
            find_action(dialog).invoke()
            scheduled.assert_not_called()
        self.assertFalse(dialog.winfo_exists())
        self.assertIn("Learning finished", self.app.status_var.get())

    def test_clear_mismatched_captures_keeps_button_and_layout(self):
        device, button = self.prepare_button()
        button["span"] = 4
        button["captures"] = [
            core.make_capture(38095, [3996, 3999] + [469, 2000] * 51),
            core.make_capture(38095, [4000, 4000] + [560, 1600] * 51),
        ]
        button["selected"] = 1
        self.app._reload_buttons(button["id"])
        self.assertEqual(core.capture_status(button), "Mismatch")
        with patch("irstudio.ui.messagebox.askyesno",
                   return_value=True) as confirm, \
             patch.object(self.app, "_capture_ble_main") as rearm:
            self.app.clear_button_captures()
            confirm.assert_called_once()
            rearm.assert_not_called()
        self.assertEqual(device["buttons"], [button])
        self.assertEqual(button["label"], "Power")
        self.assertEqual(button["span"], 4)
        self.assertEqual(button["captures"], [])
        self.assertEqual(button["selected"], 0)
        self.assertEqual(self.app.active_button, button["id"])
        self.assertEqual(core.capture_status(button), "Not learned")
        self.assertIn("Button retained", self.app.status_var.get())
        self.save_mock.assert_called()

    def test_clear_captures_cancel_and_disk_failure_preserve_samples(self):
        _, button = self.prepare_button()
        button["captures"] = [
            core.make_capture(38095, [3996, 3999] + [469, 2000] * 51)]
        original = list(button["captures"])
        with patch("irstudio.ui.messagebox.askyesno", return_value=False):
            self.app.clear_button_captures()
        self.assertEqual(button["captures"], original)
        self.save_mock.assert_not_called()
        self.save_mock.side_effect = OSError("Disk full")
        with patch("irstudio.ui.messagebox.askyesno", return_value=True), \
             patch("irstudio.ui.messagebox.showerror") as error:
            self.app.clear_button_captures()
            error.assert_called_once()
        self.assertEqual(button["captures"], original)

    def test_remove_last_can_fix_mismatch_without_deleting_button(self):
        device, button = self.prepare_button()
        original = core.make_capture(
            38095, [3996, 3999] + [469, 2000] * 51)
        bad = core.make_capture(
            38095, [4000, 4000] + [560, 1600] * 51)
        button["captures"] = [original, original.copy(), bad]
        self.app._reload_buttons(button["id"])
        self.assertEqual(core.capture_status(button), "Mismatch")
        with patch("irstudio.ui.messagebox.askyesno", return_value=True):
            self.app.remove_capture()
        self.assertEqual(device["buttons"], [button])
        self.assertEqual(button["captures"], [original, original.copy()])
        self.assertEqual(core.capture_status(button), "Verified")
        self.assertEqual(self.app.active_button, button["id"])

    def test_u1_reader_action_prompts_before_queries(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dialog = SofaBatonBleDialog(self.app, auto_scan=False)
        dialog.withdraw()
        try:
            self.assertEqual(dialog.read_button.cget("text"), "Read stored code")
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
