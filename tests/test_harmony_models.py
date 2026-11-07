"""Check that the capture worker recognizes both supported Logitech Harmony models."""
import re
from pathlib import Path
import unittest

WORKER = Path(__file__).resolve().parents[1] / "irstudio" / "Xflyee_IR_Worker.ps1"


class HarmonyModelTests(unittest.TestCase):
    def test_harmony_300_and_350_are_accepted(self):
        source = WORKER.read_text(encoding="utf-8-sig")
        m = re.search(r"\$model -notmatch '([^']+)'", source)
        self.assertIsNotNone(m, "Worker must guard supported models")
        pattern = m.group(1)
        for name in ("Logitech Harmony 300", "Logitech Harmony 350"):
            self.assertRegex(name, pattern)
        for name in ("Logitech Harmony 650", "Logitech Harmony One", "Other Device"):
            self.assertNotRegex(name, pattern)

    def test_capture_timeout_message_is_generic(self):
        source = WORKER.read_text(encoding="utf-8-sig")
        self.assertNotIn("Xflyee button", source)
        self.assertIn("press the original remote button", source)


if __name__ == "__main__":
    unittest.main()
