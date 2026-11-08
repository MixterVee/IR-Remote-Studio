"""SofaBaton U1 BLE research window, intentionally separate from IR capture."""
from __future__ import annotations

import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from irstudio import sofabaton_ble as ble


class SofaBatonBleDialog(tk.Toplevel):
    def __init__(self, parent):
        super().__init__(parent)
        self.title("SofaBaton U1 — Bluetooth Diagnostics")
        self.minsize(710, 465)
        self.geometry("830x600")
        self._busy = False
        self._devices: list[ble.BleEntry] = []
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
                  "No IR learning, command programming, writes, pairing, "
                  "or firmware changes."),
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

        ttk.Label(outer, text="Read-only diagnostic report:").pack(anchor="w")
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
            "The U1 may advertise under an unexpected name; do not connect\n"
            "to unknown devices without identifying them first.\n\n"
        )
        self.transient(parent)

    def _append(self, content: str):
        if self.winfo_exists():
            self.report.insert("end", content)
            self.report.see("end")

    def _select_device(self, event=None):
        self.inspect_button.configure(
            state="normal" if not self._busy and self.device_list.curselection()
            else "disabled")

    def _run(self, name, task, complete):
        if self._busy:
            return
        self._busy = True
        self.scan_button.configure(state="disabled")
        self.inspect_button.configure(state="disabled")
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
