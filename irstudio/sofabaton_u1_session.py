"""Persistent, explicitly connected SofaBaton U1 BLE session.

The optional Windows OS pairing flag is OFF by default: this U1 has
already been proven to communicate with Windows without a pair request.
Only the observed device-identity (0x01) and stored-code inquiry (0x0C)
messages can be sent. This class never sends programming or learn commands.

A dedicated asyncio thread owns the BleakClient, notification subscription,
and received-packet queue for the entire connection lifetime. UI threads
only invoke synchronous entry points and read immutable snapshots.
"""
from __future__ import annotations

import asyncio
from concurrent.futures import Future
from dataclasses import dataclass
import threading

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
        loop = self._loop
        inbox = self._rx
        if loop and not loop.is_closed() and inbox is not None:
            loop.call_soon_threadsafe(inbox.put_nowait, bytes(data))

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
