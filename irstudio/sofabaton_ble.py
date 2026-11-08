"""Opt-in Windows BLE discovery for experimental SofaBaton U1 research.

This module is only the scanner and metadata inspector. The experimental
stored-command reader lives in sofabaton_u1_read.py; it does NOT start IR learning.
Safe scope: scan advertisements and, after explicit user selection, connect
to enumerate GATT service/characteristic metadata only.

There are NO writes, pairing requests, notification subscriptions, value
reads, firmware/configuration operations, or automatic reconnect attempts.
The U1's app-level IR learning protocol remains to be reverse-engineered.
"""
from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass


@dataclass(frozen=True)
class BleEntry:
    name: str
    address: str
    rssi: int | None
    advertised_services: tuple[str, ...]
    likely_sofabaton: bool = False


def normalize_discoveries(discovered: dict) -> list[BleEntry]:
    """Turn Bleak's discover(return_adv=True) output into stable UI data."""
    results: list[BleEntry] = []
    for device, advertisement in discovered.values():
        name = str(advertisement.local_name or device.name or "(unnamed BLE device)")
        addr = str(device.address)
        raw_rssi = getattr(advertisement, "rssi", None)
        rssi = int(raw_rssi) if raw_rssi is not None else None
        services = tuple(str(x) for x in (advertisement.service_uuids or ()))
        # Observed U1 advertises SB<identifier> and 0xFFF0, not the word SofaBaton.
        hint = ("sofabaton" in name.casefold()
                or (name.upper().startswith("SB") and
                    any(str(uuid).lower() ==
                        "0000fff0-0000-1000-8000-00805f9b34fb"
                        for uuid in services)))
        results.append(BleEntry(name, addr, rssi, services, hint))
    results.sort(key=lambda x: (
        not x.likely_sofabaton,
        x.name.casefold(),
        -(x.rssi if x.rssi is not None else -150),
        x.address))
    return results



def auto_connect_candidate(devices: list[BleEntry]) -> BleEntry | None:
    """Identify a single SB<serial> device for user-requested auto-connect.

    Only unique SB-prefixed hexadecimal IDs (the U1's observed advertising
    form) qualify. If a UUID is advertised, it must include the observed
    U1 FFF0 service. Missing advertised UUIDs are allowed because some BLE
    adapters omit them; the connection backend must still verify FFF0/FFF1/
    FFF2 and the U1 application identity before showing green.
    If multiple candidates exist, require manual selection instead.
    """
    candidates = [
        device for device in devices
        if re.fullmatch(r"SB[0-9a-f]{6,20}", device.name.strip(), flags=re.I)
        and (not device.advertised_services or
             "0000fff0-0000-1000-8000-00805f9b34fb" in {
                 str(uuid).lower() for uuid in device.advertised_services})
    ]
    return candidates[0] if len(candidates) == 1 else None


def scan_ble(seconds: float = 7.0) -> list[BleEntry]:
    """Scan only BLE advertisements. No GATT connections are opened."""
    if not 2 <= seconds <= 20:
        raise ValueError("BLE scan duration must be 2-20 seconds.")
    try:
        from bleak import BleakScanner
    except ImportError as exc:
        raise RuntimeError("BLE library missing from the Windows build.") from exc

    async def work():
        found = await BleakScanner.discover(
            timeout=seconds, return_adv=True)
        return normalize_discoveries(found)

    # Runs on a dedicated worker thread (MTA on Windows), not Tk's thread.
    return asyncio.run(work())


def format_gatt(services) -> list[str]:
    """Render only publicly disclosed GATT structure, never its values."""
    lines = []
    for service in services:
        lines.append(f"Service {service.uuid}")
        for char in service.characteristics:
            props = ", ".join(str(x) for x in char.properties)
            lines.append(f"  Characteristic {char.uuid} [{props}]")
            for descriptor in char.descriptors:
                lines.append(f"    Descriptor {descriptor.uuid}")
    if not lines:
        lines.append("No GATT services were exposed.")
    return lines


def inspect_gatt(address: str) -> list[str]:
    """Explicitly initiated connection that enumerates metadata only.

    Even a GATT connection may interrupt the official SofaBaton app session.
    We do not pair automatically or attempt any device-specific commands.
    """
    if not address or len(address) > 128:
        raise ValueError("Choose a BLE device found by Scan first.")
    try:
        from bleak import BleakClient
    except ImportError as exc:
        raise RuntimeError("BLE library missing from the Windows build.") from exc

    async def work():
        async with BleakClient(address, pair=False, timeout=12.0) as client:
            if not client.is_connected:
                raise RuntimeError("The selected BLE device did not connect.")
            # Service discovery is automatic; no characteristic VALUE reads.
            return format_gatt(client.services)

    return asyncio.run(work())
