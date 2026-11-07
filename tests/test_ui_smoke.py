"""Smoke-test the actual Tk app startup under a graphical display.

On Linux run under xvfb-run; on Windows the test uses the available desktop.
The test prevents the former "expected integer but got UI" startup crash.
"""
import os
import sys
import unittest
from unittest.mock import patch

from irstudio import core


class UIStartupTests(unittest.TestCase):
    def test_window_initializes_and_basic_device_flows(self):
        if sys.platform != "win32" and not os.environ.get("DISPLAY"):
            self.skipTest("Tk requires a display; use xvfb-run on Linux")
        try:
            from irstudio.ui import Studio
            with patch("irstudio.ui.core.load_library", return_value=core.default_library()), \
                 patch("irstudio.ui.core.save_library"):
                app = Studio()
                try:
                    app.withdraw()
                    app.update_idletasks()
                    self.assertIsNotNone(app.probe_btn)
                    self.assertIsNotNone(app.capture_btn)
                    self.assertIsNotNone(app.brand_image, "Expected embedded transparent logo")
                    device = core.new_device("Test TV")
                    device["buttons"].append(core.new_button("Power"))
                    app.library["devices"].append(device)
                    app._reload_devices(device["id"])
                    self.assertEqual(app._selected_device()["name"], "Test TV")
                    app._reload_buttons(device["buttons"][0]["id"])
                    self.assertEqual(app._selected_button()["label"], "Power")
                    app.update_idletasks()
                finally:
                    app.destroy()
        except Exception as error:
            if error.__class__.__name__ == "TclError" and any(
                fragment in str(error).lower()
                for fragment in ("no display", "couldn't connect to display")
            ):
                self.skipTest("No graphical desktop available")
            raise


class PhonePreviewSmokeTests(unittest.TestCase):
    def test_editor_renders_both_modes_and_saves_layout(self):
        if sys.platform != "win32" and not os.environ.get("DISPLAY"):
            self.skipTest("Graphical display required")
        import tkinter as tk
        from irstudio.layout_preview import LayoutDialog
        root = tk.Tk()
        root.withdraw()
        device = core.new_device("Generic Clock")
        one = core.new_button("SET")
        one["pronto_hex"] = "0000 006D 0002 0000 0157 0057 0016 4DFA"
        two = core.new_button("Volume +")
        two["pronto_hex"] = one["pronto_hex"]
        device["buttons"].extend([one, two])
        saved = []
        try:
            dlg = LayoutDialog(root, device, lambda: saved.append(True))
            try:
                dlg.update_idletasks()
                self.assertEqual(dlg.mode.get(), "Phone")
                self.assertEqual(dlg._rows()[0][0]["label"], "SET")
                self.assertTrue(dlg.canvas.find_all(), "Phone preview should draw")
                dlg.mode.set("Grid")
                dlg._render()
                self.assertTrue(dlg.canvas.find_all(), "Grid preview should draw")
                dlg.mode.set("Phone")
                dlg.theme.set("Light")
                dlg.screen_size.set("Large")
                dlg.text_size.set("Large")
                dlg.tree.selection_set(one["id"])
                dlg._select()
                dlg.chosen_span.set("12")
                dlg._apply_width()
                self.assertEqual(dlg.working[one["id"]], 12)
                dlg._save()
                self.assertEqual(one["span"], 12)
                self.assertEqual(saved, [True])
            finally:
                if dlg.winfo_exists():
                    dlg.destroy()
        finally:
            root.destroy()


if __name__ == "__main__":
    unittest.main()
