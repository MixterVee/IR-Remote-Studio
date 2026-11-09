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
from irstudio.receivers import AUTO, BACKENDS, invoke_receiver, harmony_attached, ocrustar_attached
from irstudio.layout_preview import LayoutDialog
from irstudio.branding import install_icon
from irstudio.sofabaton_ble_ui import SofaBatonBleDialog
from irstudio.sofabaton_u1_session import U1Session


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
        self.connected_model = None
        self._usb_connection_state = "not-connected"
        self._usb_connection_detail = ""
        self.u1_session = U1Session()
        self.u1_state_var = tk.StringVar(value="BLUETOOTH — NOT CONNECTED")
        self.u1_detail_var = tk.StringVar(value="Scan and select U1, then Connect U1.")
        self._u1_last_status = None
        self._u1_poll_after = None
        self.receiver_var = tk.StringVar(value=AUTO)
        # One visible connection indicator for all supported receivers.
        self.connection_state_var = tk.StringVar(value="NOT CONNECTED")
        self._active_operation = 0
        self._scan_in_progress = False
        self.aim_var = tk.StringVar(value="USB capture: aim the original remote at the Harmony receiver, about 5–10 cm away.")
        self.status_var = tk.StringVar(value=f"Library: {self.library_path}")
        self.repeat_var = tk.StringVar(value="0")
        self.progress_var = tk.StringVar(value="No buttons yet")
        self.brand_image = install_icon(self)
        self._make_ui()
        self._reload_devices()
        self._refresh_u1_status()
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
        file_menu.add_command(label="Import IR Capture (.json)...", command=self.import_capture)
        file_menu.add_command(label="Import Device (.irplus)...", command=self.import_irplus)
        file_menu.add_separator()
        file_menu.add_command(label="Export Device (.irplus)...", command=self.export_irplus)
        file_menu.add_command(label="Back up library JSON...", command=self.backup_library)
        file_menu.add_command(label="Restore library backup...", command=self.restore_library)
        file_menu.add_separator()
        file_menu.add_command(label="Open data folder", command=self.open_folder)
        file_menu.add_command(label="Exit", command=self._close)
        menu.add_cascade(label="File", menu=file_menu)
        help_menu = tk.Menu(menu, tearoff=0)
        help_menu.add_command(label="About", command=lambda: messagebox.showinfo("About IR Remote Studio",
            "IR Remote Studio v0.3.5-beta\n\nDirect learning backends: SofaBaton U1 Bluetooth, Harmony 300/350 USB HID (046D:C124), and experimental Ocrustar/ElkSmart bulk USB (045C:02AA). Other remotes may use the optional legacy Concordance DLL.\n\nIR capture does not change Harmony programming.\n\nProject: github.com/MixterVee/IR-Remote-Studio"))
        menu.add_cascade(label="Help", menu=help_menu)
        self.config(menu=menu)

        header = ttk.Frame(self, padding=(16, 14, 16, 6))
        header.pack(fill="x")
        identity = ttk.Frame(header)
        identity.pack(fill="x")
        if self.brand_image:
            self.brand_header_image = self.brand_image.subsample(4, 4)
            ttk.Label(identity, image=self.brand_header_image).pack(side="left", padx=(0, 10))
        heading = ttk.Frame(identity)
        heading.pack(side="left", fill="x", expand=True)
        ttk.Label(heading, text="IR Remote Studio", font=("Segoe UI Semibold", 20)).pack(anchor="w")
        ttk.Label(heading, text="Create remotes, learn IR with Bluetooth or USB, and export .irplus.",
                  foreground="#505b69").pack(anchor="w", pady=(2, 0))

        toolbar = ttk.Frame(self, padding=(16, 10, 16, 10))
        toolbar.pack(fill="x")
        ttk.Button(toolbar, text="Import IR Capture (.json)", command=self.import_capture).pack(side="left", padx=(0, 6))
        ttk.Button(toolbar, text="Import Device (.irplus)", command=self.import_irplus).pack(side="left")
        ttk.Button(toolbar, text="Export Device (.irplus)", command=self.export_irplus).pack(side="right")
        ttk.Label(toolbar, text="NEC repeat frames:").pack(side="right", padx=(15, 6))
        ttk.Combobox(toolbar, textvariable=self.repeat_var, values=("0", "1", "2"),
                     width=4, state="readonly").pack(side="right")

        # Make USB connectivity a first-class workflow, not a toolbar detail.
        connection = ttk.LabelFrame(self, text="IR LEARNER CONNECTION", padding=(16, 10))
        connection.pack(fill="x", padx=16, pady=(2, 11))
        connection_top = ttk.Frame(connection)
        connection_top.pack(fill="x")
        ttk.Label(connection_top, text="Receiver:").pack(side="left", padx=(0, 8))
        self.receiver_picker = ttk.Combobox(
            connection_top, textvariable=self.receiver_var,
            values=BACKENDS, state="readonly", width=39)
        self.receiver_picker.pack(side="left", padx=(0, 12))
        self.receiver_picker.bind("<<ComboboxSelected>>", self._receiver_changed)
        self.probe_btn = ttk.Button(
            connection_top, text="Connect / Retry", command=self.probe)
        self.probe_btn.pack(side="left", ipadx=18, ipady=7)
        self.detect_btn = ttk.Button(
            connection_top, text="Refresh USB", command=self.detect_learner)
        self.detect_btn.pack(side="left", padx=(10, 0), ipadx=8, ipady=7)
        self.sofabaton_ble_btn = ttk.Button(
            connection_top, text="SofaBaton U1 Bluetooth...",
            command=self.open_sofabaton_ble)
        self.sofabaton_ble_btn.pack(side="left", padx=(10, 0), ipady=7)

        # One truthful status for the whole app: a verified BLE U1 takes
        # priority over an unused USB receiver. Avoid simultaneous red USB
        # and green Bluetooth messages when the user has a working learner.
        connection_info = ttk.Frame(connection)
        connection_info.pack(fill="x", pady=(9, 0))
        self.connection_heading = ttk.Label(
            connection_info, textvariable=self.connection_state_var,
            font=("Segoe UI Semibold", 13), foreground="#bc2525")
        # Let the heading occupy only its measured text width so Disconnect
        # sits directly alongside it, not at the far edge of the window.
        self.connection_heading.pack(side="left")
        self.u1_disconnect_btn = tk.Button(
            connection_info, text="Disconnect U1",
            command=self.disconnect_u1, state="disabled",
            background="#b42318", foreground="#ffffff",
            activebackground="#912018", activeforeground="#ffffff",
            disabledforeground="#e2b4b0",
            font=("Segoe UI Semibold", 10),
            padx=12, pady=5, borderwidth=0, highlightthickness=0,
            cursor="hand2")
        # The Disconnect button only appears when the U1 is actually connected.

        # Reserve status space before the expandable work area.
        status = ttk.Frame(self, padding=(16, 6, 16, 12))
        status.pack(side="bottom", fill="x")
        ttk.Separator(status).pack(fill="x", pady=(0, 7))
        ttk.Label(status, textvariable=self.status_var, wraplength=1080).pack(anchor="w")

        # tk.PanedWindow supports per-pane minimum widths; ttk.PanedWindow
        # only supports weights and otherwise squeezes a pane to less than
        # a button's label width at ordinary laptop window sizes.
        panels = tk.PanedWindow(
            self, orient="horizontal", sashwidth=4, borderwidth=0,
            sashrelief="flat", showhandle=False)
        panels.pack(fill="both", expand=True, padx=16, pady=(0, 8))
        left = ttk.Frame(panels, padding=8)
        middle = ttk.Frame(panels, padding=8)
        right = ttk.Frame(panels, padding=8)
        # Total minimums (900px) fit within 960px application minimum.
        # Users can resize columns with the sash but cannot hide controls.
        panels.add(left, minsize=210, stretch="always")
        panels.add(middle, minsize=310, stretch="always")
        panels.add(right, minsize=380, stretch="always")

        ttk.Label(left, text="Devices", font=("Segoe UI Semibold", 13)).pack(anchor="w", pady=(0, 8))
        self.device_actions = ttk.Frame(left)
        dl = self.device_actions
        dl.pack(side="bottom", fill="x", pady=(10, 0))
        self._device_action_buttons = [
            ttk.Button(dl, text="+ Add", command=self.add_device),
            ttk.Button(dl, text="Edit", command=self.edit_device),
            ttk.Button(dl, text="Delete", command=self.delete_device),
        ]
        self._flow_actions(dl, self._device_action_buttons, columns=2)

        # Allocate bottom action buttons first, then give remaining space to the list.
        self.device_list = tk.Listbox(left, borderwidth=1, relief="solid", activestyle="none", exportselection=False)
        self.device_list.pack(fill="both", expand=True)
        self.device_list.bind("<<ListboxSelect>>", self._select_device)
        ttk.Label(middle, text="Buttons", font=("Segoe UI Semibold", 13)).pack(anchor="w", pady=(0, 8))
        # Bottom actions are deliberately packed before the expanding Treeview.
        # Windows taskbar and display scaling must never hide these controls.
        self.button_actions = ttk.Frame(middle)
        button_actions = self.button_actions
        button_actions.pack(side="bottom", fill="x", pady=(10, 0))
        primary = ttk.Frame(button_actions)
        primary.pack(fill="x")
        self._primary_action_buttons = [
            ttk.Button(primary, text=label, command=cmd)
            for label, cmd in (("+ Add", self.add_button),
                               ("Rename", self.rename_button),
                               ("Delete", self.delete_button))
        ]
        self._flow_actions(primary, self._primary_action_buttons, columns=2)
        secondary = ttk.Frame(button_actions)
        secondary.pack(fill="x", pady=(3, 0))
        self._secondary_action_buttons = [
            ttk.Button(secondary, text=label, command=cmd)
            for label, cmd in (("Move up", lambda: self.move_button(-1)),
                               ("Move down", lambda: self.move_button(1)),
                               ("Layout editor...", self.edit_layout))
        ]
        self._flow_actions(secondary, self._secondary_action_buttons, columns=2)
        # PanedWindow panes may be quite narrow on a laptop. Switch between
        # 3-wide and 2-wide action rows without clipping their final button.
        left.bind("<Configure>", lambda event: self._fit_action_bars(
            self.device_actions, event.width, (self._device_action_buttons,), 350))
        middle.bind("<Configure>", lambda event: self._fit_action_bars(
            self.button_actions, event.width,
            (self._primary_action_buttons, self._secondary_action_buttons), 415))

        self.button_progress = ttk.Label(middle, textvariable=self.progress_var,
                                         foreground="#536277")
        self.button_progress.pack(side="bottom", fill="x", pady=(5, 0))
        self.button_tree = ttk.Treeview(middle, columns=("label", "status", "count", "code"),
                                         show="headings", selectmode="browse")
        for key, caption, width, minimum in (
            ("label", "Custom label", 130, 70),
            ("status", "Capture status", 102, 85),
            ("count", "Samples", 61, 52),
            ("code", "Decoded code", 120, 80),
        ):
            self.button_tree.heading(key, text=caption)
            self.button_tree.column(key, width=width, minwidth=minimum,
                                    anchor="center" if key in ("count", "status") else "w")
        self.button_tree.tag_configure("Verified", foreground="#16723d")
        self.button_tree.tag_configure("Mismatch", foreground="#b24b22")
        self.button_tree.tag_configure("Not learned", foreground="#7a8490")
        self.button_tree.pack(fill="both", expand=True)
        self.button_tree.bind("<<TreeviewSelect>>", self._select_button)
        ttk.Label(right, text="Selected button", font=("Segoe UI Semibold", 13)).pack(anchor="w", pady=(0, 8))
        # Keep capture controls visible even when the user narrows the window.
        controls = ttk.Frame(right)
        controls.pack(side="bottom", fill="x")
        ttk.Label(controls, textvariable=self.aim_var, wraplength=280,
                  justify="left").pack(fill="x", pady=(6, 8))
        self.capture_btn = ttk.Button(controls, text="Capture selected button (USB)", command=self.capture)
        self.capture_btn.pack(fill="x", ipady=7)
        save_actions = ttk.Frame(controls)
        save_actions.pack(fill="x", pady=(6, 0))
        self.save_button_btn = ttk.Button(save_actions, text="Save",
                                          command=self.save_current_button)
        self.save_button_btn.pack(side="left", fill="x", expand=True, padx=(0, 4), ipady=5)
        self.save_next_btn = ttk.Button(save_actions, text="Save & Learn another",
                                         command=self.learn_another)
        self.save_next_btn.pack(side="left", fill="x", expand=True, padx=(4, 0), ipady=5)
        capture_tools = ttk.Frame(controls)
        capture_tools.pack(fill="x", pady=(6, 0))
        for label, cmd in (("Review captures...", self.browse_samples),
                           ("Use best match", self.choose_best),
                           ("Remove last", self.remove_capture)):
            ttk.Button(capture_tools, text=label, command=cmd).pack(
                side="left", expand=True, fill="x", padx=1)
        self.details = tk.Text(right, wrap="word", height=8, state="disabled", relief="flat",
                               background="#f5f6f8", foreground="#222222", padx=12, pady=12)
        self.details.pack(fill="both", expand=True)


    def open_sofabaton_ble(self):
        """Connect the U1; all subsequent IR learning happens in this window."""
        SofaBatonBleDialog(self)

    def _using_bluetooth_learner(self) -> bool:
        """Auto selects the verified U1; explicit USB receivers stay USB."""
        return (self.u1_session.snapshot().connected
                and self.receiver_var.get() == AUTO)

    def _refresh_capture_mode(self):
        if not hasattr(self, "capture_btn"):
            return
        if self._using_bluetooth_learner():
            self.capture_btn.configure(text="Capture selected button (SofaBaton U1)")
            self.aim_var.set(
                "Aim the original remote at the SofaBaton U1. "
                "When the U1 screen shows Learning, press and release the source button.")
        else:
            self.capture_btn.configure(text="Capture selected button (USB)")
            self.aim_var.set(
                "USB: aim the original remote at the learning receiver, "
                "about 5–10 cm away.")

    def _refresh_u1_status(self):
        """Only the Tk main thread updates UI; snapshot is thread-safe."""
        snapshot = self.u1_session.snapshot()
        new_state = (snapshot.state, snapshot.name, snapshot.address, snapshot.detail)
        if new_state != self._u1_last_status:
            self._u1_last_status = new_state
            self.u1_state_var.set(
                f"CONNECTED — READY ({snapshot.name})" if snapshot.connected
                else "BLUETOOTH — CONNECTING..." if snapshot.state == "CONNECTING"
                else "BLUETOOTH — CONNECTION LOST" if snapshot.state == "LOST"
                else "BLUETOOTH — CONNECTION FAILED" if snapshot.state == "FAILED"
                else "BLUETOOTH — NOT CONNECTED")
            self.u1_detail_var.set(snapshot.detail)
            self._render_connection_status()
            self._refresh_capture_mode()
        # Re-arm one timer only. Manual refresh after Connect must not create
        # a second independent polling loop.
        if self._u1_poll_after is not None:
            self.after_cancel(self._u1_poll_after)
        self._u1_poll_after = self.after(400, self._refresh_u1_status)

    def disconnect_u1(self):
        """Disconnect on a worker so Bluetooth cleanup never freezes Tk."""
        if not self.u1_session.snapshot().connected or self.running:
            return
        self.u1_disconnect_btn.configure(state="disabled")

        def work():
            try:
                self.u1_session.disconnect()
            except Exception as exc:
                self.u1_session._state(
                    "FAILED", detail=f"Disconnect failed: {exc}")
            # The regular status poll handles foreground updates.

        threading.Thread(target=work, daemon=True).start()

    def store_external_capture(self, carrier: int, timings: list[int],
                               source: str) -> str:
        """Save independently verified BLE IR capture to the selected button.

        A failed disk write rolls back the in-memory addition. No U1
        programming is performed; the only mutation is the local library.
        """
        device = self._selected_device()
        button = self._selected_button()
        if not device or not button:
            raise ValueError(
                "Select a destination device and button in the main window first.")
        if self.running:
            raise RuntimeError("Finish the current IR learning operation first.")
        cap = core.make_capture(carrier, timings, source)
        previous = button.get("selected", 0)
        button["captures"].append(cap)
        button["selected"] = core.best_capture_index(button)
        try:
            self._save()
        except Exception:
            button["captures"].pop()
            button["selected"] = previous
            raise
        self._reload_buttons(button["id"])
        self.status_var.set(
            f"Saved Bluetooth IR to '{button['label']}' "
            f"({carrier:,} Hz, {len(timings)} timings). Export .irplus to test.")
        return button["label"]

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
                state = core.capture_status(b)
                self.button_tree.insert("", "end", iid=b["id"],
                                        values=(b["label"], state,
                                                f"{match}/{total}" if total else "-", code),
                                        tags=(state,))
            ids = [b["id"] for b in device["buttons"]]
            if keep not in ids:
                keep = ids[0] if ids else None
            states = [core.capture_status(b) for b in device["buttons"]]
            learned = sum(state != "Not learned" for state in states)
            verified = states.count("Verified")
            conflicts = states.count("Mismatch")
            self.progress_var.set(
                f"{learned} / {len(states)} with IR codes  |  "
                f"{verified} verified  |  {conflicts} mismatches"
            )
        else:
            keep = None
            self.progress_var.set("No device selected")
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
            lines = [f"DEVICE: {device['name']}", f"BUTTON: {button['label']}",
                     f"STATUS: {core.capture_status(button)}", ""]
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

    def edit_layout(self):
        device = self._selected_device()
        if not device:
            return messagebox.showwarning("Layout", "Select a device first.")
        if not device["buttons"]:
            return messagebox.showwarning("Layout", "Add buttons to the device first.")

        def after_save():
            self._save()
            self._reload_buttons(self.active_button)
            self.status_var.set(f"Saved remote layout: {device['name']}")

        LayoutDialog(self, device, after_save)

    def browse_samples(self):
        """Inspect a button's raw captures and select the sample to export."""
        button = self._selected_button()
        if not button or not button["captures"]:
            return messagebox.showinfo("Captured signals", "This button has no saved captures.")
        modal = tk.Toplevel(self)
        modal.title("Capture history - " + button["label"])
        modal.geometry("850x410")
        modal.minsize(600, 300)
        modal.transient(self)
        body = ttk.Frame(modal, padding=12)
        body.pack(fill="both", expand=True)
        ttk.Label(body, text="Choose the recording to use for export. Matching samples share a fingerprint.").pack(anchor="w", pady=(0, 10))
        tree = ttk.Treeview(body, columns=("n", "carrier", "length", "code", "source"),
                            show="headings", selectmode="browse")
        for name, caption, width in (("n", "#", 50), ("carrier", "Carrier", 110),
                                     ("length", "Timings", 85), ("code", "Code / fingerprint", 250),
                                     ("source", "Source", 180)):
            tree.heading(name, text=caption)
            tree.column(name, width=width)
        tree.pack(fill="both", expand=True)
        status = tk.StringVar()
        ttk.Label(body, textvariable=status).pack(anchor="w", pady=(7, 4))
        def refresh():
            tree.delete(*tree.get_children())
            for i, sample in enumerate(button["captures"]):
                decoded = core.decode_nec(sample["timings_us"])
                sig = decoded["bytes"] if decoded else core.signature_of_capture(sample)[:26]
                tree.insert("", "end", iid=str(i),
                            values=(i+1, f"{sample['carrier_hz']:,} Hz",
                                    len(sample["timings_us"]), sig, sample.get("source", "")))
            if button["captures"]:
                sel = min(button.get("selected", 0), len(button["captures"]) - 1)
                tree.selection_set(str(sel))
                status.set(f"Selected for export: sample {sel+1} / {len(button['captures'])}")
        def choose():
            sel = tree.selection()
            if not sel:
                return
            button["selected"] = int(sel[0])
            self._save()
            self._reload_buttons(button["id"])
            self.status_var.set(f"Selected capture {button['selected']+1} for {button['label']}")
            modal.destroy()
        def delete():
            sel = tree.selection()
            if not sel:
                return
            pos = int(sel[0])
            if not messagebox.askyesno("Delete capture", f"Remove capture {pos+1}?", parent=modal):
                return
            button["captures"].pop(pos)
            button["selected"] = core.best_capture_index(button)
            self._save()
            self._reload_buttons(button["id"])
            if not button["captures"]:
                modal.destroy()
            else:
                refresh()
        actions = ttk.Frame(body)
        actions.pack(fill="x")
        ttk.Button(actions, text="Use selected for export", command=choose).pack(side="left")
        ttk.Button(actions, text="Delete selected", command=delete).pack(side="left", padx=8)
        ttk.Button(actions, text="Close", command=modal.destroy).pack(side="right")
        refresh()

    def restore_library(self):
        path = filedialog.askopenfilename(title="Restore library backup", filetypes=[("IR Studio library JSON", "*.json")])
        if not path:
            return
        try:
            restored = core.load_library(Path(path))
            num_devices = len(restored["devices"])
            num_buttons = sum(len(d["buttons"]) for d in restored["devices"])
            if not messagebox.askyesno("Restore complete library",
                f"Restore {num_devices} device(s) and {num_buttons} button(s)?\n\n"
                "This replaces the current library. A safety backup of the current library "
                "will be saved first.", parent=self):
                return
            if self.library_path.exists():
                import datetime
                stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
                backup_dir = self.folder / "Backups"
                backup_dir.mkdir(parents=True, exist_ok=True)
                shutil.copy2(self.library_path, backup_dir / f"before-restore-{stamp}.json")
            core.save_library(self.library_path, restored)
            self.library = restored
            self.active_device = self.active_button = None
            self._reload_devices()
            self.status_var.set(f"Restored {num_devices} device(s), {num_buttons} button(s).")
        except Exception as exc:
            messagebox.showerror("Restore failed", str(exc))

    def _harmony_error(self, error):
        """Offer actionable USB recovery steps after a failed probe/capture.

        Never retry USB commands automatically: the user must reconnect or
        select a port and explicitly press Connect / Retry again.
        """
        details = str(error).strip() or "No technical details available."
        is_ocrustar = ("Ocrustar" in self.receiver_var.get()
                        or any(name in details for name in ("Ocrustar", "libusb", "WinUSB")))
        recovery = (
            "Unable to communicate with the IR learning receiver.\n\n"
            "1. Make sure the Harmony and original remote have working "
            "batteries, if battery-powered.\n"
            "2. Disconnect the receiver from USB, then reconnect it.\n"
            "3. If that fails, try a different USB port directly on your PC.\n"
            "4. Click Refresh USB, then Connect / Retry.\n"
            "5. Close any other Harmony or IR-learning software.\n"
        )
        if is_ocrustar:
            recovery += (
                "\nOcrustar: USB driver access may need checking. Do not replace "
                "drivers until the device ID and interfaces are verified.\n"
            )
        # Keep the original diagnostic available without filling the popup
        # with the entire session trace; full traces remain in Logs.
        summary = details if len(details) <= 1300 else details[:900] + "\n... (see Logs for complete details)"
        messagebox.showerror(
            "IR Learner — Connection Failed",
            recovery + "\nTechnical details:\n" + summary,
            parent=self)

    @staticmethod
    def _flow_actions(frame, buttons, columns):
        """Re-grid existing controls instead of truncating the last button."""
        for button in buttons:
            button.grid_forget()
        for column in range(3):
            frame.columnconfigure(column, weight=1 if column < columns else 0)
        for index, button in enumerate(buttons):
            button.grid(row=index // columns, column=index % columns,
                        sticky="ew", padx=2, pady=1)

    def _fit_action_bars(self, frame, available_width, groups, wide_threshold):
        """Keep labels usable when the device/button panes are squeezed."""
        columns = 3 if available_width >= wide_threshold else 2
        if getattr(frame, "_action_columns", None) == columns:
            return
        frame._action_columns = columns
        for group in groups:
            self._flow_actions(group[0].master, group, columns)

    def _render_connection_status(self):
        """Display exactly one red/green connection state and device name.

        Bluetooth readiness is live and verified. USB readiness represents
        the most recent successful handshake (USB probing closes afterward),
        as it did in previous versions. Do not show a red USB warning while
        a verified U1 BLE connection is active.
        """
        snap = self.u1_session.snapshot()
        usb_ready = (
            self.connected_model is not None and
            self._usb_connection_state in ("ready", "armed", "captured")
        )
        if snap.connected:
            label = f"CONNECTED — SofaBaton U1 ({snap.name})"
            color = "#147b3b"
        elif self._usb_connection_state == "armed" and self.running:
            # A USB capture receiver can be actively armed even when the
            # user skipped a separate pre-capture probe.
            label = f"CONNECTED — {self.connected_model or 'USB IR learner'}"
            color = "#147b3b"
        elif usb_ready:
            label = f"CONNECTED — {self.connected_model}"
            color = "#147b3b"
        elif snap.state == "CONNECTING" or self._usb_connection_state == "connecting":
            label = "CONNECTING..."
            color = "#9a6300"
        else:
            label = "NOT CONNECTED"
            color = "#bc2525"

        self.connection_state_var.set(label)
        self.connection_heading.configure(foreground=color)
        # Disconnect is relevant only when there is a real active U1 session.
        if snap.connected:
            self.u1_disconnect_btn.configure(
                state="disabled" if self.running else "normal")
            if not self.u1_disconnect_btn.winfo_manager():
                self.u1_disconnect_btn.pack(side="left", padx=(14, 0))
        else:
            self.u1_disconnect_btn.configure(state="disabled")
            if self.u1_disconnect_btn.winfo_manager():
                self.u1_disconnect_btn.pack_forget()

    def _set_connection(self, state: str, detail: str) -> None:
        """Track USB receiver verification without competing status banners."""
        self._usb_connection_state = state
        self._usb_connection_detail = detail
        self._render_connection_status()

    def _receiver_changed(self, event=None) -> None:
        """Changing receiver invalidates an old USB probe, not U1 Bluetooth."""
        if self.running or self._scan_in_progress:
            return
        self.connected_model = None
        self._set_connection(
            "not-connected", "Receiver selection changed. Click Connect / Retry "
            "to verify the selected USB hardware before learning.")
        self._refresh_capture_mode()

    def _capture_ready(self, operation_id: int) -> None:
        # Ignore queued READY callbacks that arrived after capture completed.
        if operation_id != self._active_operation or not self.running:
            return
        self._set_connection(
            "armed", "Receiver is armed. Aim the source remote at the learner "
            "and briefly press and release one button.")
        self.status_var.set("READY — press and release the source IR button NOW!")

    def _background(self, mode, complete):
        if self.running:
            return
        self.running = True
        self._active_operation += 1
        operation_id = self._active_operation
        self.probe_btn.configure(state="disabled")
        self.capture_btn.configure(state="disabled")
        self.detect_btn.configure(state="disabled")
        self._set_connection(
            "connecting", "Verifying hardware handshake..." if mode == "Probe"
            else "Opening a fresh USB receive session. Wait for READY before "
                 "pressing the source remote.")
        chosen_receiver = self.receiver_var.get()
        self.status_var.set("Identifying IR learning hardware..." if mode == "Probe"
                            else ("Press the source remote once during Concordance capture."
                                  if chosen_receiver == BACKENDS[3] else
                                  "Connecting to IR learner... wait for READY."))

        def job():
            try:
                result = invoke_receiver(chosen_receiver, mode, self.folder / "Logs",
                    on_ready=lambda: self.after(0, lambda:
                        self._capture_ready(operation_id)))
                self.after(0, lambda: complete(result, None))
            except Exception as exc:
                error = str(exc)
                self.after(0, lambda: complete(None, error))
        threading.Thread(target=job, daemon=True).start()

    def _end_background(self):
        self.running = False
        self.probe_btn.configure(state="normal")
        self.capture_btn.configure(state="normal")
        self.detect_btn.configure(state="normal")
        self.receiver_picker.configure(state="readonly")
        self.sofabaton_ble_btn.configure(state="normal")
        self._refresh_capture_mode()

    def detect_learner(self):
        """Read-only enumeration; never starts USB communication or IR capture."""
        if self.running or self._scan_in_progress:
            return
        self._scan_in_progress = True
        self._set_connection(
            "connecting", "Scanning USB device IDs only; no commands are sent.")
        self.detect_btn.configure(state="disabled")
        self.probe_btn.configure(state="disabled")
        self.capture_btn.configure(state="disabled")
        self.status_var.set("Scanning supported USB IR learner IDs...")
        def job():
            h = harmony_attached()
            o = ocrustar_attached()
            def done():
                self._scan_in_progress = False
                self.detect_btn.configure(state="normal")
                self.probe_btn.configure(state="normal")
                self.capture_btn.configure(state="normal")
                self.connected_model = None
                if h and o:
                    self._set_connection(
                        "detected", "Two supported USB learners found. Choose one "
                        "in Receiver, then click Connect / Retry.")
                    self.status_var.set("Harmony 300/350 AND Ocrustar detected. Select the receiver explicitly.")
                elif h:
                    self.receiver_var.set(BACKENDS[1])
                    self._set_connection(
                        "detected", "Harmony 300/350 detected (046D:C124). "
                        "Click Connect / Retry to verify communication.")
                    self.status_var.set("Detected Harmony 300/350 USB HID (046D:C124).")
                elif o:
                    self.receiver_var.set(BACKENDS[2])
                    self._set_connection(
                        "detected", "Ocrustar/ElkSmart detected (045C:02AA). "
                        "Click Connect / Retry to verify communication.")
                    self.status_var.set("Detected Ocrustar/ElkSmart USB (045C:02AA). Windows USB driver access still needs testing.")
                else:
                    self._set_connection(
                        "not-connected", "No supported USB learner found. "
                        "Try unplugging and reconnecting it, then Refresh USB.")
                    self.status_var.set("No supported direct learner found. Check USB and Windows drivers. Unlisted dongles need individual adapters.")
            self.after(0, done)
        threading.Thread(target=job, daemon=True).start()

    def probe(self):
        if self._scan_in_progress:
            return
        def done(result, error):
            self._end_background()
            if error:
                self.connected_model = None
                self._set_connection(
                    "failed", "Could not verify the selected receiver. "
                    "Unplug and reconnect USB, then click Connect / Retry. "
                    "No Harmony programming was changed.")
                self.status_var.set("IR learner handshake failed. See the error message.")
                self._harmony_error(error)
            else:
                caveat = " (experimental IR learning; please verify captures)" if result.get("compatibility") == "experimental" else ""
                self.connected_model = result.get("model")
                self.aim_var.set("Aim the source remote at the USB IR learning receiver, about 5–10 cm away.")
                self._set_connection(
                    "ready", f"{self.connected_model} handshake succeeded. "
                    "The verification USB session is closed; a new session "
                    "will open when you click Capture.")
                self.status_var.set(
                    f"Handshake verified: {self.connected_model}{caveat}. Ready to capture.")
        self._background("Probe", done)

    def capture(self):
        if self._scan_in_progress or self.running:
            return
        if self._using_bluetooth_learner():
            return self._capture_bluetooth()
        b = self._selected_button()
        if not b:
            return messagebox.showwarning("Select button", "Choose a button before capturing")
        button_id = b["id"]
        device_id = self.active_device
        def done(result, error):
            self._end_background()
            if error:
                self._set_connection(
                    "capture-failed", "No usable capture was saved. "
                    "Check the receiver or source remote and use Connect / Retry "
                    "if USB communication has been interrupted.")
                self.status_var.set("Capture failed - no changes were saved.")
                self._harmony_error(error)
                return
            try:
                cap = core.make_capture(int(result["carrier_hz"]), result["timings_us"],
                                        source=result.get("model") or "Harmony IR learner")
            except Exception as exc:
                self._set_connection(
                    "capture-failed", "Received an incomplete IR signal. "
                    "Retry with a short, firm press; the USB session has ended.")
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
            self._set_connection(
                "captured", f"Saved {target['label']} successfully. "
                "The USB capture session has closed. "
                "Choose Save or Save & Learn another.")
            match, count = core.match_count(target)
            decoded = cap["decoded"]
            desc = decoded["bytes"] if decoded else "raw signal (unidentified protocol)"
            self.status_var.set(f"Captured and autosaved {target['label']}: "
                f"{cap['carrier_hz']:,} Hz, {len(cap['timings_us'])} values, "
                f"{desc}; matching captures {match}/{count}. "
                "Choose Save to finish or Save & Learn another to continue.")
        self._background("Capture", done)

    def _bluetooth_capture_ready(self, operation_id):
        if operation_id != self._active_operation or not self.running:
            return
        self.status_var.set(
            "LEARNING START SENT — when the SofaBaton screen says Learning, "
            "aim the original remote at the U1 and press and release the button NOW!")

    def _finish_bluetooth_capture(self, device_id, button_id, result, error):
        """Autosave to the originally selected button, never a later selection."""
        self._end_background()
        if error:
            self.status_var.set(
                "SofaBaton learning failed. No new IR command saved. "
                f"{error}")
            messagebox.showerror(
                "SofaBaton IR learning failed",
                f"{error}\n\nThe app preserves the previous local backup "
                "whenever the preflight read succeeded.", parent=self)
            return
        try:
            capture, backup_path = result
            device = next((d for d in self.library["devices"]
                           if d["id"] == device_id), None)
            button = next((b for b in device["buttons"]
                           if b["id"] == button_id), None) if device else None
            if button is None:
                raise ValueError(
                    "The selected destination button was deleted during learning.")
            cap = core.make_capture(
                int(capture.carrier_hz), capture.timings_us,
                "SofaBaton U1 Bluetooth — learned IR")
            before_selection = button.get("selected", 0)
            button["captures"].append(cap)
            button["selected"] = core.best_capture_index(button)
            try:
                self._save()
            except Exception:
                button["captures"].pop()
                button["selected"] = before_selection
                raise
            if self.active_device == device_id:
                self._reload_buttons(button_id)
            self.status_var.set(
                f"Captured and autosaved {button['label']} via SofaBaton U1 "
                f"({capture.carrier_hz:,} Hz, {len(capture.timings_us)} timings). "
                "Ready to export .irplus or Save & Learn another. "
                f"Prior U1 IR backed up to {backup_path}")
        except Exception as exc:
            messagebox.showerror("Save failed",
                                 f"Bluetooth capture completed but the "
                                 f"library could not be saved:\n{exc}",
                                 parent=self)
            self.status_var.set("Bluetooth capture not saved to the library.")

    def _capture_bluetooth(self):
        """Capture directly from main screen, with automatic U1 preflight backup."""
        if not self._using_bluetooth_learner() or self.running:
            return
        button = self._selected_button()
        device = self._selected_device()
        if not device or not button:
            messagebox.showwarning("Select button",
                                   "Choose a device and button to learn first.",
                                   parent=self)
            return
        device_id, button_id = device["id"], button["id"]
        self.running = True
        self._active_operation += 1
        operation_id = self._active_operation
        for widget in (self.probe_btn, self.capture_btn, self.detect_btn,
                       self.receiver_picker, self.sofabaton_ble_btn):
            widget.configure(state="disabled")
        self.status_var.set(
            "Reading and backing up the previous U1 IR signal... "
            "Wait until the SofaBaton screen displays Learning before pressing.")
        def job():
            try:
                result = self.u1_session.learn_ir_experimental(
                    self.folder,
                    on_ready=lambda: self.after(
                        0, lambda: self._bluetooth_capture_ready(operation_id)))
                self.after(0, lambda: self._finish_bluetooth_capture(
                    device_id, button_id, result, None))
            except Exception as exc:
                err = str(exc)
                self.after(0, lambda: self._finish_bluetooth_capture(
                    device_id, button_id, None, err))
        threading.Thread(target=job, daemon=True).start()

    def save_current_button(self) -> bool:
        """Confirm durable storage of one learned button; never starts learning.

        Successful captures are already autosaved for safety. This explicit
        action flushes the library again and gives a clear finished status.
        It cannot duplicate a capture or create another button.
        """
        if self.running:
            self.status_var.set("IR capture still in progress; finish it before saving.")
            return False
        device = self._selected_device()
        button = self._selected_button()
        if not device or not button:
            messagebox.showwarning("Save", "Select a device and a button first.")
            return False
        if not button.get("captures") and not button.get("pronto_hex"):
            messagebox.showwarning("Save button",
                                   "This button has no IR command yet. Capture or import one first.")
            return False
        try:
            self._save()
        except Exception as error:
            messagebox.showerror("Save", f"Could not save the library:\n{error}")
            self.status_var.set("Save unsuccessful; previous library not replaced.")
            return False
        self.status_var.set(
            f"Saved {button['label']} to {device['name']}. "
            "Finished — no new capture started. You can export this remote as .irplus."
        )
        return True

    def learn_another(self):
        """Add a named button to the active remote and start another capture."""
        if self.running:
            return
        # Same save path as the standalone Save action; abort if this
        # capture cannot be saved. Never duplicate an already saved sample.
        if not self.save_current_button():
            return
        device = self._selected_device()
        label = simpledialog.askstring("Learn another button",
            "Name the new button (Power, Volume Up, Input...):", parent=self)
        if not label or not label.strip():
            return
        try:
            button = core.new_button(label)
        except ValueError as error:
            return messagebox.showerror("Button", str(error))
        if any(b["label"].casefold() == button["label"].casefold()
               for b in device["buttons"]):
            return messagebox.showwarning("Duplicate name",
                                          "A button with that name already exists.")
        device["buttons"].append(button)
        try:
            self._save()
        except Exception as error:
            device["buttons"].remove(button)
            return messagebox.showerror("Save", str(error))
        self._reload_buttons(button["id"])
        self.capture()

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
        path = filedialog.askopenfilename(title="Import IR Capture (.json)", filetypes=[("IR Capture (*.json)", "*.json")])
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
        path = filedialog.askopenfilename(title="Import Device (.irplus)", filetypes=[("IR Device (*.irplus)", "*.irplus"), ("XML", "*.xml")])
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
        path = filedialog.asksaveasfilename(title="Export Device (.irplus)", defaultextension=".irplus",
            initialfile=f"{device['name'].replace('/', '-')}.irplus", filetypes=[("irplus remote", "*.irplus")])
        if not path:
            return
        try:
            count = core.export_irplus(device, Path(path), int(self.repeat_var.get()))
            skipped = len(device["buttons"]) - count
            self.status_var.set(f"Exported {device['name']}: {count} button(s) to {path}")
            messagebox.showinfo("Device exported",
                f"Device: {device['name']}\n"
                f"Buttons exported: {count}\n"
                f"Unlearned buttons skipped: {skipped}\n\n"
                f"Saved to:\n{path}\n\n"
                "Import this .irplus file on a compatible phone.")
        except Exception as exc:
            messagebox.showerror("Export failed", str(exc))

    def backup_library(self):
        path = filedialog.asksaveasfilename(title="Back up IR library", defaultextension=".json",
            initialfile="IR-Remote-Studio-library.json", filetypes=[("JSON backup", "*.json")])
        if path:
            try:
                self._save()
                shutil.copy2(self.library_path, path)
                self.status_var.set(f"Backed up library to {path}")
            except Exception as exc:
                messagebox.showerror("Backup failed", str(exc))

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
        if self._u1_poll_after is not None:
            self.after_cancel(self._u1_poll_after)
            self._u1_poll_after = None
        self.u1_session.shutdown()
        self.destroy()


def main():
    app = Studio()
    app.mainloop()
