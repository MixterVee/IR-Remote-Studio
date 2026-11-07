"""Roundtrip layout widths and library recovery validation."""
from pathlib import Path
import tempfile
import unittest
from irstudio import core


class LayoutTests(unittest.TestCase):
    def test_device_defaults(self):
        d = core.new_device("Clock")
        b = core.new_button("SET")
        self.assertEqual(d["columns"], 12)
        self.assertEqual(b["span"], 6)

    def test_irplus_button_width_roundtrip(self):
        d = core.new_device("Clock")
        for width in (3, 4, 6, 12):
            b = core.new_button(f"Width {width}")
            b["span"] = width
            b["pronto_hex"] = "0000 006D 0002 0000 0157 0057 0016 4DFA"
            d["buttons"].append(b)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "test.irplus"
            count = core.export_irplus(d, path)
            self.assertEqual(count, 4)
            imported = core.import_irplus(path)[0]
            self.assertEqual([b["span"] for b in imported["buttons"]], [3, 4, 6, 12])
            self.assertIn('span="12"', path.read_text(encoding="utf-8"))

    def test_backup_restore_validation(self):
        lib = core.default_library()
        d = core.new_device("Clock")
        d["buttons"].append(core.new_button("SET"))
        lib["devices"].append(d)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "backup.json"
            core.save_library(path, lib)
            restored = core.load_library(path)
            self.assertEqual(restored["devices"][0]["buttons"][0]["label"], "SET")

    def test_invalid_span_is_rejected(self):
        with self.assertRaises(ValueError):
            core.button_span({"span": 0})
        with self.assertRaises(ValueError):
            core.button_span({"span": "too big"})


if __name__ == "__main__":
    unittest.main()
