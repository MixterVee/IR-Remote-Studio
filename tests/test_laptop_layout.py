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
                for label in ("+ Add", "Rename", "Delete"):
                    buttons = [
                        child for section in app.button_actions.winfo_children()
                        if section.winfo_class() == "TFrame"
                        for child in section.winfo_children()
                        if child.cget("text") == label
                    ]
                    self.assertEqual(len(buttons), 1, label)
                    within_window(self, buttons[0], app)
                # Appearance controls live in the separate layout editor.
                within_window(self, app.layout_editor_btn, app)
                self.assertEqual(app.layout_editor_btn.cget("text"),
                                 "Layout editor...")
                self.assertFalse(hasattr(app, "_secondary_action_buttons"))
            finally:
                app.destroy()

    def test_squeezed_window_reflows_all_delete_and_layout_controls(self):
        """At laptop width, the final button of each action row must fit."""
        require_display(self)
        from irstudio.ui import Studio
        with patch("irstudio.ui.core.load_library",
                   return_value=core.default_library()), \
             patch("irstudio.ui.core.save_library"):
            app = Studio()
            try:
                app.geometry("1100x760")
                app.update()
                # Simulate the narrow individual panes shown in the screenshot.
                app._fit_action_bars(
                    app.device_actions, 275,
                    (app._device_action_buttons,), 350)
                app._fit_action_bars(
                    app.button_actions, 350,
                    (app._primary_action_buttons,), 415)
                app.update()
                for group in (app._device_action_buttons,
                              app._primary_action_buttons):
                    self.assertEqual(group[-1].grid_info()["row"], 1)
                    for widget in group:
                        within_window(self, widget, app)
                        self.assertGreaterEqual(
                            widget.winfo_width(),
                            widget.winfo_reqwidth() - 6,
                            f"Clipped action {widget.cget('text')}")
                # Expanding the window reclaims the natural single-row layout.
                app._fit_action_bars(
                    app.device_actions, 550,
                    (app._device_action_buttons,), 350)
                app._fit_action_bars(
                    app.button_actions, 760,
                    (app._primary_action_buttons,), 415)
                # Grid placement changes immediately. Calling app.update() on
                # this deliberately still-narrow window re-triggers <Configure>
                # and legitimately puts it back in two-column mode.
                for group in (app._device_action_buttons,
                              app._primary_action_buttons):
                    self.assertEqual(group[-1].grid_info()["row"], 0)
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
                within_window(self, dlg.order_bar, dlg)
                within_window(self, dlg.move_up_btn, dlg)
                within_window(self, dlg.move_down_btn, dlg)
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


class LayoutOrderTests(unittest.TestCase):
    """Appearance controls must be transactional and affect IRplus order."""

    @staticmethod
    def _device():
        device = core.new_device("Layout test")
        for label in ("Select", "Right", "Input"):
            button = core.new_button(label)
            button["pronto_hex"] = "0000 006D 0002 0000 0157 0057 0016 4DFA"
            device["buttons"].append(button)
        return device

    def test_move_preview_then_cancel_preserves_actual_device(self):
        require_display(self)
        import tkinter as tk
        from irstudio.layout_preview import LayoutDialog
        root = tk.Tk()
        device = self._device()
        original = [b["id"] for b in device["buttons"]]
        callbacks = []
        try:
            dlg = LayoutDialog(root, device, lambda: callbacks.append("saved"))
            target = device["buttons"][1]["id"]
            dlg.tree.selection_set(target)
            dlg._select()
            self.assertEqual(str(dlg.move_up_btn.cget("state")), "normal")
            dlg._move_selected(-1)
            dlg.chosen_span.set("12")
            dlg._apply_width()
            self.assertEqual(dlg.order, [original[1], original[0], original[2]])
            self.assertEqual(dlg.tree.get_children(), tuple(dlg.order))
            self.assertEqual([b["label"] for row in dlg._rows()
                              for b in row], ["Right", "Select", "Input"])
            self.assertEqual(dlg.working[target], 12)
            self.assertEqual([b["id"] for b in device["buttons"]], original)
            self.assertTrue(all(b["span"] == 6 for b in device["buttons"]))
            dlg.destroy()  # user clicks Cancel
            self.assertEqual(callbacks, [])
            self.assertEqual([b["id"] for b in device["buttons"]], original)
        finally:
            root.destroy()

    def test_save_both_order_and_width_and_export_to_irplus(self):
        require_display(self)
        import tempfile
        import tkinter as tk
        import xml.etree.ElementTree as ET
        from pathlib import Path
        from irstudio.layout_preview import LayoutDialog
        root = tk.Tk()
        device = self._device()
        library = core.default_library()
        library["devices"].append(device)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "library.json"
            calls = []
            def save():
                core.save_library(path, library)
                calls.append("saved")
            try:
                dlg = LayoutDialog(root, device, save)
                target = device["buttons"][2]["id"]
                dlg.tree.selection_set(target)
                dlg._select()
                dlg._move_selected(-1)
                dlg._move_selected(-1)
                self.assertEqual(
                    str(dlg.move_up_btn.cget("state")), "disabled")
                dlg.chosen_span.set("12")
                dlg._apply_width()
                dlg._save()
                self.assertEqual(calls, ["saved"])
                self.assertFalse(dlg.winfo_exists())
                saved = core.load_library(path)["devices"][0]
                self.assertEqual(
                    [b["label"] for b in saved["buttons"]],
                    ["Input", "Select", "Right"])
                self.assertEqual(
                    [b["span"] for b in saved["buttons"]],
                    [12, 6, 6])
                irplus = Path(temp) / "remote.irplus"
                self.assertEqual(core.export_irplus(device, irplus), 3)
                exported = [
                    (b.get("label"), int(b.get("span")))
                    for b in ET.parse(irplus).getroot().find("device")
                    if b.tag == "button"]
                self.assertEqual(exported, [
                    ("Input", 12), ("Select", 6), ("Right", 6)])
            finally:
                if dlg.winfo_exists():
                    dlg.destroy()
                root.destroy()

    def test_failed_save_restores_original_order_and_widths(self):
        require_display(self)
        import tkinter as tk
        from irstudio.layout_preview import LayoutDialog
        root = tk.Tk()
        device = self._device()
        original = list(device["buttons"])
        try:
            def fail():
                raise OSError("Disk is full")
            dlg = LayoutDialog(root, device, fail)
            target = device["buttons"][1]["id"]
            dlg.tree.selection_set(target)
            dlg._select()
            dlg._move_selected(-1)
            dlg.chosen_span.set("4")
            dlg._apply_width()
            with patch("irstudio.layout_preview.messagebox.showerror") as error:
                dlg._save()
                error.assert_called_once()
            self.assertTrue(dlg.winfo_exists())
            self.assertEqual(device["buttons"], original)
            self.assertTrue(all(b["span"] == 6 for b in device["buttons"]))
            dlg.destroy()
        finally:
            root.destroy()


if __name__ == "__main__":
    unittest.main()
