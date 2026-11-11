"""IR Remote Studio phone-like and grid layout editor (preview-only, no IR transmission).

Both modes consume core.layout_rows(), the same row planner used by irplus export.
The simulated app chrome is illustrative: Android irplus may render differently.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import tempfile
import tkinter as tk
from tkinter import font as tkfont, messagebox, ttk

from irstudio import core

SCREEN_SIZES = {"Small": 250, "Normal": 305, "Large": 365}
TEXT_SIZES = {"Small": 9, "Normal": 11, "Large": 13}

# Separate from library.json: moving a window is a UI preference, never a
# remote-definition change. No IR samples, devices or button layouts go here.
WINDOW_POSITION_FILE = "layout-editor-window.json"


def _monitor_rectangles(widget):
    """All active monitor rectangles in virtual desktop coordinates.

    winfo_screenwidth() only covers the primary display on some Tk/Windows
    configurations. EnumDisplayMonitors preserves positions on a monitor to
    the left or above the primary (negative x/y).
    """
    if sys.platform == "win32":
        try:
            import ctypes
            from ctypes import wintypes

            callback_type = ctypes.WINFUNCTYPE(
                ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p,
                ctypes.POINTER(wintypes.RECT), ctypes.c_ssize_t)
            rectangles = []

            def record(_monitor, _hdc, rect, _data):
                r = rect.contents
                rectangles.append((r.left, r.top, r.right, r.bottom))
                return 1

            callback = callback_type(record)
            user32 = ctypes.windll.user32
            user32.EnumDisplayMonitors(
                None, None, callback, 0)
            if rectangles:
                return rectangles
        except (AttributeError, OSError, ValueError):
            pass
    return [(0, 0, widget.winfo_screenwidth(), widget.winfo_screenheight())]


def _titlebar_on_screen(x, y, width, monitors):
    """Permit partially overlapping windows only when the title is usable."""
    for left, top, right, bottom in monitors:
        horizontal = min(x + width, right) - max(x, left)
        if horizontal >= 140 and top <= y <= bottom - 65:
            return True
    return False


class LayoutDialog(tk.Toplevel):
    """Edit button order and 12-column widths; preview before applying."""

    def __init__(self, master, device: dict, on_save):
        super().__init__(master)
        # Keep the Toplevel hidden until it is placed, avoiding a flash in
        # the top-left before the saved position is restored.
        self.withdraw()
        owner_folder = getattr(master, "folder", None)
        self._position_file = (
            Path(owner_folder) / WINDOW_POSITION_FILE
            if owner_folder is not None else None)
        self.device = device
        self.on_save = on_save
        # Both appearance settings are staged until Save layout. Cancel
        # leaves the real device and its captured IR waveforms untouched.
        self.working = {button["id"]: core.button_span(button)
                        for button in device["buttons"]}
        self.order = [button["id"] for button in device["buttons"]]
        self.mode = tk.StringVar(master=self, value="Phone")
        self.screen_size = tk.StringVar(master=self, value="Normal")
        self.theme = tk.StringVar(master=self, value="Dark")
        self.text_size = tk.StringVar(master=self, value="Normal")
        self.chosen_span = tk.StringVar(master=self, value="6")
        self.preview_info = tk.StringVar(master=self, value="")
        self.title("Remote layout - " + device["name"])
        self.geometry("930x690")
        self.minsize(760, 540)
        self.transient(master)
        self.button_font = tkfont.Font(root=self, family="Segoe UI", size=11, weight="bold")
        self._make_ui()
        self._render()
        self._restore_window_position(master)
        self.deiconify()

    def _place_window(self, width, height, x, y):
        """Apply absolute coordinates, including monitors left of the primary.

        Tk's negative -X/-Y geometry syntax means distance from the RIGHT
        or BOTTOM screen edge, not an absolute negative virtual-desktop
        position. Convert desired negative virtual coords accordingly.
        """
        screen_width = self.winfo_screenwidth()
        screen_height = self.winfo_screenheight()
        xpart = f"+{x}" if x >= 0 else f"-{screen_width - width - x}"
        ypart = f"+{y}" if y >= 0 else f"-{screen_height - height - y}"
        self.geometry(f"{width}x{height}{xpart}{ypart}")

    def _restore_window_position(self, master):
        """Restore persisted geometry or center on the owner's monitor."""
        monitors = _monitor_rectangles(self)
        width, height = 930, 690
        saved = None
        if self._position_file is not None:
            try:
                data = json.loads(
                    self._position_file.read_text(encoding="utf-8"))
                values = (data["x"], data["y"],
                          data["width"], data["height"])
                if (all(type(value) is int for value in values)
                        and 760 <= values[2] <= 6000
                        and 540 <= values[3] <= 4000
                        and abs(values[0]) <= 20000
                        and abs(values[1]) <= 20000):
                    saved = values
            except (OSError, ValueError, KeyError, TypeError):
                pass
        if saved is not None:
            x, y, width, height = saved
            if _titlebar_on_screen(x, y, width, monitors):
                self._place_window(width, height, x, y)
                return
        # No remembered position, or the old monitor has disappeared.
        # Center relative to the main window (including a secondary monitor).
        master.update_idletasks()
        owner_width = master.winfo_width()
        owner_height = master.winfo_height()
        if owner_width >= 100 and owner_height >= 100:
            cx = master.winfo_rootx() + owner_width // 2
            cy = master.winfo_rooty() + owner_height // 2
        else:
            left, top, right, bottom = monitors[0]
            cx, cy = (left + right) // 2, (top + bottom) // 2
        chosen = next(
            ((left, top, right, bottom)
             for left, top, right, bottom in monitors
             if left <= cx < right and top <= cy < bottom),
            monitors[0])
        left, top, right, bottom = chosen
        width = min(width, max(760, right - left))
        height = min(height, max(540, bottom - top))
        x = max(left, min(cx - width // 2, right - width))
        y = max(top, min(cy - height // 2, bottom - height))
        self._place_window(width, height, x, y)

    def _remember_window_position(self):
        """Persist only valid window placement, never remote/library content."""
        if self._position_file is None or not self.winfo_exists():
            return
        if self.state() != "normal":
            return
        try:
            self.update_idletasks()
            width, height = self.winfo_width(), self.winfo_height()
            x, y = self.winfo_x(), self.winfo_y()
            if (width < 760 or height < 540
                    or not _titlebar_on_screen(
                        x, y, width, _monitor_rectangles(self))):
                return
            self._position_file.parent.mkdir(parents=True, exist_ok=True)
            temporary = None
            try:
                descriptor, temporary = tempfile.mkstemp(
                    prefix=".layout-window-", suffix=".tmp",
                    dir=str(self._position_file.parent))
                with os.fdopen(descriptor, "w", encoding="utf-8") as output:
                    json.dump({"x": x, "y": y, "width": width,
                               "height": height}, output)
                    output.write("\n")
                os.replace(temporary, self._position_file)
            finally:
                if temporary is not None and os.path.exists(temporary):
                    os.unlink(temporary)
        except (OSError, ValueError, tk.TclError):
            # A placement preference must never prevent closing the editor.
            pass

    def destroy(self):
        self._remember_window_position()
        super().destroy()

    def _make_ui(self):
        outer = ttk.Frame(self, padding=14)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="Remote layout editor",
                  font=("Segoe UI", 15, "bold")).pack(anchor="w")
        ttk.Label(
            outer,
            text="Arrange button order and widths here. The preview follows "
                 "the exported .irplus layout; buttons without IR codes "
                 "remain listed but are hidden in the preview.",
            wraplength=800).pack(anchor="w", pady=(3, 10))
        # Keep Save/Cancel reserved at the bottom when maximized on a laptop.
        self.footer = ttk.Frame(outer)
        footer = self.footer
        footer.pack(side="bottom", fill="x", pady=(10, 0))
        ttk.Label(footer, text="Preview is illustrative; final font and chrome depend on the phone and irplus.",
                  foreground="#59677b").pack(side="left")
        ttk.Button(footer, text="Save layout",
                   command=self._save).pack(side="right")
        ttk.Button(footer, text="Cancel",
                   command=self.destroy).pack(side="right", padx=8)

        body = ttk.PanedWindow(outer, orient="horizontal")
        body.pack(fill="both", expand=True)

        left = ttk.Frame(body, padding=(0, 0, 10, 0))
        right = ttk.Frame(body, padding=(10, 0, 0, 0))
        body.add(left, weight=2)
        body.add(right, weight=3)

        ttk.Label(left, text="Buttons", font=("Segoe UI", 11, "bold")).pack(anchor="w")
        self.width_bar = ttk.Frame(left)
        width_bar = self.width_bar
        width_bar.pack(side="bottom", fill="x", pady=(8, 0))
        ttk.Label(width_bar, text="Selected width").pack(side="left")
        self.width_combo = ttk.Combobox(width_bar, textvariable=self.chosen_span,
                                         values=("2", "3", "4", "6", "12"),
                                         width=5, state="readonly")
        self.width_combo.pack(side="left", padx=(10, 5))
        ttk.Button(width_bar, text="Apply width",
                   command=self._apply_width).pack(side="left")
        self.order_bar = ttk.Frame(left)
        self.order_bar.pack(side="bottom", fill="x", pady=(8, 0))
        self.move_up_btn = ttk.Button(
            self.order_bar, text="Move Up", command=lambda: self._move_selected(-1),
            state="disabled")
        self.move_up_btn.pack(side="left", fill="x", expand=True, padx=(0, 4))
        self.move_down_btn = ttk.Button(
            self.order_bar, text="Move Down", command=lambda: self._move_selected(1),
            state="disabled")
        self.move_down_btn.pack(side="left", fill="x", expand=True, padx=(4, 0))

        self.tree = ttk.Treeview(left, columns=("label", "width", "state"),
                                 show="headings", height=14, selectmode="browse")
        for key, heading, width in (
            ("label", "Label", 140), ("width", "Width / 12", 78),
            ("state", "IR status", 105),
        ):
            self.tree.heading(key, text=heading)
            self.tree.column(key, width=width, minwidth=60)
        self.tree.pack(fill="both", expand=True, pady=(5, 8))
        for b in self.device["buttons"]:
            self.tree.insert("", "end", iid=b["id"],
                             values=(b["label"], self.working[b["id"]],
                                     core.capture_status(b)))
        self.tree.bind("<<TreeviewSelect>>", self._select)

        ttk.Label(right, text="Preview", font=("Segoe UI", 11, "bold")).pack(anchor="w")
        toolbar = ttk.Frame(right)
        toolbar.pack(fill="x", pady=(5, 7))
        for label, var, options, width in (
            ("Mode", self.mode, ("Phone", "Grid"), 7),
            ("Size", self.screen_size, tuple(SCREEN_SIZES), 7),
            ("Theme", self.theme, ("Dark", "Light"), 7),
            ("Text", self.text_size, tuple(TEXT_SIZES), 7),
        ):
            group = ttk.Frame(toolbar)
            group.pack(side="left", padx=(0, 7))
            ttk.Label(group, text=label).pack(anchor="w")
            combo = ttk.Combobox(group, textvariable=var, values=options,
                                 width=width, state="readonly")
            combo.pack()
            combo.bind("<<ComboboxSelected>>", lambda _e: self._render())

        ttk.Label(right, textvariable=self.preview_info, wraplength=400,
                  foreground="#536177").pack(side="bottom", fill="x", pady=(6, 0))

        canvas_holder = ttk.Frame(right)
        canvas_holder.pack(fill="both", expand=True)
        self.canvas = tk.Canvas(canvas_holder, bg="#e9edf3",
                                highlightthickness=0, height=380)
        scrollbar = ttk.Scrollbar(canvas_holder, orient="vertical",
                                  command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.canvas.bind("<Configure>", lambda _e: self._render())
        self.canvas.bind("<MouseWheel>", self._wheel)
        self.canvas.bind("<Button-4>", lambda _e: self.canvas.yview_scroll(-3, "units"))
        self.canvas.bind("<Button-5>", lambda _e: self.canvas.yview_scroll(3, "units"))
        if self.device["buttons"]:
            first = self.device["buttons"][0]["id"]
            self.tree.selection_set(first)
            self._select()

    def _wheel(self, event):
        units = -1 if event.delta > 0 else 1
        self.canvas.yview_scroll(units * 3, "units")

    def _select(self, _event=None):
        ids = self.tree.selection()
        if ids:
            self.chosen_span.set(str(self.working[ids[0]]))
        self._update_move_controls()
        self._render()

    def _update_move_controls(self):
        ids = self.tree.selection()
        if not ids or ids[0] not in self.order:
            self.move_up_btn.configure(state="disabled")
            self.move_down_btn.configure(state="disabled")
            return
        index = self.order.index(ids[0])
        self.move_up_btn.configure(
            state="normal" if index > 0 else "disabled")
        self.move_down_btn.configure(
            state="normal" if index < len(self.order) - 1 else "disabled")

    def _move_selected(self, offset):
        """Reorder staged appearance, not the actual library until Save."""
        ids = self.tree.selection()
        if not ids or offset not in (-1, 1):
            return
        button_id = ids[0]
        index = self.order.index(button_id)
        destination = index + offset
        if not 0 <= destination < len(self.order):
            return
        self.order.pop(index)
        self.order.insert(destination, button_id)
        self.tree.move(button_id, "", destination)
        self.tree.selection_set(button_id)
        self.tree.focus(button_id)
        self.tree.see(button_id)
        self._update_move_controls()
        self._render()

    def _apply_width(self):
        ids = self.tree.selection()
        if not ids:
            return messagebox.showinfo("Layout editor", "Select a button first.", parent=self)
        width = int(self.chosen_span.get())
        self.working[ids[0]] = width
        label = self.tree.item(ids[0], "values")[0]
        status = self.tree.item(ids[0], "values")[2]
        self.tree.item(ids[0], values=(label, width, status))
        self._render()

    def _preview_device(self):
        by_id = {b["id"]: b for b in self.device["buttons"]}
        buttons = [dict(by_id[button_id], span=self.working[button_id])
                   for button_id in self.order]
        return {"name": self.device["name"], "buttons": buttons}

    def _rows(self):
        return core.layout_rows(self._preview_device())

    def _label(self, label, width):
        """Conservatively emulate one-line mobile button text truncation."""
        if self.button_font.measure(label) <= width - 12:
            return label, False
        suffix = "..."
        available = max(0, width - 12 - self.button_font.measure(suffix))
        while label and self.button_font.measure(label) > available:
            label = label[:-1]
        return (label + suffix) if label else suffix, True

    def _render(self):
        if not hasattr(self, "canvas"):
            return
        canvas = self.canvas
        canvas.delete("all")
        width = max(canvas.winfo_width(), 430)
        rows = self._rows()
        exported = sum(map(len, rows))
        missing = sum(not (b.get("captures") or b.get("pronto_hex"))
                      for b in self.device["buttons"])
        self.preview_info.set(
            f"{exported} exportable button(s) displayed; {missing} not learned (hidden). "
            "Grid positions match the .irplus exporter."
        )
        self.button_font.configure(size=TEXT_SIZES[self.text_size.get()])
        selected = self.tree.selection()[0] if self.tree.selection() else None
        if self.mode.get() == "Grid":
            self._draw_grid(width, rows, selected)
        else:
            self._draw_phone(width, rows, selected)
        bounds = canvas.bbox("all")
        canvas.configure(scrollregion=(0, 0, max(width, bounds[2] + 20 if bounds else width),
                                      bounds[3] + 18 if bounds else 460))

    def _button(self, x, y, width, height, button, selected, dark):
        canvas = self.canvas
        chosen = button["id"] == selected
        fill = ("#365d93" if dark else "#d7e9ff") if chosen else ("#323e52" if dark else "#f8fafc")
        border = "#80b9ff" if chosen else ("#637184" if dark else "#a1b1c7")
        canvas.create_rectangle(x, y, x+width, y+height, fill=fill,
                                outline=border, width=2 if chosen else 1)
        text, clipped = self._label(button["label"], width)
        canvas.create_text(x+width/2, y+height/2, text=text,
                           font=self.button_font, fill="#ffffff" if dark else "#1f2937")
        return clipped

    def _draw_phone(self, canvas_width, rows, selected):
        canvas = self.canvas
        screen_width = SCREEN_SIZES[self.screen_size.get()]
        x = max(15, (canvas_width-screen_width-28)/2)
        y = 16
        dark = self.theme.get() == "Dark"
        bezel = "#121820" if dark else "#334155"
        screen = "#111a29" if dark else "#f1f5f9"
        text = "#f8fafc" if dark else "#1e293b"
        row_height = 52
        body_top = y+118
        content_height = max(440, 160 + max(1,len(rows))*row_height + 70)
        body_bottom = y + content_height
        canvas.create_rectangle(x, y, x+screen_width+28, body_bottom+18,
                                fill=bezel, outline="#46576c", width=3)
        left = x+14
        right = left+screen_width
        canvas.create_rectangle(left, y+30, right, body_bottom-8,
                                fill=screen, outline=screen)
        canvas.create_oval(x+(screen_width+28)/2-16, y+10,
                           x+(screen_width+28)/2+16, y+16, fill="#526478",
                           outline="")
        canvas.create_text(left+18, y+48, text="9:41", anchor="w", font=("Segoe UI", 9, "bold"),
                           fill=text)
        canvas.create_text(right-18, y+48, text="Wi-Fi   100%", anchor="e",
                           font=("Segoe UI", 9), fill=text)
        canvas.create_rectangle(left, y+66, right, y+105,
                                fill="#243449" if dark else "#dce5f0", outline="")
        title = self.device["name"]
        if len(title) > 28:
            title = title[:25] + "..."
        canvas.create_text((left+right)/2, y+86, text=title,
                           font=("Segoe UI", 12, "bold"), fill=text)
        interior_margin = 10
        col_width = (screen_width-interior_margin*2)/12
        warnings = []
        for ri, row in enumerate(rows):
            col = 0
            top = body_top + ri*row_height
            for button in row:
                span = core.button_span(button)
                bx = left + interior_margin + col*col_width + 2
                bw = span*col_width - 4
                if self._button(bx, top, bw, 41, button, selected, dark):
                    warnings.append(button["label"])
                col += span
        canvas.create_rectangle((left+right)/2-33, body_bottom-22,
                                (left+right)/2+33, body_bottom-18,
                                fill="#8797a9", outline="")
        if not rows:
            canvas.create_text((left+right)/2, body_top+25, text="No learned buttons yet",
                               fill=text, font=("Segoe UI", 10))
        if warnings:
            canvas.create_text(canvas_width/2, body_bottom+39,
                text="Possible clipped labels: " + ", ".join(warnings[:4]),
                font=("Segoe UI", 9), fill="#9b492f", width=canvas_width-30)
        canvas.configure(scrollregion=(0, 0, canvas_width, body_bottom+95))

    def _draw_grid(self, canvas_width, rows, selected):
        canvas = self.canvas
        x = 22
        right = max(400, canvas_width-24)
        unit = (right-x)/12
        dark = self.theme.get() == "Dark"
        ink = "#1f2937"
        canvas.create_text(x, 22, text="12-column export grid", anchor="w",
                           font=("Segoe UI", 12, "bold"), fill=ink)
        for col in range(13):
            bx = x+col*unit
            canvas.create_line(bx, 50, bx, 50+max(1,len(rows))*57,
                               fill="#b5c4d6", dash=(2, 3))
        for ri, row in enumerate(rows):
            col = 0
            top = 55+ri*57
            for button in row:
                span = core.button_span(button)
                bx = x+col*unit+2
                self._button(bx, top, span*unit-4, 43, button, selected, dark)
                col += span
            if col < 12:
                canvas.create_text(x+col*unit+4, top+22,
                    text=f"space ({12-col})", anchor="w", fill="#64748b",
                    font=("Segoe UI", 9))
        if not rows:
            canvas.create_text(x+12, 87, anchor="w", text="No learned buttons yet",
                               fill=ink, font=("Segoe UI", 10))

    def _save(self):
        """Apply order and widths in one library save, or roll both back."""
        original_order = list(self.device["buttons"])
        original_widths = {b["id"]: b.get("span", 6)
                           for b in original_order}
        by_id = {b["id"]: b for b in original_order}
        try:
            self.device["buttons"][:] = [by_id[button_id]
                                          for button_id in self.order]
            for button in self.device["buttons"]:
                button["span"] = self.working[button["id"]]
            self.on_save()
        except Exception as exc:
            self.device["buttons"][:] = original_order
            for button in original_order:
                button["span"] = original_widths[button["id"]]
            messagebox.showerror(
                "Layout not saved",
                "The original button order and widths have been restored.\n\n"
                + str(exc), parent=self)
            return
        self.destroy()
