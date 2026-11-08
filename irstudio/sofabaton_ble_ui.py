"""U1 Bluetooth connection, optional OS pairing, diagnostics and stored IR."""
from __future__ import annotations

import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from irstudio import sofabaton_ble as ble
from irstudio import sofabaton_u1_read as u1


class SofaBatonBleDialog(tk.Toplevel):
    def __init__(self, parent):
        super().__init__(parent)
        self.title("SofaBaton U1 — Bluetooth / Saved IR")
        self.minsize(710, 465)
        self.geometry("830x600")
        self._busy = False
        self._devices: list[ble.BleEntry] = []
        self._capture: u1.IrRead | None = None
        self.slot_var = tk.StringVar(value="8")
        self.pair_var = tk.BooleanVar(value=False)
        self.link_var = tk.StringVar(value="NOT CONNECTED")
        self._last_link = None
        self.status_var = tk.StringVar(
            value="Scan first. Only Bluetooth metadata is inspected; no IR commands are sent.")
        self.protocol("WM_DELETE_WINDOW", self.destroy)

        outer = ttk.Frame(self, padding=14)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="SofaBaton U1 — Bluetooth Connection",
                  font=("Segoe UI Semibold", 15)).pack(anchor="w")
        ttk.Label(
            outer,
            text=("Select your U1 and click Connect U1 to maintain a Bluetooth "
                  "connection. Pairing with Windows is optional. "
                  "Only stored-IR reads are supported; no new learning or programming."),
            wraplength=775, justify="left").pack(anchor="w", pady=(7, 12))

        toolbar = ttk.Frame(outer)
        toolbar.pack(fill="x")
        self.scan_button = ttk.Button(toolbar, text="Scan Bluetooth",
                                      command=self.scan)
        self.scan_button.pack(side="left", ipadx=10, ipady=5)
        self.inspect_button = ttk.Button(
            toolbar, text="Inspect selected (read-only)",
            command=self.inspect, state="disabled")
        self.inspect_button.pack(side="left", padx=(9, 0), ipadx=7, ipady=5)
        connect_row = ttk.Frame(outer)
        connect_row.pack(fill="x", pady=(10, 0))
        self.connect_button = ttk.Button(
            connect_row, text="Connect U1", command=self.connect_u1,
            state="disabled")
        self.connect_button.pack(side="left", ipadx=12, ipady=6)
        self.disconnect_button = ttk.Button(
            connect_row, text="Disconnect U1", command=self.disconnect_u1,
            state="disabled")
        self.disconnect_button.pack(side="left", padx=(10, 0), ipadx=8, ipady=6)
        self.pair_check = ttk.Checkbutton(
            connect_row, text="Request Windows pairing (only if needed)",
            variable=self.pair_var)
        self.pair_check.pack(side="left", padx=(16, 0))
        self.link_label = ttk.Label(
            outer, textvariable=self.link_var,
            foreground="#bc2525", font=("Segoe UI Semibold", 12))
        self.link_label.pack(anchor="w", pady=(8, 0))
        # The stored-code reader is distinct from live IR learning.
        read_row = ttk.Frame(outer)
        read_row.pack(fill="x", pady=(12, 3))
        ttk.Label(read_row, text="Existing U1 IR slot:").pack(side="left")
        ttk.Combobox(
            read_row, textvariable=self.slot_var, values=[str(i) for i in u1.ALLOWED_SLOTS],
            state="readonly", width=4).pack(side="left", padx=(8, 12))
        self.read_button = ttk.Button(
            read_row, text="Read stored IR (test)",
            command=self.read_ir, state="disabled")
        self.read_button.pack(side="left", ipadx=8, ipady=5)
        self.save_capture_button = ttk.Button(
            read_row, text="Save to selected button",
            command=self.save_capture, state="disabled")
        self.save_capture_button.pack(side="left", padx=(10, 0), ipadx=8, ipady=5)
        ttk.Button(toolbar, text="Save report...", command=self.save).pack(side="right")
        ttk.Button(toolbar, text="Copy report", command=self.copy).pack(
            side="right", padx=(0, 8))

        ttk.Label(outer, textvariable=self.status_var,
                  wraplength=790).pack(anchor="w", pady=(11, 7))
        ttk.Label(outer, text="Discovered BLE devices (SofaBaton candidates first):").pack(
            anchor="w")
        self.device_list = tk.Listbox(
            outer, height=7, exportselection=False, activestyle="none")
        self.device_list.pack(fill="x", pady=(4, 10))
        self.device_list.bind("<<ListboxSelect>>", self._select_device)

        ttk.Label(outer, text="Bluetooth session / diagnostic report:").pack(anchor="w")
        report_frame = ttk.Frame(outer)
        report_frame.pack(fill="both", expand=True, pady=(5, 0))
        self.report = tk.Text(
            report_frame, wrap="word", font=("Consolas", 10), height=12)
        self.report.pack(side="left", fill="both", expand=True)
        scrollbar = ttk.Scrollbar(report_frame, orient="vertical",
                                  command=self.report.yview)
        scrollbar.pack(side="right", fill="y")
        self.report.configure(yscrollcommand=scrollbar.set)
        self._append(
            "SofaBaton U1 BLE diagnostic — experimental\n"
            "1. Ensure batteries are fitted and Bluetooth is enabled on the PC.\n"
            "2. Put the U1 in app pairing mode (hold - and E until Pairing).\n"
            "3. Close the official SofaBaton phone app if it owns the session.\n"
            "4. Scan Bluetooth, select the identified U1, click Connect U1.\n"
            "5. Optional: Request Windows pairing ONLY if a normal connection fails.\n"
            "6. When connected, this window closes and the main window shows\n"
            "   green CONNECTED — READY. Reopen it to read a stored IR slot.\n"
            "7. Read stored IR (test) queries an already-learned code only.\n"
            "8. Select a device/button in the main app, then Save to selected button.\n"
            "9. Use Disconnect U1 when finished; closing this window keeps\n"
            "   the connection alive, closing the main app disconnects.\n"
            "The U1 may advertise under an unexpected name; do not connect\n"
            "to unknown devices without identifying them first.\n\n"
        )
        self.transient(parent)
        # Reopening the window must not require scanning again while connected.
        snap = self.master.u1_session.snapshot()
        if snap.connected:
            self._devices = [ble.BleEntry(
                name=snap.name, address=snap.address, rssi=None,
                advertised_services=(u1.SVC,), likely_sofabaton=True)]
            self.device_list.insert("end", f"{snap.name} — CONNECTED — {snap.address}")
            self.device_list.selection_set(0)
        self._poll_connection()

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

    def _run(self, name, task, complete):
        if self._busy:
            return
        self._busy = True
        self.scan_button.configure(state="disabled")
        self.connect_button.configure(state="disabled")
        self.disconnect_button.configure(state="disabled")
        self.inspect_button.configure(state="disabled")
        self.read_button.configure(state="disabled")
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
                    self.status_var.set(name + " failed. See report.")
                    self._append(name + " FAILED: " + error + "\n")
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
            self.status_var.set(
                f"Found {len(devices)} BLE device(s). "
                "Select the SofaBaton U1 and inspect its services if identified."
                if devices else
                "No BLE advertisements seen. Check U1 batteries, pairing mode "
                "and the PC's Bluetooth adapter.")
            self._append(f"BLE scan: {len(devices)} advertising device(s).\n")
            for d in devices:
                self._append(f"  {d.name} / {d.address} / "
                             f"{d.rssi if d.rssi is not None else '?'} dBm\n"
                             f"    Advertised UUIDs: "
                             f"{', '.join(d.advertised_services) or '(none)'}\n")
            self._append("\n")
        self._run("Scanning", lambda: ble.scan_ble(7.0), complete)

    def connect_u1(self):
        selection = self.device_list.curselection()
        if not selection or self._busy:
            return
        device = self._devices[selection[0]]
        if not (device.likely_sofabaton or any(
                uuid.lower() == u1.SVC for uuid in device.advertised_services)):
            messagebox.showwarning(
                "Identify the U1",
                "Select the identified SofaBaton U1 (for example SB...). "
                "Connecting to unknown BLE devices is disabled.",
                parent=self)
            return
        pair = bool(self.pair_var.get())
        if not messagebox.askokcancel(
            "Connect to SofaBaton U1?",
            f"Connect to {device.name} over Bluetooth?\n\n"
            + ("Windows pairing will be requested. You may need to confirm on "
               "the remote or Windows.\n\n" if pair else
               "No Windows pairing will be requested unless you enable the "
               "pairing option.\n\n")
            + "The connection will remain active in the main window until "
              "you choose Disconnect U1 or close the app. "
              "Only device-identification inquiries will be sent.",
            parent=self):
            return
        self._append(
            f"Connecting to {device.name} ({device.address}), "
            f"request Windows pairing: {pair}...\n")

        def complete(snapshot):
            self._append("U1 identity verified; session remains connected.\n")
            self.master._refresh_u1_status()
            # Return to main UI as requested; reopen the dialog to read codes.
            self.destroy()
        self._run("Connecting U1",
                  lambda: self.master.u1_session.connect(
                      device.address, device.name, pair=pair), complete)

    def disconnect_u1(self):
        if self._busy or not self.master.u1_session.snapshot().connected:
            return

        def complete(_):
            self._append("U1 disconnected from Windows.\n")
            self.status_var.set("Disconnected. Select the U1 and click Connect U1.")
        self._run("Disconnecting U1",
                  self.master.u1_session.disconnect, complete)

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
                f"Service metadata for {device.name} collected; "
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
