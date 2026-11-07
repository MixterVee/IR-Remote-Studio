"""Preview and exported IR layouts must agree, even with unlearned buttons."""
import unittest
from irstudio import core


PRONTO = "0000 006D 0002 0000 0157 0057 0016 4DFA"


class ExportPreviewConsistency(unittest.TestCase):
    def test_irplus_preview_rows_ignore_unlearned(self):
        d = core.new_device("Test device")
        for label, width, learned in [
            ("Power", 6, True),
            ("Unused placeholder", 6, False),
            ("Volume Up", 12, True),
            ("Volume Down", 4, True),
            ("Mute", 4, True),
            ("Input", 6, True),
        ]:
            b = core.new_button(label)
            b["span"] = width
            if learned:
                b["pronto_hex"] = PRONTO
            d["buttons"].append(b)
        self.assertEqual([[b["label"] for b in row] for row in core.layout_rows(d)],
                         [["Power"], ["Volume Up"], ["Volume Down", "Mute"],
                          ["Input"]])

    def test_capture_status_categorical(self):
        missing = core.new_button("Missing")
        self.assertEqual(core.capture_status(missing), "Not learned")
        imported = core.new_button("Import")
        imported["pronto_hex"] = PRONTO
        self.assertEqual(core.capture_status(imported), "Imported")
        sample = core.new_button("Capture")
        sample["captures"] = [{"carrier_hz": 38136, "timings_us": [100, 200]*10}]
        self.assertEqual(core.capture_status(sample), "Captured once")
        sample["captures"].append(dict(sample["captures"][0]))
        self.assertEqual(core.capture_status(sample), "Verified")
        sample["captures"][-1]["timings_us"] = [200, 250]*10
        self.assertEqual(core.capture_status(sample), "Mismatch")


if __name__ == "__main__":
    unittest.main()
