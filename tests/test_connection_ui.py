"""Regression tests for prominent Windows learner connection status.

Runs in GitHub Actions with virtual display. No actual USB communications.
"""
import os
import sys
import unittest
from unittest.mock import patch

from irstudio import core


class ConnectionIndicatorTests(unittest.TestCase):
    def setUp(self):
        if sys.platform != "win32" and not os.environ.get("DISPLAY"):
            self.skipTest("Graphical desktop is required")
        from irstudio.ui import Studio
        self.load_mock = patch("irstudio.ui.core.load_library",
                               return_value=core.default_library())
        self.save_mock = patch("irstudio.ui.core.save_library")
        self.load_mock.start()
        self.save_mock.start()
        self.app = Studio()
        self.app.withdraw()

    def tearDown(self):
        if hasattr(self, "app"):
            self.app.destroy()
        if hasattr(self, "save_mock"):
            self.save_mock.stop()
        if hasattr(self, "load_mock"):
            self.load_mock.stop()

    def test_initial_state_is_red_and_connect_button_obvious(self):
        self.assertEqual(self.app.probe_btn.cget("text"), "Connect / Retry")
        self.assertEqual(self.app.detect_btn.cget("text"), "Refresh USB")
        self.assertEqual(self.app.connection_state_var.get(), "NOT CONNECTED")
        self.assertEqual(str(self.app.connection_heading.cget("foreground")),
                         "#bc2525")

    def test_usb_detection_only_is_amber_not_connected(self):
        self.app._set_connection("detected", "Device found; handshake not checked")
        self.assertIn("USB DETECTED", self.app.connection_state_var.get())
        self.assertEqual(str(self.app.connection_heading.cget("foreground")),
                         "#9a6300")
        self.assertNotIn("READY", self.app.connection_state_var.get())

    def test_successful_handshake_is_green_and_does_not_claim_live_open(self):
        fake_result = {"model": "Harmony 300/350 (direct HID)",
                       "compatibility": "experimental"}
        with patch.object(self.app, "_background",
                          side_effect=lambda mode, done: done(fake_result, None)):
            self.app.probe()
        self.assertEqual(self.app.connection_state_var.get(),
                         "READY — CONNECTION VERIFIED")
        self.assertEqual(str(self.app.connection_heading.cget("foreground")),
                         "#147b3b")
        self.assertIn("session is closed",
                      self.app.connection_detail_var.get())

    def test_failed_probe_is_red_and_reconnect_guidance_is_visible(self):
        with patch.object(self.app, "_background",
                          side_effect=lambda mode, done: done(None, "USB read timeout")), \
             patch.object(self.app, "_harmony_error"):
            self.app.probe()
        self.assertEqual(self.app.connection_state_var.get(),
                         "CONNECTION FAILED — RETRY")
        self.assertEqual(str(self.app.connection_heading.cget("foreground")),
                         "#bc2525")
        self.assertIn("Unplug and reconnect",
                      self.app.connection_detail_var.get())

    def test_connection_failure_popup_shows_unplug_and_other_port_steps(self):
        with patch.object(self.app, "_background",
                          side_effect=lambda mode, done: done(None, "USB read timeout")), \
             patch("irstudio.ui.messagebox.showerror") as popup:
            self.app.probe()
        popup.assert_called_once()
        title, message = popup.call_args.args[:2]
        self.assertEqual(title, "IR Learner — Connection Failed")
        self.assertIn("Disconnect the receiver from USB", message)
        self.assertIn("working batteries", message)
        self.assertIn("different USB port", message)
        self.assertIn("Refresh USB, then Connect / Retry", message)
        self.assertIn("USB read timeout", message)
        self.assertIs(popup.call_args.kwargs["parent"], self.app)
        self.assertEqual(self.app.connection_state_var.get(),
                         "CONNECTION FAILED — RETRY")

    def test_ocrustar_failure_popup_has_driver_caution(self):
        self.app.receiver_var.set("Ocrustar / ElkSmart — USB bulk (experimental)")
        with patch("irstudio.ui.messagebox.showerror") as popup:
            self.app._harmony_error("device open failed")
        self.assertIn("Ocrustar: USB driver access", popup.call_args.args[1])
        self.assertIn("Do not replace", popup.call_args.args[1])

    def test_ready_requires_active_capture_and_ignores_stale_callback(self):
        self.app.running = True
        self.app._active_operation = 7
        self.app._capture_ready(6)
        self.assertEqual(self.app.connection_state_var.get(), "NOT CONNECTED")
        self.app._capture_ready(7)
        self.assertEqual(self.app.connection_state_var.get(),
                         "READY — PRESS A BUTTON NOW")
        self.assertEqual(str(self.app.connection_heading.cget("foreground")),
                         "#147b3b")
        self.app.running = False
        self.app._set_connection("captured", "Save complete")
        self.app._capture_ready(7)
        self.assertEqual(self.app.connection_state_var.get(), "CAPTURE COMPLETE")

    def test_switching_receiver_invalidates_old_green_state(self):
        self.app._set_connection("ready", "Harmony handshake completed")
        self.app.connected_model = "Harmony 300"
        self.app._receiver_changed()
        self.assertIsNone(self.app.connected_model)
        self.assertEqual(self.app.connection_state_var.get(), "NOT CONNECTED")
        self.assertEqual(str(self.app.connection_heading.cget("foreground")),
                         "#bc2525")


if __name__ == "__main__":
    unittest.main()
