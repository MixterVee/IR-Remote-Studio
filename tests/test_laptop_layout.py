"""Ensure maximize/resizing on a shorter laptop display does not hide actions."""
import os
import sys
import unittest
from unittest.mock import patch

from irstudio import core


def require_display(test):
    if sys.platform != "win32" and not os.environ.get("DISPLAY"):
        test.skipTest("Requires a graphical test display")


def within_window(test, widget, window):
    test.assertTrue(widget.winfo_ismapped(), "Unmapped widget: " + str(widget))
    test.assertGreater(widget.winfo_height(), 10, "Collapsed widget: " + str(widget))
    test.assertLessEqual(
        widget.winfo_rooty() + widget.winfo_height(),
        window.winfo_rooty() + window.winfo_height() + 2,
        "Bottom of " + str(widget) + " falls beyond visible window")


class LaptopControlsTests(unittest.TestCase):
    def test_main_window_actions_above_bottom_at_laptop_height(self):
        require_display(self)
        from irstudio.ui import Studio
        with patch("irstudio.ui.core.load_library",
                   return_value=core.default_library()), \
             patch("irstudio.ui.core.save_library"):
            app = Studio()
            try:
                # Maximized laptop window after taskbar has reserved its area.
                app.geometry("1160x610")
                app.update()
                for widget in (app.device_actions, app.button_actions,
                               app.button_progress, app.capture_btn,
                               app.save_button_btn, app.save_next_btn):
                    within_window(self, widget, app)
                self.assertLessEqual(
                    app.button_tree.winfo_rooty() + app.button_tree.winfo_height(),
                    app.button_progress.winfo_rooty() + 3)
                self.assertLessEqual(
                    app.button_progress.winfo_rooty() + app.button_progress.winfo_height(),
                    app.button_actions.winfo_rooty() + 3)
                for label in ("+ Add", "Rename", "Delete", "Move up",
                              "Move down", "Layout editor..."):
                    buttons = [
                        child for section in app.button_actions.winfo_children()
                        for child in section.winfo_children()
                        if child.cget("text") == label
                    ]
                    self.assertEqual(len(buttons), 1, label)
                    within_window(self, buttons[0], app)
            finally:
                app.destroy()

    def test_layout_editor_save_and_width_controls_at_minimum_height(self):
        require_display(self)
        import tkinter as tk
        from irstudio.layout_preview import LayoutDialog
        root = tk.Tk()
        root.geometry("960x610")
        dev = core.new_device("Test")
        dev["buttons"].append(core.new_button("Power"))
        try:
            dlg = LayoutDialog(root, dev, lambda: None)
            try:
                dlg.geometry("900x540")
                dlg.update()
                within_window(self, dlg.footer, dlg)
                within_window(self, dlg.width_bar, dlg)
                within_window(self, dlg.tree, dlg)
                save = [w for w in dlg.footer.winfo_children()
                        if w.winfo_class() == "TButton" and
                           w.cget("text") == "Save layout"]
                self.assertEqual(len(save), 1)
                within_window(self, save[0], dlg)
                self.assertLess(
                    dlg.tree.winfo_rooty() + dlg.tree.winfo_height(),
                    dlg.width_bar.winfo_rooty() + 15)
            finally:
                dlg.destroy()
        finally:
            root.destroy()


    def test_u1_advanced_buttons_remain_visible_on_short_laptop(self):
        require_display(self)
        from irstudio.ui import Studio
        from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
        with patch("irstudio.ui.core.load_library",
                   return_value=core.default_library()), \
             patch("irstudio.ui.core.save_library"):
            app = Studio()
            dialog = None
            try:
                app.geometry("1160x610")
                app.update()
                dialog = SofaBatonBleDialog(app, auto_scan=False)
                dialog.geometry("850x610")
                dialog.toggle_advanced()
                # Simulate Windows maximized work area minus taskbar.
                dialog.geometry("850x610")
                dialog.update()
                for widget in (dialog.advanced_toggle_button,
                               dialog.read_button,
                               dialog.save_capture_button,
                               dialog.inspect_button):
                    within_window(self, widget, dialog)
            finally:
                if dialog is not None and dialog.winfo_exists():
                    dialog.destroy()
                app.destroy()


if __name__ == "__main__":
    unittest.main()
