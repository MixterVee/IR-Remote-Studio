"""Backend save/learn-another actions remain safe despite automatic UI saving.

Run with a display (GitHub's Linux smoke runner provides Xvfb).
No USB device or Harmony is contacted by these tests.
"""
import os
import sys
import unittest
from unittest.mock import patch

from irstudio import core


class SaveButtonUITests(unittest.TestCase):
    def setUp(self):
        if sys.platform != "win32" and not os.environ.get("DISPLAY"):
            self.skipTest("Tk requires a graphical display")
        from irstudio.ui import Studio
        self.load_patch = patch("irstudio.ui.core.load_library",
                                return_value=core.default_library())
        self.save_patch = patch("irstudio.ui.core.save_library")
        self.load_patch.start()
        self.save_mock = self.save_patch.start()
        self.app = Studio()
        self.app.withdraw()
        self.device = core.new_device("Test TV")
        self.button = core.new_button("Power")
        self.device["buttons"].append(self.button)
        self.app.library["devices"].append(self.device)
        self.app._reload_devices(self.device["id"])
        self.app._reload_buttons(self.button["id"])

    def tearDown(self):
        if hasattr(self, "app"):
            self.app.destroy()
        if hasattr(self, "save_patch"):
            self.save_patch.stop()
        if hasattr(self, "load_patch"):
            self.load_patch.stop()

    def test_standalone_save_confirms_without_recapturing_or_duplicating(self):
        # Visible Save buttons were removed in v1.0: capture autosaves.
        self.assertFalse(hasattr(self.app, "save_button_btn"))
        self.assertFalse(hasattr(self.app, "save_next_btn"))
        self.assertEqual(self.app.capture_btn.cget("text"),
                         "Capture selected button (USB)")
        self.button["pronto_hex"] = "0000 006D 0001 0000 0010 0020"
        with patch.object(self.app, "capture") as capture:
            self.assertTrue(self.app.save_current_button())
            capture.assert_not_called()
        self.assertEqual(len(self.device["buttons"]), 1)
        self.assertEqual(len(self.button["captures"]), 0)
        self.assertIn("no new capture started",
                      self.app.status_var.get())
        self.save_mock.assert_called()

    def test_save_and_learn_another_saves_existing_then_starts_new_button(self):
        self.button["pronto_hex"] = "0000 006D 0001 0000 0010 0020"
        with patch("irstudio.ui.simpledialog.askstring", return_value="Volume Up"), \
             patch.object(self.app, "capture") as start_capture:
            self.app.learn_another()
            start_capture.assert_called_once_with()
        self.assertEqual(len(self.device["buttons"]), 2)
        self.assertEqual(self.device["buttons"][0]["label"], "Power")
        self.assertEqual(self.device["buttons"][1]["label"], "Volume Up")
        self.assertEqual(self.app._selected_button()["label"], "Volume Up")
        self.assertEqual(len(self.button["captures"]), 0)

    def test_save_does_not_create_unlearned_duplicate(self):
        with patch("irstudio.ui.messagebox.showwarning") as warning, \
             patch.object(self.app, "capture") as capture:
            self.assertFalse(self.app.save_current_button())
            self.app.learn_another()
            capture.assert_not_called()
            self.assertEqual(len(self.device["buttons"]), 1)
            self.assertEqual(warning.call_count, 2)

    def test_save_error_refuses_to_start_next_learning(self):
        self.button["pronto_hex"] = "0000 006D 0001 0000 0010 0020"
        self.save_mock.side_effect = OSError("Disk full")
        with patch("irstudio.ui.messagebox.showerror") as error, \
             patch.object(self.app, "capture") as capture:
            self.assertFalse(self.app.save_current_button())
            self.app.learn_another()
            capture.assert_not_called()
            self.assertEqual(len(self.device["buttons"]), 1)
            self.assertEqual(error.call_count, 2)


if __name__ == "__main__":
    unittest.main()
