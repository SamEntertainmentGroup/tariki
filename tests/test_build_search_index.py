import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import build_search_index as bsi


def place(kind, name=None, name_ar=None, name_fr=None, coordinates=(3.05, 36.75)):
    properties = {"place": kind}
    for key, value in (("name", name), ("name:ar", name_ar), ("name:fr", name_fr)):
        if value:
            properties[key] = value
    return {"properties": properties, "geometry": {"type": "Point", "coordinates": list(coordinates)}}


class IndexTest(unittest.TestCase):
    def build(self, lines):
        directory = Path(tempfile.mkdtemp())
        source = directory / "places.geojsonseq"
        source.write_text("".join(lines), encoding="utf-8")
        output = directory / "out" / "places-index.json"
        code = bsi.main(["--input", str(source), "--output", str(output)])
        return code, output

    def test_rows_and_order(self):
        lines = [
            json.dumps(place("village", "Ain X", "عين إكس", "Aïn X", (5.123456, 35.987654)), ensure_ascii=False) + "\n",
            "\x1e" + json.dumps(place("city", "Alger", "الجزائر", "Alger"), ensure_ascii=False) + "\n",
            json.dumps(place("hamlet", "Dechra")) + "\n",
            json.dumps(place("town", name_ar="مدينة")) + "\n",
        ]
        code, output = self.build(lines)
        self.assertEqual(code, 0)
        data = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(data["version"], 1)
        kinds = [row[3] for row in data["places"]]
        self.assertEqual(kinds, ["city", "town", "village", "hamlet"])
        village = data["places"][2]
        self.assertEqual(village, ["Ain X", "عين إكس", "Aïn X", "village", 35.98765, 5.12346])
        self.assertEqual(data["places"][1][0], "")

    def test_invalid_and_unnamed_are_skipped(self):
        lines = [
            json.dumps(place("city")) + "\n",
            json.dumps(place("isolated_dwelling", "X")) + "\n",
            json.dumps(place("city", "Bad", coordinates=(999, 0))) + "\n",
            "pas du json\n",
            json.dumps(place("city", "Alger")) + "\n",
        ]
        code, output = self.build(lines)
        self.assertEqual(code, 0)
        data = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual([row[0] for row in data["places"]], ["Alger"])

    def test_empty_input_fails(self):
        code, output = self.build(["pas du json\n"])
        self.assertEqual(code, 1)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
