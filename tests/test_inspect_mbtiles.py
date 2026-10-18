import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import inspect_mbtiles as im


def make_mbtiles(layers, tiles):
    path = Path(tempfile.mkdtemp()) / "test.mbtiles"
    connection = sqlite3.connect(path)
    connection.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
    connection.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
    metadata = {
        "format": "pbf", "minzoom": "4", "maxzoom": "14", "bounds": "2.8,36.6,3.3,36.9",
        "json": json.dumps({"vector_layers": [{"id": layer} for layer in layers]}),
    }
    connection.executemany("INSERT INTO metadata VALUES (?, ?)", metadata.items())
    connection.executemany("INSERT INTO tiles VALUES (?, ?, ?, ?)", tiles)
    connection.commit()
    connection.close()
    return path


class InspectTest(unittest.TestCase):
    def test_valid_file(self):
        path = make_mbtiles(["roads", "places"], [(4, 0, 0, b"x" * 100), (14, 1, 1, b"y" * 300), (14, 1, 2, b"z" * 50)])
        report, ok = im.inspect(path)
        self.assertTrue(ok)
        self.assertIn("zoom  4 :", report)
        self.assertIn("zoom 14 :", report)
        self.assertIn("2 tuiles", report)
        self.assertIn("Contrôles : OK", report)

    def test_missing_layer_is_reported(self):
        path = make_mbtiles(["roads"], [(4, 0, 0, b"x")])
        report, ok = im.inspect(path)
        self.assertFalse(ok)
        self.assertIn("couches absentes : places", report)

    def test_empty_file_is_reported(self):
        path = make_mbtiles(["roads", "places"], [])
        report, ok = im.inspect(path)
        self.assertFalse(ok)
        self.assertIn("aucune tuile", report)


if __name__ == "__main__":
    unittest.main()
