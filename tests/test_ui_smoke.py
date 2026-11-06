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


if __name__ == "__main__":
    unittest.main()
