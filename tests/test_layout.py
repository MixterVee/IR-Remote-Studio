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

    def test_irplus_wrap_before_wide_button(self):
        """Regression: width 6 then 12 must export button, space, button."""
        import xml.etree.ElementTree as ET
        d = core.new_device("Test")
        for label, width in (("Test 2", 6), ("Test", 12)):
            b = core.new_button(label)
            b["span"] = width
            b["pronto_hex"] = "0000 006D 0002 0000 0157 0057 0016 4DFA"
            d["buttons"].append(b)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "test.irplus"
            self.assertEqual(core.export_irplus(d, path), 2)
            elems = list(ET.parse(path).getroot().find("device"))
            self.assertEqual([(e.tag, e.get("span") or e.get("multiple")) for e in elems],
                             [("button", "6"), ("space", "6"), ("button", "12")])
            self.assertEqual([b["label"] for b in core.import_irplus(path)[0]["buttons"]],
                             ["Test 2", "Test"])

    def test_irplus_partial_rows_sum_to_twelve(self):
        import xml.etree.ElementTree as ET
        d = core.new_device("Other")
        for width in (3, 4, 6, 2, 12, 6):
            b = core.new_button(str(width))
            b["span"] = width
            b["pronto_hex"] = "0000 006D 0002 0000 0157 0057 0016 4DFA"
            d["buttons"].append(b)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "out.irplus"
            core.export_irplus(d, path)
            occupied = 0
            rows = 0
            elems = list(ET.parse(path).getroot().find("device"))
            for e in elems:
                width = int(e.get("span") if e.tag == "button" else e.get("multiple"))
                self.assertLessEqual(occupied + width, 12)
                occupied += width
                if occupied == 12:
                    occupied = 0
                    rows += 1
            self.assertEqual((occupied, rows), (0, 4))
            self.assertEqual(sum(e.tag == "button" for e in elems), 6)

    def test_irplus_trailing_padding(self):
        import xml.etree.ElementTree as ET
        d = core.new_device("Test")
        b = core.new_button("Power")
        b["span"] = 6
        b["pronto_hex"] = "0000 006D 0002 0000 0157 0057 0016 4DFA"
        d["buttons"].append(b)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "out.irplus"
            core.export_irplus(d, path)
            elems = list(ET.parse(path).getroot().find("device"))
            self.assertEqual([e.tag for e in elems], ["button", "space"])
            self.assertEqual(elems[-1].attrib, {"multiple": "6"})

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
