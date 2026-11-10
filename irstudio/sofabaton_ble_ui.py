"""SofaBaton U1 connection and optional stored-code troubleshooting UI."""
from __future__ import annotations

import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from irstudio import sofabaton_ble as ble
from irstudio import sofabaton_u1_read as u1


class SofaBatonBleDialog(tk.Toplevel):
    def __init__(self, parent, *, auto_scan=True):
        super().__init__(parent)
        self.title("Connect SofaBaton U1")
        self.minsize(710, 420)
        self.geometry("850x510")
        self._busy = False
        self._devices: list[ble.BleEntry] = []
        self._capture: u1.IrRead | None = None
        self.slot_var = tk.StringVar(value="8")
        self.pair_var = tk.BooleanVar(value=False)
        self.link_var = tk.StringVar(value="NOT CONNECTED")
        self._last_link = None
        self.advanced_open = False
        self.status_var = tk.StringVar(
            value="Searching for your SofaBaton U1 automatically...")
        self.protocol("WM_DELETE_WINDOW", self.destroy)

        outer = ttk.Frame(self, padding=(16, 14))
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="SofaBaton U1",
                  font=("Segoe UI Semibold", 17)).pack(anchor="w")
        ttk.Label(
            outer,
            text="Put batteries in the U1. Hold the - and E buttons together "
                 "until Pairing appears on its screen. Keep the official "
                 "SofaBaton phone app closed. Scanning and connection start "
                 "automatically; Windows Bluetooth must be enabled.",
            foreground="#536277", wraplength=790, justify="left"
        ).pack(anchor="w", pady=(5, 9))

        self.link_label = ttk.Label(
            outer, textvariable=self.link_var,
            foreground="#bc2525", font=("Segoe UI Semibold", 12))
        self.link_label.pack(anchor="w")
        toolbar = ttk.Frame(outer)
        toolbar.pack(fill="x", pady=(11, 6))
        self.scan_button = ttk.Button(
            toolbar, text="Scan again", command=self.scan)
        self.scan_button.pack(side="left", ipadx=9, ipady=5)
        self.connect_button = ttk.Button(
            toolbar, text="Connect U1", command=self.connect_u1,
            state="disabled")
        self.connect_button.pack(side="left", padx=(9, 0), ipadx=11, ipady=5)
        self.disconnect_button = ttk.Button(
            toolbar, text="Disconnect", command=self.disconnect_u1,
            state="disabled")
        self.disconnect_button.pack(side="left", padx=(9, 0), ipadx=9, ipady=5)
        ttk.Label(
            outer, textvariable=self.status_var, wraplength=790,
            foreground="#536277").pack(anchor="w", pady=(6, 8))

        ttk.Label(outer, text="Bluetooth devices").pack(anchor="w")
        self.device_list = tk.Listbox(
            outer, height=5, exportselection=False, activestyle="none")
        self.device_list.pack(fill="x", pady=(4, 12))
        self.device_list.bind("<<ListboxSelect>>", self._select_device)

        ttk.Label(
            outer,
            text="After connecting, return to the main window to learn IR "
                 "commands. The U1 stays connected while you work.",
            foreground="#315c88", wraplength=790,
            justify="left").pack(anchor="w", pady=(0, 11))

        self.advanced_toggle_button = ttk.Button(
            outer, text="Troubleshooting  ▸", command=self.toggle_advanced)
        self.advanced_toggle_button.pack(anchor="w")
        # Never show slots or low-level diagnostics in the normal workflow.
        self.advanced_panel = ttk.LabelFrame(
            outer, text="Troubleshooting — stored IR and Bluetooth log", padding=10)

        read_row = ttk.Frame(self.advanced_panel)
        read_row.pack(fill="x", pady=(0, 7))
        ttk.Label(read_row, text="Stored command slot:").pack(side="left")
        self.slot_combo = ttk.Combobox(
            read_row, textvariable=self.slot_var,
            values=[str(i) for i in u1.ALLOWED_SLOTS],
            state="readonly", width=4)
        self.slot_combo.pack(side="left", padx=(8, 12))
        self.read_button = ttk.Button(
            read_row, text="Read stored code",
            command=self.read_ir, state="disabled")
        self.read_button.pack(side="left", ipadx=7, ipady=4)
        self.save_capture_button = ttk.Button(
            read_row, text="Save to library button",
            command=self.save_capture, state="disabled")
        self.save_capture_button.pack(
            side="left", padx=(10, 0), ipadx=7, ipady=4)

        tools_row = ttk.Frame(self.advanced_panel)
        tools_row.pack(fill="x", pady=(0, 5))
        self.inspect_button = ttk.Button(
            tools_row, text="Inspect Bluetooth",
            command=self.inspect, state="disabled")
        self.inspect_button.pack(side="left", ipadx=6)
        self.pair_check = ttk.Checkbutton(
            tools_row, text="Request Windows pairing (only if needed)",
            variable=self.pair_var)
        self.pair_check.pack(side="left", padx=(12, 0))
        ttk.Button(
            tools_row, text="Save report...", command=self.save).pack(
                side="right")
        ttk.Button(
            tools_row, text="Copy report", command=self.copy).pack(
                side="right", padx=(0, 8))
        ttk.Label(
            self.advanced_panel, text="Bluetooth activity log (optional):",
            foreground="#536277").pack(anchor="w", pady=(5, 0))
        report_frame = ttk.Frame(self.advanced_panel)
        report_frame.pack(fill="both", expand=True, pady=(4, 0))
        self.report = tk.Text(
            report_frame, wrap="word", font=("Consolas", 10), height=6)
        self.report.pack(side="left", fill="both", expand=True)
        scrollbar = ttk.Scrollbar(
            report_frame, orient="vertical", command=self.report.yview)
        scrollbar.pack(side="right", fill="y")
        self.report.configure(yscrollcommand=scrollbar.set)
        self._append(
            "SofaBaton U1 connection log. Learning happens in "
            "the main Studio window.\n\n"
        )
        self.transient(parent)
        # A live session survives closing this popup and can be reused here.
        snap = self.master.u1_session.snapshot()
        if snap.connected:
            self._devices = [ble.BleEntry(
                name=snap.name, address=snap.address, rssi=None,
                advertised_services=(u1.SVC,), likely_sofabaton=True)]
            self.device_list.insert(
                "end", f"{snap.name} — CONNECTED — {snap.address}")
            self.device_list.selection_set(0)
        self._poll_connection()
        # Schedule after construction so controls, lists and callbacks exist.
        # Do not scan when reopening an already connected session: users may
        # be opening Advanced to read an existing stored IR command.
        if auto_scan and not snap.connected:
            self.after(250, self._auto_scan_on_open)

    def _auto_scan_on_open(self):
        if not self.winfo_exists() or self._busy:
            return
        if self.master.u1_session.snapshot().connected:
            return
        self.scan()

    def toggle_advanced(self):
        """Reveal protocol-specific controls only when explicitly requested."""
        self.advanced_open = not self.advanced_open
        # Free vertical space for advanced controls on smaller laptop displays.
        # The device list and diagnostic report retain their own scrolling.
        self.device_list.configure(height=2 if self.advanced_open else 5)
        self.report.configure(height=2 if self.advanced_open else 6)
        if self.advanced_open:
            self.advanced_toggle_button.configure(text="Troubleshooting  ▾")
            self.advanced_panel.pack(fill="both", expand=True, pady=(9, 0))
            # Grow within the display, leaving taskbar clearance.
            self.update_idletasks()
            max_height = max(460, self.winfo_screenheight() - 110)
            target = min(710, max_height)
            if self.winfo_height() < target:
                self.geometry(f"{self.winfo_width()}x{target}")
        else:
            self.advanced_panel.pack_forget()
            self.advanced_toggle_button.configure(text="Troubleshooting  ▸")
            self.geometry(f"{self.winfo_width()}x510")

    def _poll_connection(self):
        if not self.winfo_exists():
            return
        snap = self.master.u1_session.snapshot()
        state = (snap.state, snap.name, snap.address, snap.detail)
        if state != self._last_link:
            self._last_link = state
            self.link_var.set(
                f"CONNECTED — READY: {snap.name}" if snap.connected
                else "CONNECTING..." if snap.state == "CONNECTING"
                else "CONNECTION LOST — reconnect" if snap.state == "LOST"
                else "CONNECTION FAILED — retry" if snap.state == "FAILED"
                else "NOT CONNECTED")
            self.link_label.configure(foreground=(
                "#147b3b" if snap.connected else
                "#9a6300" if snap.state == "CONNECTING" else "#bc2525"))
            self._select_device()
        self.after(450, self._poll_connection)

    def _append(self, content: str):
        if self.winfo_exists():
            self.report.insert("end", content)
            self.report.see("end")

    def _select_device(self, event=None):
        selected = bool(self.device_list.curselection()) and not self._busy
        snap = self.master.u1_session.snapshot()
        active_selection = (selected and snap.connected and
                            self._devices[self.device_list.curselection()[0]].address
                            == snap.address)
        self.connect_button.configure(
            state="normal" if selected and not snap.connected else "disabled")
        self.disconnect_button.configure(
            state="normal" if snap.connected and not self._busy else "disabled")
        self.inspect_button.configure(
            state="normal" if selected and not snap.connected else "disabled")
        self.read_button.configure(
            state="normal" if active_selection else "disabled")
        self.save_capture_button.configure(
            state="normal" if self._capture is not None and not self._busy
            else "disabled")

    def _run(self, name, task, complete, on_error=None):
        if self._busy:
            return
        self._busy = True
        self.scan_button.configure(state="disabled")
        self.connect_button.configure(state="disabled")
        self.disconnect_button.configure(state="disabled")
        self.inspect_button.configure(state="disabled")
        self.read_button.configure(state="disabled")
        self.experimental_learn_button.configure(state="disabled")
        self.save_capture_button.configure(state="disabled")
        self.status_var.set(name + "...")

        def job():
            try:
                result = task()
                error = None
            except Exception as exc:
                result = None
                error = f"{type(exc).__name__}: {exc}"

            def done():
                if not self.winfo_exists():
                    return
                self._busy = False
                self.scan_button.configure(state="normal")
                self._select_device()
                if error is not None:
                    if name == "Connecting U1":
                        self.status_var.set(
                            "Could not connect to the U1. Keep it in Pairing "
                            "mode, check the batteries, and select Scan again. "
                            "Open Troubleshooting if Windows pairing is needed. "
                            f"Details: {error[:160]}")
                    elif name == "Scanning":
                        self.status_var.set(
                            "Bluetooth scan failed. Check Windows Bluetooth "
                            "and press Scan again. " + error[:140])
                    else:
                        self.status_var.set(name + " failed. See report.")
                    self._append(name + " FAILED: " + error + "\n")
                    if on_error is not None:
                        on_error(error)
                else:
                    complete(result)
            try:
                self.after(0, done)
            except (RuntimeError, tk.TclError):
                pass

        threading.Thread(target=job, daemon=True).start()

    def scan(self):
        self._capture = None
        self.save_capture_button.configure(state="disabled")
        self._devices = []
        self.device_list.delete(0, "end")
        self._append("Scanning BLE advertisements (7 seconds)...\n")

        def complete(devices):
            self._devices = devices
            for d in devices:
                quality = f"{d.rssi} dBm" if d.rssi is not None else "RSSI unknown"
                candidate = "  [Possible SofaBaton]" if d.likely_sofabaton else ""
                self.device_list.insert(
                    "end", f"{d.name} — {quality} — {d.address}{candidate}")
            candidate = ble.auto_connect_candidate(devices)
            if candidate is not None:
                index = devices.index(candidate)
                self.device_list.selection_clear(0, "end")
                self.device_list.selection_set(index)
                self.device_list.see(index)
                self._select_device()
                self._append(
                    f"Found U1 {candidate.name} ({candidate.address}); "
                    "connecting automatically without prompting...\n")
                self.status_var.set(
                    f"Found {candidate.name}. Connecting automatically...")
                # Reuse exactly the verified, persistent BLE connection path.
                self._connect_device(candidate, automatic=True)
            else:
                sb_count = sum(
                    bool(ble.auto_connect_candidate([d])) for d in devices)
                if sb_count > 1:
                    self.status_var.set(
                        "More than one U1 was found. Select yours and click "
                        "Connect U1; automatic selection was skipped.")
                elif devices:
                    self.status_var.set(
                        f"Found {len(devices)} Bluetooth devices, but no unique "
                        "SB-prefixed U1. Keep the remote in Pairing mode and "
                        "press Scan again, or manually select it and Connect U1.")
                else:
                    self.status_var.set(
                        "No Bluetooth devices found. Check the U1 batteries, "
                        "hold - and E until Pairing, then press Scan again.")
            self._append(f"BLE scan: {len(devices)} advertising device(s).\n")
            for d in devices:
                self._append(f"  {d.name} / {d.address} / "
                             f"{d.rssi if d.rssi is not None else '?'} dBm\n"
                             f"    Advertised UUIDs: "
                             f"{', '.join(d.advertised_services) or '(none)'}\n")
            self._append("\n")
        self._run("Scanning", lambda: ble.scan_ble(7.0), complete)

    def connect_u1(self):
        """Manual fallback after scanning, for atypical U1 advertisements."""
        selection = self.device_list.curselection()
        if not selection or self._busy:
            return
        self._connect_device(self._devices[selection[0]], automatic=False)

    @staticmethod
    def _authentication_required(error: Exception) -> bool:
        """Retry Windows bonding only for explicit Bluetooth auth failures."""
        text = str(error).casefold()
        return any(term in text for term in (
            "insufficient authentication", "authentication required",
            "pairing required", "not paired", "bond required",
            "insufficient encryption"))

    def _connect_device(self, device, *, automatic: bool):
        """Auto-connect a recognized SB ID; manually confirm other candidates.

        Normal connect does not request an unnecessary Windows OS bond,
        because the tested U1 works without one. An explicit BLE
        authentication-required error can trigger the Windows pairing
        procedure as part of the user's requested automatic workflow.
        The U1 identity is always verified before reporting connected.
        """
        if self._busy:
            return
        if automatic:
            if ble.auto_connect_candidate([device]) is None:
                self.status_var.set(
                    "Auto-connect skipped: device is not a verified "
                    "SB-prefixed U1 advertising candidate.")
                return
        elif not (device.likely_sofabaton or any(
                uuid.lower() == u1.SVC for uuid in device.advertised_services)):
            messagebox.showwarning(
                "Identify the U1",
                "Select your identified SofaBaton U1 (for example SB...). "
                "Connecting to unknown Bluetooth devices is disabled.",
                parent=self)
            return
        pair = bool(self.pair_var.get()) if not automatic else False
        if not automatic and not messagebox.askokcancel(
            "Connect to SofaBaton U1?",
            f"Connect to {device.name} over Bluetooth?\n\n"
            + ("Windows pairing will be requested. You may need to confirm "
               "on Windows.\n\n" if pair else
               "Windows bonding is usually unnecessary.\n\n")
            + "The connection stays active on the main screen. "
              "Only identity inquiries are sent.",
            parent=self):
            return
        self._append(
            f"Connecting to {device.name} ({device.address}); "
            f"Windows bonding requested: {pair}.\n")

        def work():
            try:
                return self.master.u1_session.connect(
                    device.address, device.name, pair=pair)
            except Exception as exc:
                if automatic and self._authentication_required(exc):
                    self._append_on_ui(
                        "U1 requested authentication; trying Windows "
                        "pairing automatically. A Windows prompt may appear.\n")
                    return self.master.u1_session.connect(
                        device.address, device.name, pair=True)
                raise

        def complete(snapshot):
            self._append("U1 identity verified; Bluetooth remains connected.\n")
            self.master._refresh_u1_status()
            # Connection succeeded. Return to the main Windows app as requested.
            self.destroy()
        self._run("Connecting U1", work, complete)

    def _append_on_ui(self, message: str):
        try:
            self.after(0, lambda: self._append(message))
        except (RuntimeError, tk.TclError):
            pass

    def disconnect_u1(self):
        if self._busy or not self.master.u1_session.snapshot().connected:
            return

        def complete(_):
            self._append("U1 disconnected from Windows.\n")
            self.status_var.set("Disconnected. Select the U1 and click Connect U1.")
        self._run("Disconnecting U1",
                  self.master.u1_session.disconnect, complete)

    def start_main_capture(self, on_complete, on_error):
        """Run the exact v0.3.3 dialog worker, but report to main Studio.

        Preserve the original connection, notify, preflight backup, and
        learning implementation, including its thread scheduling.
        No LEARN 8 prompt and no Bluetooth protocol changes.
        """
        if self._busy:
            on_error("Bluetooth learning is already running.")
            return
        snap = self.master.u1_session.snapshot()
        if not snap.connected:
            on_error("Connect your verified SofaBaton U1 first.")
            return
        selection = self.device_list.curselection()
        if not selection or self._devices[selection[0]].address != snap.address:
            on_error("The selected U1 is not the active Bluetooth connection.")
            return
        self._capture = None
        self.save_capture_button.configure(state="disabled")
        self._append(
            "Reading and backing up U1 slot 8, then using the original "
            "v0.3.3 learning routine.\n")
        self.status_var.set(
            "When SofaBaton enters Learning, aim the original IR remote "
            "and press and release the command.")

        def complete(pair):
            try:
                self._capture = pair[0]
                on_complete(pair)
            finally:
                self.destroy()

        def failed(error):
            try:
                on_error(error)
            finally:
                self.destroy()

        self._run("SofaBaton learning", lambda:
                  self.master.u1_session.learn_ir_experimental(
                      self.master.folder), complete, on_error=failed)

    def read_ir(self):
        """Opt-in query of IR already stored in the selected U1 slot."""
        selected = self.device_list.curselection()
        if not selected or self._busy:
            return
        device = self._devices[selected[0]]
        slot = int(self.slot_var.get())
        snap = self.master.u1_session.snapshot()
        if not snap.connected or snap.address != device.address:
            self.status_var.set("Connect U1 first to read an existing IR slot.")
            return
        if not (device.likely_sofabaton or any(
                uuid.lower() == u1.SVC for uuid in device.advertised_services)):
            messagebox.showwarning(
                "Identify the U1",
                "Select a positively identified SofaBaton U1 (for example SB...). "
                "Other BLE devices are not eligible for IR queries.",
                parent=self)
            return
        if not messagebox.askokcancel(
            "Read stored U1 infrared code?",
            f"Read the IR code already stored in slot {slot} of {device.name}?\n\n"
            "Experimental: uses the verified active Bluetooth connection "
            "to send read-code (0x0C) only. No new learning, profile updates "
            "or firmware commands are sent.\n\n"
            "Only proceed if this really is your SofaBaton U1. "
            "Disconnect the SofaBaton phone app first.",
            parent=self):
            return
        self._capture = None
        self.save_capture_button.configure(state="disabled")
        self._append(f"Reading stored U1 slot {slot} from {device.name}...\n")

        def complete(result):
            self._capture = result
            self._select_device()
            self._append(
                f"Verified stored U1 slot {result.slot}: "
                f"{result.carrier_hz:,} Hz, "
                f"{len(result.timings_us)} timing values, "
                f"{result.fragments} ordered fragments.\n"
                "No new learning or U1 programming performed.\n\n")
            self.status_var.set(
                f"Stored IR read succeeded ({result.carrier_hz:,} Hz). "
                "Choose a device and button in the main window, "
                "then Save to selected button.")
        self._run("Reading stored IR",
                  lambda: self.master.u1_session.read_saved_ir(slot), complete)

    def save_capture(self):
        if self._busy or self._capture is None:
            return
        try:
            label = self.master.store_external_capture(
                self._capture.carrier_hz,
                self._capture.timings_us,
                f"{self._capture.model}, slot {self._capture.slot}")
        except ValueError as error:
            messagebox.showwarning("Select a button", str(error), parent=self)
            return
        except Exception as error:
            messagebox.showerror("Save failed", str(error), parent=self)
            return
        self._append(f"Saved U1 slot {self._capture.slot} to '{label}' "
                     "in IR Remote Studio library.\n")
        self.status_var.set(f"Saved to {label}; ready for .irplus export.")

    def inspect(self):
        selection = self.device_list.curselection()
        if not selection or self._busy:
            return
        if self.master.u1_session.snapshot().connected:
            self.status_var.set("Already connected. No second BLE inspection session needed.")
            return
        device = self._devices[selection[0]]
        if not messagebox.askokcancel(
            "Inspect BLE services?",
            f"Connect temporarily to {device.name} to inspect its GATT "
            "service and characteristic UUIDs?\n\n"
            "This will not write commands, read characteristic values, "
            "pair automatically, or change remote programming.\n\n"
            "It may temporarily disconnect the official SofaBaton app.",
            parent=self):
            return
        self._append(f"Inspecting {device.name} ({device.address})...\n")

        def complete(lines):
            self._append("\n".join(lines) + "\n\n")
            self.status_var.set(
                f"Bluetooth service information for {device.name} collected; "
                "BLE inspection session closed.")
        self._run("Inspecting services",
                  lambda: ble.inspect_gatt(device.address), complete)

    def _report_text(self):
        return self.report.get("1.0", "end-1c")

    def copy(self):
        self.clipboard_clear()
        self.clipboard_append(self._report_text())
        self.status_var.set("Report copied. Consider redacting Bluetooth addresses before sharing.")

    def save(self):
        path = filedialog.asksaveasfilename(
            parent=self, title="Save BLE diagnostic report",
            initialfile="sofabaton-u1-ble-diagnostics.txt",
            defaultextension=".txt", filetypes=[("Text report", "*.txt")])
        if not path:
            return
        try:
            from pathlib import Path
            Path(path).write_text(self._report_text(), encoding="utf-8")
            self.status_var.set(f"Saved local report to {path}")
        except OSError as error:
            messagebox.showerror("Save report", str(error), parent=self)
