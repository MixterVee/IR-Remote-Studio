"""Persistent, explicitly connected SofaBaton U1 BLE session.

The optional Windows OS pairing flag is OFF by default: this U1 has
already been proven to communicate with Windows without a pair request.
Normal operations use only observed device-identity (0x01) and stored-code
inquiry (0x0C). Main-window user-initiated U1 learning additionally sends the verified
0x04 learn start and 0x05 finish after automatically reading and saving
a slot-8 backup to local disk.
It may overwrite the U1's slot 8 and cannot restore that programming.
No unverified profile or firmware configuration commands are sent.

A dedicated asyncio thread owns the BleakClient, notification subscription,
and received-packet queue for the entire connection lifetime. UI threads
only invoke synchronous entry points and read immutable snapshots.
"""
from __future__ import annotations

import asyncio
from collections import deque
from concurrent.futures import Future
from dataclasses import dataclass
import threading
import time

from irstudio import sofabaton_u1_read as protocol


@dataclass(frozen=True)
class ConnectionSnapshot:
    state: str
    name: str = ""
    address: str = ""
    detail: str = ""

    @property
    def connected(self) -> bool:
        return self.state == "CONNECTED"


class U1Session:
    def __init__(self, client_factory=None):
        self._client_factory = client_factory
        self._lock = threading.Lock()
        self._snapshot = ConnectionSnapshot("NOT CONNECTED",
                                            detail="Scan Bluetooth, then Connect U1.")
        self._loop = None
        self._thread = None
        self._started = threading.Event()
        self._client = None
        self._rx = None
        self._busy = None
        self._disconnecting = False
        # Keep BLE receive diagnostics separate from the protocol inbox.
        # Store only timestamps, command headers and lengths, never IR timings.
        self._rx_diag_lock = threading.Lock()
        self._rx_total = 0
        self._rx_recent = deque(maxlen=128)

    def snapshot(self) -> ConnectionSnapshot:
        with self._lock:
            return self._snapshot

    def _state(self, state: str, name="", address="", detail=""):
        with self._lock:
            self._snapshot = ConnectionSnapshot(state, name, address, detail)

    def _loop_main(self):
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self._loop = loop
        self._busy = asyncio.Lock()
        self._started.set()
        try:
            loop.run_forever()
        finally:
            pending = asyncio.all_tasks(loop)
            for task in pending:
                task.cancel()
            if pending:
                loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            loop.close()

    def _submit(self, coro) -> Future:
        if self._thread is None:
            self._started.clear()
            self._thread = threading.Thread(target=self._loop_main, daemon=True,
                                            name="SofaBaton-U1-Bluetooth")
            self._thread.start()
        if not self._started.wait(timeout=5):
            coro.close()
            raise RuntimeError("Bluetooth background thread did not start.")
        return asyncio.run_coroutine_threadsafe(coro, self._loop)

    def _receive(self, _sender, data):
        # Bleak's notification callback may arrive on a backend-specific thread.
        raw = bytes(data)
        event = {
            "at": time.strftime("%H:%M:%S", time.localtime()),
            "bytes": len(raw),
            "command": f"0x{raw[3]:02X}" if len(raw) >= 4
                       and raw[:2] == protocol.PREFIX else "unframed",
        }
        with self._rx_diag_lock:
            self._rx_total += 1
            self._rx_recent.append((self._rx_total, event))
        loop = self._loop
        inbox = self._rx
        if loop and not loop.is_closed() and inbox is not None:
            loop.call_soon_threadsafe(inbox.put_nowait, raw)

    def _rx_report(self, since: int):
        """Count the ACTUAL FFF1 callback notifications, not parsed packets."""
        with self._rx_diag_lock:
            total = self._rx_total
            recent = [item for n, item in self._rx_recent if n > since]
        return total - since, recent

    def _rx_counter(self):
        with self._rx_diag_lock:
            return self._rx_total

    def _disconnected(self, client):
        if self._client is client and not self._disconnecting:
            previous = self.snapshot()
            self._client = None
            self._rx = None
            self._state("LOST", previous.name, previous.address,
                        "Bluetooth connection was lost. Check U1 batteries, "
                        "then reconnect from SofaBaton U1 Bluetooth.")

    async def _connect_async(self, address, name, pair):
        async with self._busy:
            if self._client is not None and self._client.is_connected:
                current = self.snapshot()
                if current.address == address:
                    return current
                raise RuntimeError("Another U1 is already connected. Disconnect it first.")
            if not address or len(address) > 128:
                raise ValueError("Select an identified SofaBaton U1 first.")
            if self._client_factory is None:
                try:
                    from bleak import BleakClient
                except ImportError as exc:
                    raise RuntimeError("Bluetooth library is missing.") from exc
                factory = BleakClient
            else:
                factory = self._client_factory
            self._state("CONNECTING", name, address,
                        "Checking U1 Bluetooth services and device identity...")
            client = factory(address, pair=pair, timeout=25.0,
                             disconnected_callback=self._disconnected)
            try:
                await client.connect()
                if not client.is_connected:
                    raise RuntimeError("Windows did not establish a Bluetooth connection.")
                if not protocol.is_u1_service(client.services):
                    raise RuntimeError(
                        "This device does not expose the expected U1 FFF0/FFF1/FFF2 "
                        "Bluetooth service. No U1 command was sent.")
                self._client = client
                self._rx = asyncio.Queue()
                await client.start_notify(protocol.NOTIFY, self._receive)
                # Verify the device responds, not just that Windows sees its radio.
                await protocol.verify_identity(client, self._rx)
                if not client.is_connected:
                    raise RuntimeError("The U1 disconnected during identity verification.")
                self._state("CONNECTED", name, address,
                            "Bluetooth link and U1 identity verified. Ready to read stored IR.")
                return self.snapshot()
            except Exception as exc:
                self._disconnecting = True
                try:
                    if client.is_connected:
                        try:
                            await client.stop_notify(protocol.NOTIFY)
                        except Exception:
                            pass
                        await client.disconnect()
                finally:
                    self._disconnecting = False
                    self._client = None
                    self._rx = None
                    self._state("FAILED", name, address,
                                f"Connection failed: {exc}. Try reconnecting, "
                                "checking the batteries or another Bluetooth adapter.")
                raise

    def connect(self, address: str, name: str, *, pair=False) -> ConnectionSnapshot:
        """User-initiated Connect/Pair; invoke only from a worker thread."""
        return self._submit(self._connect_async(address, name, pair)).result(timeout=55)

    async def _read_async(self, slot):
        async with self._busy:
            client, inbox = self._client, self._rx
            if not client or not client.is_connected or inbox is None:
                raise RuntimeError("U1 is not connected. Click Connect U1 first.")
            return await protocol.read_connected_ir(client, inbox, slot)

    def read_saved_ir(self, slot: int):
        """Use the already-connected BLE session; do not disconnect after reading."""
        return self._submit(self._read_async(slot)).result(timeout=25)

    async def _learn_async(self, backup_folder, on_ready=None):
        """Read/back up slot 8 BEFORE sending the experimental learn command."""
        async with self._busy:
            client, inbox = self._client, self._rx
            if not client or not client.is_connected or inbox is None:
                raise RuntimeError("Connect the SofaBaton U1 before learning.")
            from pathlib import Path
            import datetime
            import json
            from irstudio import core
            # Do not arm a learner unless we can verify and save the previous
            # contents of this exact slot. We cannot program a backup into U1.
            notifications_before_read = self._rx_counter()
            previous = await protocol.read_connected_ir(client, inbox, 8)
            preflight_notifications, _ = self._rx_report(notifications_before_read)
            folder = Path(backup_folder) / "U1-Learn-Backups"
            folder.mkdir(parents=True, exist_ok=True)
            stamp = datetime.datetime.now(datetime.timezone.utc).strftime(
                "%Y%m%d_%H%M%S_%f")
            path = folder / f"SofaBaton-U1-slot8-before-learn-{stamp}.json"
            backup = {
                "key": "U1 slot 8 before experimental learn",
                "carrier_hz": previous.carrier_hz,
                "timings_us": previous.timings_us,
                "original_slot": 8,
                "note": "LOCAL IRPlus-compatible capture backup. Cannot automatically "
                        "restore SofaBaton U1 programming.",
            }
            # Exclusive create avoids overwriting earlier backups.
            with path.open("x", encoding="utf-8") as stream:
                json.dump(backup, stream, indent=2)
                stream.write("\n")
                stream.flush()
                import os
                os.fsync(stream.fileno())
            # Test the just-written backup is importable before arming U1.
            core.import_capture_json(path)
            notifications_before_learn = self._rx_counter()
            try:
                learned = await protocol.experimental_learn_connected_ir(
                    client, inbox, slot=8, timeout=35, on_ready=on_ready)
            except Exception as exc:
                count, events = self._rx_report(notifications_before_learn)
                diagnostic_path = folder / f"U1-Learn-Diagnostic-{stamp}.txt"
                diagnostics = [
                    "IR Remote Studio v0.3.6 — U1 receive diagnostics",
                    "No remote configuration/firmware changes requested.",
                    f"Backup (preserves IR waveform ONLY): {path}",
                    f"U1 connected at failure: {bool(client.is_connected)}",
                    f"BLE notifications during previous-slot read: {preflight_notifications}",
                    f"BLE notifications after learning start: {count}",
                    f"Incoming control frame summaries (newest 128 at most):",
                    *[f"  {event['at']}  {event['command']}  {event['bytes']} bytes"
                      for event in events],
                    f"Protocol error: {exc}",
                    "If BLE notifications after learning start are zero,",
                    "the app received no FFF1 notifications despite entering Learning mode.",
                    "No IR signal bytes or device-identifying BLE addresses are included.",
                ]
                # Diagnostic recording is best effort: a logging failure must
                # never hide the original Bluetooth learning failure.
                try:
                    with diagnostic_path.open("x", encoding="utf-8") as stream:
                        stream.write("\n".join(diagnostics) + "\n")
                    note = f"Diagnostics: {diagnostic_path}"
                except OSError:
                    note = "Could not write the diagnostic report."
                raise RuntimeError(
                    f"Learning failed: {exc} "
                    f"BLE preflight notifications: {preflight_notifications}; "
                    f"learning notifications: {count}. {note} "
                    f"Previous U1 IR backed up to {path}; this cannot "
                    "restore the U1's own programmed slot.") from exc
            return learned, str(path)

    def learn_ir_experimental(self, backup_folder, on_ready=None):
        """User-initiated U1 live learning with mandatory local preflight backup."""
        return self._submit(self._learn_async(
            backup_folder, on_ready=on_ready)).result(timeout=65)

    async def _disconnect_async(self):
        async with self._busy:
            client = self._client
            self._disconnecting = True
            try:
                if client is not None:
                    try:
                        if client.is_connected:
                            try:
                                await client.stop_notify(protocol.NOTIFY)
                            except Exception:
                                pass
                            await client.disconnect()
                    finally:
                        self._client = None
                        self._rx = None
            finally:
                self._disconnecting = False
                self._state("NOT CONNECTED",
                            detail="Disconnected. Select the U1 and click Connect U1.")

    def disconnect(self):
        """Explicit user disconnect; safe to call repeatedly."""
        if self._thread is not None:
            self._submit(self._disconnect_async()).result(timeout=12)
        else:
            self._state("NOT CONNECTED", detail="Disconnected.")

    def shutdown(self):
        """Release Bluetooth before the main Tk window exits."""
        if self._thread is None:
            return
        try:
            self.disconnect()
        finally:
            loop = self._loop
            if loop and not loop.is_closed():
                loop.call_soon_threadsafe(loop.stop)
            if self._thread is not threading.current_thread():
                self._thread.join(timeout=3)
