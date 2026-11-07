"""Packaged visual identity must be readable in source mode."""
import unittest
from irstudio.branding import brand_image_path


class BrandingTests(unittest.TestCase):
    def test_transparent_logo_asset_exists(self):
        p = brand_image_path()
        self.assertTrue(p.exists(), str(p))
        self.assertGreater(p.stat().st_size, 1000)
        self.assertEqual(p.read_bytes()[:8], b"\\x89PNG\\r\\n\\x1a\\n".decode("unicode_escape").encode("latin1"))


if __name__ == "__main__":
    unittest.main()
