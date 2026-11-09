"""Guarded SofaBaton U1 live learning regression tests (NO physical BLE).

Checks the exact frames observed in the official app trace, reject cases,
and verifies the saved local backup precedes the first potentially
state-changing 0x04 command. These do NOT prove real U1 safety.
"""
import asyncio
import json
import math
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import AsyncMock, patch

from irstudio import core
from irstudio import sofabaton_u1_read as u1
from irstudio.sofabaton_u1_session import U1Session


def ir_frames(slot=8):
    values = [3996, 3999] + [469, 2000] * 51
    size = len(values) * 4
    total = math.ceil((20 + size + 1) / 12)
    blob = (b"\x01\x00" + bytes((total, slot)) + bytes(8)
            + size.to_bytes(4, "big") + (38095).to_bytes(4, "big")
            + b"".join(i.to_bytes(4, "big") for i in values))
    blob += bytes((sum(blob) & 0xff,))
    return [u1.encode_frame(u1.IR_DATA, b"\x01\x00" + bytes((i+1,))
                               + blob[i*12:i*12+12])
            for i in range(total)]


class MockLearnClient:
    def __init__(self, queue, *, missing_ready=False, corrupt=False):
        self.is_connected = True
        self.queue = queue
        self.missing_ready = missing_ready
        self.corrupt = corrupt
        self.writes = []

    async def write_gatt_char(self, uuid, data, response=False):
        self.writes.append(bytes(data))
        cmd, payload = u1.decode_frame(data)
        if cmd == u1.LEARN_START:
            if not self.missing_ready:
                self.queue.put_nowait(u1.encode_frame(u1.LEARN_READY))
            self.queue.put_nowait(
                u1.encode_frame(u1.LEARN_CAPTURED, b"\x00"))
            for frame in ir_frames():
                if self.corrupt:
                    frame = frame[:-1] + bytes((frame[-1] ^ 0x01,))
                    self.queue.put_nowait(frame)
                    break
                self.queue.put_nowait(frame)


class LiveLearningProtocolTests(unittest.TestCase):
    def test_exact_observed_start_stop_and_complete_waveform(self):
        async def run():
            q = asyncio.Queue()
            fake = MockLearnClient(q)
            signal = await u1.experimental_learn_connected_ir(
                fake, q, slot=8)
            return fake, signal
        client, result = asyncio.run(run())
        self.assertEqual(result.slot, 8)
        self.assertEqual(result.carrier_hz, 38095)
        self.assertEqual(len(result.timings_us), 104)
        self.assertEqual([v.hex() for v in client.writes], [
            "a55a09040818020000000000002e",
            "a55a01050106",
        ])
        self.assertNotIn(0x07, [u1.decode_frame(f)[0]
                                for f in client.writes])
        self.assertNotIn(0x08, [u1.decode_frame(f)[0]
                                for f in client.writes])

    def test_only_recorded_slot_eight_can_be_started(self):
        async def run():
            q = asyncio.Queue()
            client = MockLearnClient(q)
            with self.assertRaisesRegex(ValueError, "slot 8"):
                await u1.experimental_learn_connected_ir(
                    client, q, slot=7)
            return client
        client = asyncio.run(run())
        self.assertEqual(client.writes, [])

    def test_u1_screen_learning_without_ready_notification_can_capture(self):
        # Hardware may show Learning even if it never notifies 0x04 ready.
        # We still require explicit 0x06 success and verified IR fragments.
        async def run():
            q = asyncio.Queue()
            fake = MockLearnClient(q, missing_ready=True)
            prompted = []
            result = await u1.experimental_learn_connected_ir(
                fake, q, on_ready=lambda: prompted.append("start sent"))
            return fake, result, prompted
        client, result, prompted = asyncio.run(run())
        self.assertEqual(prompted, ["start sent"])
        self.assertEqual(result.slot, 8)
        self.assertEqual(result.carrier_hz, 38095)
        self.assertEqual(len(result.timings_us), 104)
        self.assertEqual([u1.decode_frame(f)[0] for f in client.writes],
                         [u1.LEARN_START, u1.LEARN_FINISH])

    def test_invalid_packet_rejects_capture_and_sends_finish(self):
        async def run():
            q = asyncio.Queue()
            fake = MockLearnClient(q, corrupt=True)
            with self.assertRaisesRegex(u1.U1ReadError, "checksum"):
                await u1.experimental_learn_connected_ir(fake, q)
            return fake
        client = asyncio.run(run())
        self.assertEqual([u1.decode_frame(f)[0] for f in client.writes],
                         [u1.LEARN_START, u1.LEARN_FINISH])


class OfficialAppPreparationTests(unittest.TestCase):
    """Check exact recorded pre-learning packets, not physical U1 behavior."""

    def test_replays_two_acknowledged_stages_in_order(self):
        class Fake:
            is_connected = True

            def __init__(self, q):
                self.q = q
                self.writes = []

            async def write_gatt_char(self, uuid, data, response=False):
                self.writes.append(bytes(data))
                if data == u1.LEARN_PREP_ENTER or data == u1.LEARN_PREP_FRAGMENTS[-1]:
                    self.q.put_nowait(u1.encode_frame(u1.LEARN_ACK, b"\x00"))

        async def run():
            q = asyncio.Queue()
            client = Fake(q)
            await u1.prepare_connected_u1_learning(client, q)
            return client.writes
        packets = asyncio.run(run())
        self.assertEqual([packet.hex() for packet in packets], [
            "a55a001413",
            "a55a0f0701000101000300080108050000001748",
            "a55a0f07010002a55a0100000072656d6f7465a4",
            "a55a050701000300bdcc",
        ])
        self.assertNotIn(u1.LEARN_START, [u1.decode_frame(p)[0] for p in packets])
        self.assertNotIn(0x08, [u1.decode_frame(p)[0] for p in packets])

    def test_rejects_nonzero_ack_without_arming_ir(self):
        class Reject:
            is_connected = True

            def __init__(self, q):
                self.q = q
                self.writes = []

            async def write_gatt_char(self, uuid, data, response=False):
                self.writes.append(bytes(data))
                self.q.put_nowait(u1.encode_frame(u1.LEARN_ACK, b"\x01"))

        async def run():
            q = asyncio.Queue()
            client = Reject(q)
            with self.assertRaisesRegex(u1.U1ReadError, "non-success"):
                await u1.prepare_connected_u1_learning(client, q)
            return client.writes
        writes = asyncio.run(run())
        self.assertEqual(writes, [u1.LEARN_PREP_ENTER])


class MockConnected:
    is_connected = True


class PreflightBackupTests(unittest.TestCase):
    def setUp(self):
        # Isolate the on-device preparation from filesystem/backup tests.
        # Real preparation packets are tested separately above.
        patcher = patch(
            "irstudio.sofabaton_u1_session.protocol.prepare_connected_u1_learning",
            new_callable=AsyncMock)
        self.preparation = patcher.start()
        self.addCleanup(patcher.stop)

    async def _try(self, tmpdir):
        session = U1Session()
        session._busy = asyncio.Lock()
        session._client = MockConnected()
        session._rx = asyncio.Queue()
        return await session._learn_async(tmpdir)

    def test_backup_is_verified_before_first_learning_write(self):
        previous = u1.IrRead(
            8, 38095, [3996, 3999] + [469, 2000] * 51, 37)
        new = u1.IrRead(
            8, 38095, [4000, 3990] + [560, 1700] * 51, 37)
        async def old_read(*args):
            return previous
        async def new_learn(*args, **kwargs):
            backups = list(Path(root).glob(
                "U1-Learn-Backups/SofaBaton-U1-slot8-before-learn-*.json"))
            self.assertEqual(len(backups), 1)
            name, saved = core.import_capture_json(backups[0])
            self.assertEqual(saved["carrier_hz"], 38095)
            self.assertEqual(saved["timings_us"], previous.timings_us)
            return new
        with TemporaryDirectory() as root, \
             patch("irstudio.sofabaton_u1_session.protocol.read_connected_ir",
                   side_effect=old_read), \
             patch("irstudio.sofabaton_u1_session.protocol.experimental_learn_connected_ir",
                   side_effect=new_learn) as learn:
            result, backup = asyncio.run(self._try(root))
            self.assertEqual(result.timings_us, new.timings_us)
            self.assertTrue(Path(backup).exists())
            learn.assert_awaited_once()
            data = json.loads(Path(backup).read_text(encoding="utf-8"))
            self.assertIn("cannot automatically", data["note"].lower())

    def test_no_live_ble_reply_generates_raw_callback_diagnostic(self):
        # The preflight read callback can succeed while the U1 sends no
        # response to the subsequent learn request. Do not invent an ACK.
        previous = u1.IrRead(
            8, 38095, [3996, 3999] + [469, 2000] * 51, 37)

        async def scenario(root):
            session = U1Session()
            session._busy = asyncio.Lock()
            session._client = MockConnected()
            session._rx = asyncio.Queue()

            async def old_read(*args):
                session._receive(None, u1.encode_frame(u1.LEARN_ACK))
                return previous

            async def no_reply(*args, **kwargs):
                raise u1.U1ReadError("BLE replies: none")

            with patch("irstudio.sofabaton_u1_session.protocol.read_connected_ir",
                       side_effect=old_read), \
                 patch("irstudio.sofabaton_u1_session.protocol.experimental_learn_connected_ir",
                       side_effect=no_reply):
                with self.assertRaisesRegex(
                    RuntimeError, "learning notifications: 0"
                ) as caught:
                    await session._learn_async(root)
            return str(caught.exception)

        with TemporaryDirectory() as root:
            msg = asyncio.run(scenario(root))
            files = list(Path(root).glob(
                "U1-Learn-Backups/U1-Learn-Diagnostic-*.txt"))
            self.assertEqual(len(files), 1)
            report = files[0].read_text(encoding="utf-8")
            self.assertIn("BLE notifications during previous-slot read: 1",
                          report)
            self.assertIn("BLE notifications after learning start: 0",
                          report)
            self.assertIn("U1 connected at failure: True", report)
            self.assertIn("0x03", report)
            self.assertIn("Diagnostics:", msg)
            self.assertIn("BLE replies: none", report)

    def test_failed_preflight_has_no_learning_write(self):
        async def failed_read(*args):
            raise u1.U1ReadError("unreadable slot 8")
        with TemporaryDirectory() as root, \
             patch("irstudio.sofabaton_u1_session.protocol.read_connected_ir",
                   side_effect=failed_read), \
             patch("irstudio.sofabaton_u1_session.protocol.experimental_learn_connected_ir",
                   new_callable=AsyncMock) as learn:
            with self.assertRaisesRegex(u1.U1ReadError, "unreadable"):
                asyncio.run(self._try(root))
            learn.assert_not_awaited()

    def test_failed_backup_disk_write_never_starts_learning(self):
        previous = u1.IrRead(8, 38095,
                             [3996, 3999] + [469, 2000] * 51, 37)
        async def old_read(*args):
            return previous
        with TemporaryDirectory() as root, \
             patch("irstudio.sofabaton_u1_session.protocol.read_connected_ir",
                   side_effect=old_read), \
             patch("irstudio.sofabaton_u1_session.protocol.experimental_learn_connected_ir",
                   new_callable=AsyncMock) as learn, \
             patch("pathlib.Path.open", side_effect=OSError("Disk full")):
            with self.assertRaisesRegex(OSError, "Disk full"):
                asyncio.run(self._try(root))
            learn.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
