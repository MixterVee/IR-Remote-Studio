"""SofaBaton U1 BLE research window, intentionally separate from IR capture."""
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
        self.status_var = tk.StringVar(
            value="Scan first. Only Bluetooth metadata is inspected; no IR commands are sent.")
        self.protocol("WM_DELETE_WINDOW", self.destroy)

        outer = ttk.Frame(self, padding=14)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="SofaBaton U1 — Bluetooth Diagnostics",
                  font=("Segoe UI Semibold", 15)).pack(anchor="w")
        ttk.Label(
            outer,
            text=("Research mode only: BLE discovery and GATT metadata. "
                  "Discovery is read-only. Optional stored-code read sends only "
                  "verified inquiry commands; no new learning or programming."),
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
            "4. Scan Bluetooth. Choose the U1 by its advertised name.\n"
            "5. Optional: Inspect selected (read-only) to list GATT UUIDs.\n"
            "6. Optional: select a known occupied U1 slot, then choose\n"
            "   Read stored IR (test). This sends status + read inquiries,\n"
            "   NOT a new learning or programming command.\n"
            "7. Select a device/button in the main app, then Save to selected button.\n"
            "The U1 may advertise under an unexpected name; do not connect\n"
            "to unknown devices without identifying them first.\n\n"
        )
        self.transient(parent)

    def _append(self, content: str):
        if self.winfo_exists():
            self.report.insert("end", content)
            self.report.see("end")

    def _select_device(self, event=None):
        selected = bool(self.device_list.curselection()) and not self._busy
        self.inspect_button.configure(state="normal" if selected else "disabled")
        self.read_button.configure(state="normal" if selected else "disabled")
        self.save_capture_button.configure(
            state="normal" if self._capture is not None and not self._busy
            else "disabled")

    def _run(self, name, task, complete):
        if self._busy:
            return
        self._busy = True
        self.scan_button.configure(state="disabled")
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

    def read_ir(self):
        """Opt-in query of IR already stored in the selected U1 slot."""
        selected = self.device_list.curselection()
        if not selected or self._busy:
            return
        device = self._devices[selected[0]]
        slot = int(self.slot_var.get())
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
            "Experimental: sends only observed device-info (0x01) and "
            "read-code (0x0C) requests. No new learning, profile updates "
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
                  lambda: u1.read_saved_ir(device.address, slot), complete)

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
