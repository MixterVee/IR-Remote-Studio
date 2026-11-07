"""Prevent accidental broadening of IR-learning model support."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKER = ROOT / "irstudio" / "Xflyee_IR_Worker.ps1"
MODELS = ROOT / "irstudio" / "harmony_models.json"


class HarmonyModelTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.catalogue = json.loads(MODELS.read_text(encoding="utf-8"))

    def test_known_supported_models_and_350_regression(self):
        supported = set(self.catalogue["working"])
        for model in (
            "Harmony 200", "Harmony 300", "Harmony 350", "Harmony 600",
            "Harmony 650", "Harmony 665", "Harmony 720", "Harmony 785",
            "Harmony 880", "Harmony One", "Harmony 900"
        ):
            self.assertIn(model, supported)
        self.assertNotIn("Harmony 700", supported)

    def test_experimental_700(self):
        self.assertEqual(set(self.catalogue["experimental"]),
                         {"Harmony 700", "Harmony 700i"})

    def test_explicit_exclusions(self):
        enabled = set(self.catalogue["working"]) | set(self.catalogue["experimental"])
        for model in self.catalogue["excluded_examples"]:
            self.assertNotIn(model, enabled, model)
        self.assertEqual(len(enabled), len(self.catalogue["working"]) + len(self.catalogue["experimental"]))

    def test_worker_checks_catalogue_before_learning(self):
        source = WORKER.read_text(encoding="utf-8-sig")
        self.assertIn("harmony_models.json", source)
        self.assertIn("ConvertFrom-Json", source)
        self.assertIn("$model -notin @($catalog.working)", source)
        self.assertIn("$model -notin @($catalog.experimental)", source)
        self.assertLess(source.index("harmony_models.json"),
                        source.index("learn_from_remote([ref]$carrier"))
        self.assertNotIn("Xflyee button", source)
        self.assertIn("press the original remote button", source)


if __name__ == "__main__":
    unittest.main()
