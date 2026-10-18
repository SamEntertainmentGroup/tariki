import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import prepare_layers as pl


def road(highway, coordinates=None, **tags):
    coordinates = coordinates or [[3.0, 36.0], [3.1, 36.1]]
    return {"properties": {"highway": highway, **tags},
            "geometry": {"type": "LineString", "coordinates": coordinates}}


def place(kind, coordinates=None, geometry_type="Point", **tags):
    coordinates = coordinates or [3.05, 36.75]
    return {"properties": {"place": kind, **tags},
            "geometry": {"type": geometry_type, "coordinates": coordinates}}


class RoadTest(unittest.TestCase):
    def test_classes_and_zooms(self):
        expected = {
            "motorway": ("motorway", 5), "trunk": ("trunk", 5), "primary": ("primary", 7),
            "secondary": ("secondary", 9), "tertiary": ("tertiary", 11),
            "unclassified": ("minor", 12), "residential": ("minor", 12),
        }
        for highway, (road_class, zoom) in expected.items():
            with self.subTest(highway=highway):
                converted, label = pl.road_feature(road(highway))
                self.assertEqual(converted["properties"]["class"], road_class)
                self.assertEqual(converted["tippecanoe"]["minzoom"], zoom)
                self.assertEqual(label, road_class)

    def test_links_appear_later(self):
        converted, _ = pl.road_feature(road("motorway_link"))
        self.assertEqual(converted["properties"]["class"], "motorway")
        self.assertEqual(converted["tippecanoe"]["minzoom"], 10)
        converted, _ = pl.road_feature(road("tertiary_link"))
        self.assertEqual(converted["tippecanoe"]["minzoom"], 13)

    def test_ref_is_kept_and_cleaned(self):
        converted, _ = pl.road_feature(road("primary", ref="  RN   5 "))
        self.assertEqual(converted["properties"], {"class": "primary", "ref": "RN 5"})
        converted, _ = pl.road_feature(road("primary", name="Rue X"))
        self.assertEqual(converted["properties"], {"class": "primary"})

    def test_rejections(self):
        cases = [
            (road("service"), "type de route non retenu"),
            (road("track"), "type de route non retenu"),
            ({"properties": {}, "geometry": {"type": "LineString", "coordinates": [[0, 0], [1, 1]]}}, "type de route non retenu"),
            ({"properties": {"highway": "primary"}, "geometry": {"type": "Polygon", "coordinates": []}}, "géométrie non linéaire"),
            (road("primary", [[3.0, 36.0]]), "géométrie trop courte"),
            (road("primary", [[3.0, 36.0], [300.0, 36.0]]), "coordonnées invalides"),
            (road("primary", [[3.0, 36.0], ["x", 36.0]]), "coordonnées invalides"),
            (road("primary", [[3.0, 36.0], [float("nan"), 36.0]]), "coordonnées invalides"),
        ]
        for feature, reason in cases:
            with self.subTest(reason=reason):
                converted, label = pl.road_feature(feature)
                self.assertIsNone(converted)
                self.assertEqual(label, reason)

    def test_coordinates_are_rounded(self):
        converted, _ = pl.road_feature(road("primary", [[3.123456789, 36.987654321], [3.2, 36.3]]))
        self.assertEqual(converted["geometry"]["coordinates"][0], [3.123457, 36.987654])


class PlaceTest(unittest.TestCase):
    def test_names_and_zoom(self):
        converted, label = pl.place_feature(
            place("village", **{"name": "Ain X", "name:ar": "عين إكس", "name:fr": "Aïn X"}))
        self.assertEqual(label, "village")
        self.assertEqual(converted["properties"],
                         {"class": "village", "name": "Ain X", "name_ar": "عين إكس", "name_fr": "Aïn X"})
        self.assertEqual(converted["tippecanoe"]["minzoom"], 10)

    def test_arabic_only_is_kept(self):
        converted, _ = pl.place_feature(place("town", **{"name:ar": "مدينة"}))
        self.assertEqual(converted["properties"], {"class": "town", "name_ar": "مدينة"})

    def test_rejections(self):
        cases = [
            (place("isolated_dwelling", name="X"), "type de lieu non retenu"),
            (place("city"), "lieu sans nom"),
            (place("city", name="  "), "lieu sans nom"),
            (place("city", geometry_type="LineString", name="X"), "coordonnées invalides"),
            (place("city", coordinates=[999, 0], name="X"), "coordonnées invalides"),
        ]
        for feature, reason in cases:
            with self.subTest(reason=reason):
                converted, label = pl.place_feature(feature)
                self.assertIsNone(converted)
                self.assertEqual(label, reason)

    def test_zoom_order(self):
        zooms = [pl.PLACE_MIN_ZOOM[k] for k in ("city", "town", "village", "hamlet")]
        self.assertEqual(zooms, sorted(zooms))


class ConvertTest(unittest.TestCase):
    def test_file_conversion_handles_record_separators_and_garbage(self):
        directory = Path(tempfile.mkdtemp())
        source = directory / "roads.geojsonseq"
        rows = [
            "\x1e" + json.dumps(road("primary", ref="RN 1")) + "\n",
            json.dumps(road("residential")) + "\n",
            "\n",
            "pas du json\n",
            "[1, 2]\n",
            "\x1e" + json.dumps(road("service")) + "\n",
        ]
        source.write_text("".join(rows), encoding="utf-8")
        target = directory / "out.geojsonseq"
        kept, skipped = pl.convert(source, target, pl.road_feature)
        self.assertEqual(kept, {"primary": 1, "minor": 1})
        self.assertEqual(skipped["ligne JSON illisible"], 2)
        self.assertEqual(skipped["type de route non retenu"], 1)
        lines = target.read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(lines), 2)
        for line in lines:
            self.assertNotIn("\x1e", line)
            self.assertIn("minzoom", json.loads(line)["tippecanoe"])

    def test_main_fails_when_nothing_is_kept(self):
        directory = Path(tempfile.mkdtemp())
        empty = directory / "empty.geojsonseq"
        empty.write_text("", encoding="utf-8")
        argv = ["prepare_layers.py", "--roads-in", str(empty), "--roads-out", str(directory / "r"),
                "--places-in", str(empty), "--places-out", str(directory / "p")]
        original = sys.argv
        sys.argv = argv
        try:
            self.assertEqual(pl.main(), 1)
        finally:
            sys.argv = original


if __name__ == "__main__":
    unittest.main()
