"""Release-candidate UI behavior; never touches USB/BLE hardware."""
import os
import sys
import tkinter as tk
import unittest
from unittest.mock import patch

from irstudio import __version__, core


class ReleasePresentationTests(unittest.TestCase):
    def setUp(self):
        if sys.platform != "win32" and not os.environ.get("DISPLAY"):
            self.skipTest("Needs a Tk display")
        from irstudio.ui import Studio
        self.load_patch = patch("irstudio.ui.core.load_library",
                                return_value=core.default_library())
        self.save_patch = patch("irstudio.ui.core.save_library")
        self.load_patch.start()
        self.save_patch.start()
        self.app = Studio()

    def tearDown(self):
        if hasattr(self, "app") and self.app.winfo_exists():
            self.app.destroy()
        if hasattr(self, "save_patch"):
            self.save_patch.stop()
        if hasattr(self, "load_patch"):
            self.load_patch.stop()

    @staticmethod
    def all_children(widget):
        result = []
        for child in widget.winfo_children():
            result.append(child)
            result.extend(ReleasePresentationTests.all_children(child))
        return result

    def test_version_and_about_shows_real_features_and_data_location(self):
        self.assertEqual(__version__, "1.0.0-rc2")
        self.assertIn(__version__, self.app.title())
        dialog = self.app.show_about()
        try:
            dialog.update()
            all_widgets = self.all_children(dialog)
            labels = [w.cget("text") for w in all_widgets
                      if isinstance(w, (tk.Label, __import__(
                          "tkinter.ttk", fromlist=["Label"]).Label))]
            display = "\n".join(labels)
            self.assertIn("1.0.0-rc2", display)
            self.assertIn("SofaBaton U1", display)
            self.assertIn("Harmony 300/350", display)
            self.assertIn("License: MIT", display)
            self.assertIn(str(self.app.folder), display)
            buttons = [w.cget("text") for w in all_widgets
                       if w.winfo_class() == "TButton"]
            self.assertIn("Project on GitHub", buttons)
            self.assertIn("Open data folder", buttons)
            self.assertIn("Close", buttons)
        finally:
            if dialog.winfo_exists():
                dialog.destroy()

    def test_main_screen_has_only_capture_not_redundant_manual_save(self):
        from tkinter import ttk
        self.app.update_idletasks()
        self.assertEqual(self.app.probe_btn.cget("text"), "Connect USB")
        self.assertEqual(self.app.detect_btn.cget("text"), "Detect USB")
        self.assertEqual(self.app.sofabaton_ble_btn.cget("text"),
                         "SofaBaton U1...")
        self.assertFalse(hasattr(self.app, "save_button_btn"))
        self.assertFalse(hasattr(self.app, "save_next_btn"))
        labels = [w.cget("text") for w in self.all_children(self.app)
                  if isinstance(w, ttk.Label)]
        self.assertIn("Successful captures save automatically.", labels)

    def test_bluetooth_dialog_omits_obsolete_learn_controls(self):
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        dialog = SofaBatonBleDialog(self.app, auto_scan=False)
        try:
            dialog.update_idletasks()
            self.assertFalse(hasattr(dialog, "learn_button"))
            self.assertFalse(hasattr(dialog, "experimental_learn_button"))
            self.assertFalse(dialog.advanced_open)
            self.assertEqual(dialog.advanced_panel.winfo_manager(), "")
            self.assertEqual(dialog.read_button.cget("text"),
                             "Read stored code")
            self.assertIn("Troubleshooting",
                          dialog.advanced_toggle_button.cget("text"))
        finally:
            dialog.destroy()


if __name__ == "__main__":
    unittest.main()
