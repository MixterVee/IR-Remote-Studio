import json
import tempfile
import unittest
from pathlib import Path

from irstudio import core

FIXTURES = Path(__file__).parent.parent / 'examples'

class IRCoreTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.first = json.loads((FIXTURES/'SET_154732.json').read_text(encoding='utf-8-sig'))
        cls.second = json.loads((FIXTURES/'SET_154932.json').read_text(encoding='utf-8-sig'))
        cls.repeat = json.loads((FIXTURES/'SET_repeat_only.json').read_text(encoding='utf-8-sig'))

    def test_verified_nec_decode(self):
        for record in (self.first, self.second):
            code = core.decode_nec(record['timings_us'])
            self.assertEqual(code['bytes'], '06 AB 01 FE')
            self.assertEqual(code['address'], '0xAB06')
            self.assertEqual(code['command'], '0x01')

    def test_both_good_captures_match(self):
        c1 = core.make_capture(self.first['carrier_hz'], self.first['timings_us'])
        c2 = core.make_capture(self.second['carrier_hz'], self.second['timings_us'])
        b = core.new_button('SET')
        b['captures'] = [c1, c2]
        self.assertEqual(core.match_count(b), (2,2))

    def test_repeat_only_rejected(self):
        self.assertFalse(core.assess_capture(self.repeat['carrier_hz'], self.repeat['timings_us'])['valid'])
        with self.assertRaises(ValueError):
            core.make_capture(self.repeat['carrier_hz'], self.repeat['timings_us'])

    def test_export_irplus_roundtrip(self):
        d = core.new_device('Wall Clock', 'Xflyee', '099')
        b = core.new_button('SET')
        b['captures'].append(core.make_capture(self.first['carrier_hz'], self.first['timings_us']))
        d['buttons'].append(b)
        with tempfile.TemporaryDirectory() as p:
            target = Path(p)/'Xflyee.irplus'
            self.assertEqual(core.export_irplus(d,target,0), 1)
            imported = core.import_irplus(target)
            self.assertEqual(imported[0]['buttons'][0]['label'], 'SET')
            self.assertEqual(imported[0]['buttons'][0]['pronto_hex'], core.pronto_for_button(b))
            self.assertTrue(len(imported[0]['buttons'][0]['pronto_hex'].split())>60)

    def test_existing_user_irplus_import(self):
        test_irplus = FIXTURES / 'Xflyee_099_SET_Test.irplus'
        imported = core.import_irplus(test_irplus)
        self.assertEqual(len(imported), 1)
        self.assertEqual(len(imported[0]['buttons']), 2)
        self.assertEqual(imported[0]['buttons'][0]['label'], 'SET')

    def test_library_save_load(self):
        lib = core.default_library()
        lib['devices'].append(core.new_device('Receiver'))
        with tempfile.TemporaryDirectory() as p:
            target = Path(p)/'library.json'
            core.save_library(target, lib)
            self.assertEqual(lib, core.load_library(target))

    def test_long_silence_trim(self):
        original = self.first['timings_us']
        self.assertGreater(original[-1], 200000)
        pronto = core.pronto_from_raw(self.first['carrier_hz'], original)
        self.assertEqual(int(pronto.split()[2],16),34)

if __name__ == '__main__':
    unittest.main()
