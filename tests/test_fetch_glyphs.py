import gzip
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import fetch_glyphs as fg


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def field(number, wire_type, payload):
    key = varint((number << 3) | wire_type)
    if wire_type == 0:
        return key + varint(payload)
    return key + varint(len(payload)) + payload


def glyph(code):
    return (field(1, 0, code) + field(2, 2, b"\x00\x01") + field(3, 0, 5) + field(4, 0, 5)
            + field(5, 0, 0) + field(6, 0, 0) + field(7, 0, 6))


def pbf(codes, name="Test", range_name="0-255"):
    stack = field(1, 2, name.encode()) + field(2, 2, range_name.encode())
    stack += b"".join(field(3, 2, glyph(code)) for code in codes)
    return field(1, 2, stack)


class ParserTest(unittest.TestCase):
    def test_reads_ids(self):
        self.assertEqual(fg.glyph_ids(pbf([65, 66, 0x0627, 0xFEFB])), {65, 66, 0x0627, 0xFEFB})

    def test_reads_gzip(self):
        self.assertEqual(fg.glyph_ids(gzip.compress(pbf([1, 2, 300]))), {1, 2, 300})

    def test_empty_stack(self):
        self.assertEqual(fg.glyph_ids(pbf([])), set())

    def test_truncated_data_is_rejected(self):
        with self.assertRaises(fg.GlyphFormatError):
            fg.glyph_ids(pbf([65, 66])[:-3])

    def test_coverage(self):
        full = fg.ARABIC_BASE_LETTERS | fg.ARABIC_SHAPED_FORMS
        self.assertEqual(fg.arabic_coverage(full), ([], []))
        letters, forms = fg.arabic_coverage(fg.ARABIC_BASE_LETTERS)
        self.assertEqual(letters, [])
        self.assertEqual(len(forms), len(fg.ARABIC_SHAPED_FORMS))


class RunTest(unittest.TestCase):
    def run_with(self, served):
        output = Path(tempfile.mkdtemp())

        def fake_fetch(url):
            name = url.rsplit("/", 1)[1]
            return served.get(name)

        with mock.patch.object(fg, "fetch", side_effect=fake_fetch):
            latin_ok, report = fg.run(output)
        return output, latin_ok, report

    def test_full_coverage(self):
        served = {
            "0-255.pbf": pbf(range(32, 127)),
            "1536-1791.pbf": pbf(fg.ARABIC_BASE_LETTERS),
            "65024-65279.pbf": pbf(fg.ARABIC_SHAPED_FORMS),
        }
        output, latin_ok, report = self.run_with(served)
        self.assertTrue(latin_ok)
        self.assertIn("Arabe : OK", report)
        self.assertIn("256-511.pbf : absent", report)
        self.assertTrue((output / fg.TARGET_FONT / "0-255.pbf").is_file())
        self.assertFalse((output / fg.TARGET_FONT / "256-511.pbf").exists())

    def test_missing_shaped_forms_is_flagged(self):
        served = {"0-255.pbf": pbf(range(32, 127)), "1536-1791.pbf": pbf(fg.ARABIC_BASE_LETTERS)}
        _, latin_ok, report = self.run_with(served)
        self.assertTrue(latin_ok)
        self.assertIn("Arabe : INCOMPLET", report)
        self.assertIn("U+FEFB", report)

    def test_missing_latin_fails(self):
        _, latin_ok, report = self.run_with({"1536-1791.pbf": pbf([0x0627])})
        self.assertFalse(latin_ok)
        self.assertIn("Latin : ÉCHEC", report)

    def test_gzip_files_are_stored_decompressed(self):
        raw = pbf(range(32, 127))
        output, _, _ = self.run_with({"0-255.pbf": gzip.compress(raw)})
        self.assertEqual((output / fg.TARGET_FONT / "0-255.pbf").read_bytes(), raw)

    def test_main_exit_codes(self):
        directory = tempfile.mkdtemp()
        with mock.patch.object(fg, "fetch", return_value=pbf(range(32, 127))):
            self.assertEqual(fg.main(["--output", directory]), 0)
        with mock.patch.object(fg, "fetch", return_value=None):
            self.assertEqual(fg.main(["--output", directory]), 1)
        with mock.patch.object(fg, "fetch", side_effect=RuntimeError("réseau")):
            self.assertEqual(fg.main(["--output", directory]), 1)


if __name__ == "__main__":
    unittest.main()
