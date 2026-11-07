"""Tk Windows user interface for IR Remote Studio."""
from __future__ import annotations

import json
import os
import shutil
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, font as tkfont, messagebox, simpledialog, ttk

from irstudio import __version__
from irstudio import core
from irstudio.harmony import invoke_harmony


class Studio(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"IR Remote Studio {__version__}  |  Windows")
        self.minsize(960, 610)
        self.geometry("1160x740")
        self.folder = Path.home() / "Documents" / "IR Remote Studio"
        self.library_path = self.folder / "library.json"
        self.library = core.load_library(self.library_path)
        self.active_device = None
        self.active_button = None
        self.running = False
        self.status_var = tk.StringVar(value=f"Library: {self.library_path}")
        self.repeat_var = tk.StringVar(value="0")
        self._make_ui()
        self._reload_devices()
        self.protocol("WM_DELETE_WINDOW", self._close)

    def _make_ui(self):
        style = ttk.Style()
        if "vista" in style.theme_names():
            style.theme_use("vista")
        # The unquoted Tcl font string "Segoe UI 10" misparses UI as the size.
        # Setting the named font instead also applies to menus and Tk widgets.
        tkfont.nametofont("TkDefaultFont").configure(family="Segoe UI", size=10)
        menu = tk.Menu(self)
        file_menu = tk.Menu(menu, tearoff=0)
        file_menu.add_command(label="Import capture JSON...", command=self.import_capture)
        file_menu.add_command(label="Import .irplus remote...", command=self.import_irplus)
        file_menu.add_separator()
        file_menu.add_command(label="Export current device as .irplus...", command=self.export_irplus)
        file_menu.add_command(label="Back up library JSON...", command=self.backup_library)
        file_menu.add_separator()
        file_menu.add_command(label="Open data folder", command=self.open_folder)
        file_menu.add_command(label="Exit", command=self._close)
        menu.add_cascade(label="File", menu=file_menu)
        help_menu = tk.Menu(menu, tearoff=0)
        help_menu.add_command(label="About", command=lambda: messagebox.showinfo("About IR Remote Studio",
            "IR Remote Studio v0.1.3\n\nCompatible Harmony remotes use the installed Concordance libconcord DLL through a separate x86 PowerShell process.\n\nCapture reads IR only; it does not change Harmony programming.\n\nProject: github.com/MixterVee/IR-Remote-Studio"))
        menu.add_cascade(label="Help", menu=help_menu)
        self.config(menu=menu)

        header = ttk.Frame(self, padding=(16, 14, 16, 6))
        header.pack(fill="x")
        ttk.Label(header, text="IR Remote Studio", font=("Segoe UI Semibold", 20)).pack(anchor="w")
        ttk.Label(header, text="Create devices, label buttons, learn IR with compatible Harmony remotes, and export .irplus remotes.",
                  foreground="#505b69").pack(anchor="w", pady=(2, 0))

        toolbar = ttk.Frame(self, padding=(16, 10, 16, 10))
        toolbar.pack(fill="x")
        self.probe_btn = ttk.Button(toolbar, text="Check Harmony IR learner", command=self.probe)
        self.probe_btn.pack(side="left")
        ttk.Separator(toolbar, orient="vertical").pack(side="left", fill="y", padx=12)
        ttk.Button(toolbar, text="Import JSON capture", command=self.import_capture).pack(side="left", padx=(0, 6))
        ttk.Button(toolbar, text="Import .irplus", command=self.import_irplus).pack(side="left")
        ttk.Button(toolbar, text="Export .irplus", command=self.export_irplus).pack(side="right")
        ttk.Label(toolbar, text="NEC repeat frames:").pack(side="right", padx=(15, 6))
        ttk.Combobox(toolbar, textvariable=self.repeat_var, values=("0", "1", "2"),
                     width=4, state="readonly").pack(side="right")

        panels = ttk.PanedWindow(self, orient="horizontal")
        panels.pack(fill="both", expand=True, padx=16, pady=(0, 8))
        left = ttk.Frame(panels, padding=8)
        middle = ttk.Frame(panels, padding=8)
        right = ttk.Frame(panels, padding=8)
        panels.add(left, weight=2)
        panels.add(middle, weight=4)
        panels.add(right, weight=3)

        ttk.Label(left, text="Devices", font=("Segoe UI Semibold", 13)).pack(anchor="w", pady=(0, 8))
        self.device_list = tk.Listbox(left, borderwidth=1, relief="solid", activestyle="none", exportselection=False)
        self.device_list.pack(fill="both", expand=True)
        self.device_list.bind("<<ListboxSelect>>", self._select_device)
        dl = ttk.Frame(left)
        dl.pack(fill="x", pady=(10, 0))
        ttk.Button(dl, text="+ Add", command=self.add_device).pack(side="left", expand=True, fill="x")
        ttk.Button(dl, text="Edit", command=self.edit_device).pack(side="left", expand=True, fill="x", padx=3)
        ttk.Button(dl, text="Delete", command=self.delete_device).pack(side="left", expand=True, fill="x")

        ttk.Label(middle, text="Buttons", font=("Segoe UI Semibold", 13)).pack(anchor="w", pady=(0, 8))
        self.button_tree = ttk.Treeview(middle, columns=("count", "code"), show="headings", selectmode="browse")
        self.button_tree.heading("#0", text="")
        self.button_tree["columns"] = ("label", "count", "code")
        self.button_tree.heading("label", text="Custom label")
        self.button_tree.heading("count", text="Captures")
        self.button_tree.heading("code", text="Decoded code")
        self.button_tree.column("label", width=150, minwidth=80)
        self.button_tree.column("count", width=80, minwidth=60, anchor="center")
        self.button_tree.column("code", width=140, minwidth=100)
        self.button_tree.pack(fill="both", expand=True)
        self.button_tree.bind("<<TreeviewSelect>>", self._select_button)
        buttons = ttk.Frame(middle)
        buttons.pack(fill="x", pady=(10, 0))
        for text, cmd in (("+ Add", self.add_button), ("Rename", self.rename_button),
                          ("Delete", self.delete_button), ("Up", lambda: self.move_button(-1)),
                          ("Down", lambda: self.move_button(1))):
            ttk.Button(buttons, text=text, command=cmd).pack(side="left", expand=True, fill="x", padx=1)

        ttk.Label(right, text="Selected button", font=("Segoe UI Semibold", 13)).pack(anchor="w", pady=(0, 8))
        self.details = tk.Text(right, wrap="word", height=16, state="disabled", relief="flat",
                               background="#f5f6f8", foreground="#222222", padx=12, pady=12)
        self.details.pack(fill="both", expand=True)
        ttk.Label(right, text="Point the original remote at the TOP of the Harmony, 5-10 cm away.\nClick Capture, then press the original button briefly.",
                  wraplength=270, justify="left").pack(fill="x", pady=(9, 8))
        self.capture_btn = ttk.Button(right, text="Capture selected button", command=self.capture)
        self.capture_btn.pack(fill="x", ipady=7)
        ttk.Button(right, text="Use best matching capture", command=self.choose_best).pack(fill="x", pady=(6, 0))
        ttk.Button(right, text="Remove last capture", command=self.remove_capture).pack(fill="x", pady=(6, 0))

        status = ttk.Frame(self, padding=(16, 6, 16, 12))
        status.pack(fill="x")
        ttk.Separator(status).pack(fill="x", pady=(0, 7))
        ttk.Label(status, textvariable=self.status_var, wraplength=1080).pack(anchor="w")

    def _selected_device(self):
        for d in self.library["devices"]:
            if d["id"] == self.active_device:
                return d
        return None

    def _selected_button(self):
        device = self._selected_device()
        if device:
            for button in device["buttons"]:
                if button["id"] == self.active_button:
                    return button
        return None

    def _save(self):
        core.save_library(self.library_path, self.library)

    def _reload_devices(self, selected=None):
        keep = selected or self.active_device
        self.device_list.delete(0, "end")
        for d in self.library["devices"]:
            self.device_list.insert("end", d["name"])
        positions = [i for i, d in enumerate(self.library["devices"]) if d["id"] == keep]
        if not positions and self.library["devices"]:
            positions = [0]
        if positions:
            index = positions[0]
            self.device_list.selection_set(index)
            self.device_list.activate(index)
            self.active_device = self.library["devices"][index]["id"]
        else:
            self.active_device = None
        self._reload_buttons()

    def _select_device(self, _event=None):
        sel = self.device_list.curselection()
        if not sel:
            return
        self.active_device = self.library["devices"][sel[0]]["id"]
        self.active_button = None
        self._reload_buttons()

    def _reload_buttons(self, selected=None):
        keep = selected or self.active_button
        self.button_tree.delete(*self.button_tree.get_children())
        device = self._selected_device()
        if device:
            for b in device["buttons"]:
                match, total = core.match_count(b)
                cap = core.selected_capture(b)
                decoded = core.decode_nec(cap["timings_us"]) if cap else None
                code = decoded["bytes"] if decoded else ("Pronto" if b.get("pronto_hex") else "Not learned")
                self.button_tree.insert("", "end", iid=b["id"],
                                        values=(b["label"], f"{match}/{total}" if total else "-", code))
            ids = [b["id"] for b in device["buttons"]]
            if keep not in ids:
                keep = ids[0] if ids else None
        else:
            keep = None
        self.active_button = keep
        if keep:
            self.button_tree.selection_set(keep)
            self.button_tree.focus(keep)
        self._update_details()

    def _select_button(self, _event=None):
        selection = self.button_tree.selection()
        if selection:
            self.active_button = selection[0]
        self._update_details()

    def _update_details(self):
        button = self._selected_button()
        device = self._selected_device()
        if button:
            cap = core.selected_capture(button)
            match, count = core.match_count(button)
            lines = [f"DEVICE: {device['name']}", f"BUTTON: {button['label']}", ""]
            if cap:
                decoded = core.decode_nec(cap["timings_us"])
                lines += [f"Carrier: {cap['carrier_hz']:,} Hz", f"Timings: {len(cap['timings_us'])}",
                          f"Captured: {count} time(s)", f"Best agreement: {match}/{count}",
                          f"Selected sample: {button.get('selected', 0)+1}/{count}", ""]
                if decoded:
                    lines += [f"Protocol: {decoded['protocol']}", f"Address: {decoded['address']}",
                              f"Command: {decoded['command']}", f"Bytes: {decoded['bytes']}", ""]
                else:
                    lines += ["Protocol: unknown (raw timings)", ""]
                lines.append("Tip: capture a button 2-3 times for confidence.")
            elif button.get("pronto_hex"):
                lines += ["Imported Pronto Hex code", "Ready for .irplus export", ""]
            else:
                lines += ["No IR code captured yet.", ""]
        else:
            lines = ["Select or create a device and a button.", "", "Custom labels are saved automatically."]
        self.details.configure(state="normal")
        self.details.delete("1.0", "end")
        self.details.insert("1.0", "\n".join(lines))
        self.details.configure(state="disabled")

    def add_device(self):
        name = simpledialog.askstring("New device", "Device name (e.g. Kitchen clock):", parent=self)
        if not name:
            return
        manufacturer = simpledialog.askstring("Manufacturer", "Manufacturer (optional):", parent=self) or ""
        model = simpledialog.askstring("Model", "Model number (optional):", parent=self) or ""
        try:
            device = core.new_device(name, manufacturer, model)
        except ValueError as exc:
            return messagebox.showerror("Device", str(exc))
        self.library["devices"].append(device)
        self._save()
        self._reload_devices(device["id"])
        self.status_var.set(f"Created device: {name}")

    def edit_device(self):
        d = self._selected_device()
        if not d:
            return
        for key, caption in (("name", "Device name"), ("manufacturer", "Manufacturer"), ("model", "Model")):
            value = simpledialog.askstring("Edit device", caption + ":", initialvalue=d.get(key, ""), parent=self)
            if value is None:
                return
            if key == "name" and not value.strip():
                return messagebox.showerror("Device", "Name cannot be empty")
            d[key] = value.strip()
        self._save()
        self._reload_devices(d["id"])

    def delete_device(self):
        d = self._selected_device()
        if not d or not messagebox.askyesno("Delete device", f"Delete '{d['name']}' and all its stored commands?"):
            return
        self.library["devices"].remove(d)
        self.active_device = self.active_button = None
        self._save()
        self._reload_devices()

    def add_button(self):
        d = self._selected_device()
        if not d:
            return messagebox.showwarning("Device", "Create a device first")
        label = simpledialog.askstring("New button", "Custom button label:", parent=self)
        if not label:
            return
        try:
            b = core.new_button(label)
        except ValueError as exc:
            return messagebox.showerror("Button", str(exc))
        d["buttons"].append(b)
        self._save()
        self._reload_buttons(b["id"])

    def rename_button(self):
        b = self._selected_button()
        if not b:
            return
        label = simpledialog.askstring("Rename", "Button label:", initialvalue=b["label"], parent=self)
        if label and label.strip():
            b["label"] = label.strip()
            self._save()
            self._reload_buttons(b["id"])

    def delete_button(self):
        d, b = self._selected_device(), self._selected_button()
        if d and b and messagebox.askyesno("Delete button", f"Delete '{b['label']}' and its captures?"):
            d["buttons"].remove(b)
            self.active_button = None
            self._save()
            self._reload_buttons()

    def move_button(self, offset):
        d, b = self._selected_device(), self._selected_button()
        if not d or not b:
            return
        i = d["buttons"].index(b)
        j = i + offset
        if 0 <= j < len(d["buttons"]):
            d["buttons"][i], d["buttons"][j] = d["buttons"][j], d["buttons"][i]
            self._save()
            self._reload_buttons(b["id"])

    def _background(self, mode, complete):
        if self.running:
            return
        self.running = True
        self.probe_btn.configure(state="disabled")
        self.capture_btn.configure(state="disabled")
        self.status_var.set("Checking Harmony USB..." if mode == "Probe"
                            else "Listening: press the original remote's button once now...")

        def job():
            try:
                result = invoke_harmony(mode, self.folder / "Logs")
                self.after(0, lambda: complete(result, None))
            except Exception as exc:
                error = str(exc)
                self.after(0, lambda: complete(None, error))
        threading.Thread(target=job, daemon=True).start()

    def _end_background(self):
        self.running = False
        self.probe_btn.configure(state="normal")
        self.capture_btn.configure(state="normal")

    def probe(self):
        def done(result, error):
            self._end_background()
            if error:
                self.status_var.set("Harmony check failed. See the error message.")
                messagebox.showerror("Harmony connection", error)
            else:
                caveat = " (experimental IR learning; please verify captures)" if result.get("compatibility") == "experimental" else ""
                self.status_var.set(f"Connected: {result.get('model', 'Harmony remote')}{caveat}. Ready for capture.")
        self._background("Probe", done)

    def capture(self):
        b = self._selected_button()
        if not b:
            return messagebox.showwarning("Select button", "Choose a button before capturing")
        button_id = b["id"]
        device_id = self.active_device
        def done(result, error):
            self._end_background()
            if error:
                self.status_var.set("Capture failed - no changes were saved.")
                messagebox.showerror("Capture error", error)
                return
            try:
                cap = core.make_capture(int(result["carrier_hz"]), result["timings_us"])
            except Exception as exc:
                self.status_var.set("Incomplete capture rejected. Retry with a short, firm press.")
                messagebox.showwarning("Incomplete capture", str(exc))
                return
            device = next((d for d in self.library["devices"] if d["id"] == device_id), None)
            target = next((b for b in device["buttons"] if b["id"] == button_id), None) if device else None
            if not target:
                return
            target["captures"].append(cap)
            target["selected"] = core.best_capture_index(target)
            self._save()
            self._reload_buttons(button_id)
            match, count = core.match_count(target)
            decoded = cap["decoded"]
            desc = decoded["bytes"] if decoded else "raw signal (unidentified protocol)"
            self.status_var.set(f"Saved {target['label']}: {cap['carrier_hz']:,} Hz, {len(cap['timings_us'])} values, {desc}; matching captures {match}/{count}.")
        self._background("Capture", done)

    def choose_best(self):
        b = self._selected_button()
        if not b or not b["captures"]:
            return
        b["selected"] = core.best_capture_index(b)
        self._save()
        self._reload_buttons(b["id"])
        self.status_var.set(f"Selected best matching sample for {b['label']}")

    def remove_capture(self):
        b = self._selected_button()
        if b and b["captures"] and messagebox.askyesno("Remove sample", "Remove the last recorded sample?"):
            b["captures"].pop()
            b["selected"] = core.best_capture_index(b)
            self._save()
            self._reload_buttons(b["id"])

    def import_capture(self):
        path = filedialog.askopenfilename(title="Import captured IR JSON", filetypes=[("IR capture", "*.json")])
        if not path:
            return
        try:
            label, cap = core.import_capture_json(Path(path))
            device = self._selected_device()
            if not device:
                name = simpledialog.askstring("Device", "No device selected. Name for new device:", parent=self)
                if not name:
                    return
                device = core.new_device(name)
                self.library["devices"].append(device)
            button = next((b for b in device["buttons"] if b["label"].casefold() == label.casefold()), None)
            if not button:
                button = core.new_button(label)
                device["buttons"].append(button)
            button["captures"].append(cap)
            button["selected"] = core.best_capture_index(button)
            self._save()
            self._reload_devices(device["id"])
            self._reload_buttons(button["id"])
            self.status_var.set(f"Imported '{button['label']}' from {Path(path).name}")
        except Exception as exc:
            messagebox.showerror("Import failed", str(exc))

    def import_irplus(self):
        path = filedialog.askopenfilename(title="Import .irplus", filetypes=[("irplus remote", "*.irplus"), ("XML", "*.xml")])
        if not path:
            return
        try:
            devices = core.import_irplus(Path(path))
            self.library["devices"].extend(devices)
            self._save()
            self._reload_devices(devices[0]["id"])
            self.status_var.set(f"Imported {len(devices)} device(s) from {Path(path).name}")
        except Exception as exc:
            messagebox.showerror("Import failed", str(exc))

    def export_irplus(self):
        device = self._selected_device()
        if not device:
            return messagebox.showwarning("Select a device", "Select a device to export")
        path = filedialog.asksaveasfilename(title="Save irplus remote", defaultextension=".irplus",
            initialfile=f"{device['name'].replace('/', '-')}.irplus", filetypes=[("irplus remote", "*.irplus")])
        if not path:
            return
        try:
            count = core.export_irplus(device, Path(path), int(self.repeat_var.get()))
            self.status_var.set(f"Exported {count} buttons to {path}")
            messagebox.showinfo("Export complete", f"Exported {count} buttons.\n\n{path}\n\nImport this file in irplus on your OnePlus 15.")
        except Exception as exc:
            messagebox.showerror("Export failed", str(exc))

    def backup_library(self):
        path = filedialog.asksaveasfilename(title="Back up IR library", defaultextension=".json",
            initialfile="IR-Remote-Studio-library.json", filetypes=[("JSON backup", "*.json")])
        if path:
            self._save()
            shutil.copy2(self.library_path, path)
            self.status_var.set(f"Backed up library to {path}")

    def open_folder(self):
        self.folder.mkdir(parents=True, exist_ok=True)
        if sys.platform == "win32":
            os.startfile(str(self.folder))
        else:
            messagebox.showinfo("Data folder", str(self.folder))

    def _close(self):
        if self.running:
            if not messagebox.askyesno("Capture in progress", "A USB operation is still running. Close the app anyway?"):
                return
        self._save()
        self.destroy()


def main():
    app = Studio()
    app.mainloop()
