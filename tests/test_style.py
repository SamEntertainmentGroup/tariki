import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import fetch_glyphs
import prepare_layers

STYLE_PATH = ROOT / "android" / "app" / "src" / "main" / "assets" / "style.json"
TEXT_FIELD_ARABIC = '["coalesce",["get","name_ar"],["get","name"],["get","name_fr"]]'
TEXT_FIELD_FRENCH = '["coalesce",["get","name_fr"],["get","name"],["get","name_ar"]]'
SOURCE_LAYERS = {"roads", "places", "borders", "coast"}


def load_style(text_field=TEXT_FIELD_ARABIC):
    raw = STYLE_PATH.read_text(encoding="utf-8")
    raw = raw.replace('"{{TEXT_FIELD}}"', text_field)
    raw = raw.replace("{{MBTILES_URL}}", "mbtiles:///data/user/0/app/files/algeria-map.mbtiles")
    return json.loads(raw)


def class_values(layer):
    found = set()

    def walk(node):
        if isinstance(node, list):
            if len(node) == 3 and node[0] == "==" and node[1] == ["get", "class"]:
                found.add(node[2])
            if len(node) >= 3 and node[0] == "in" and node[1] == "class":
                found.update(node[2:])
            for item in node:
                walk(item)

    walk(layer.get("filter", []))
    return found


class StyleTest(unittest.TestCase):
    def test_template_placeholders_are_present_once(self):
        raw = STYLE_PATH.read_text(encoding="utf-8")
        self.assertEqual(raw.count("{{MBTILES_URL}}"), 1)
        self.assertEqual(raw.count('"{{TEXT_FIELD}}"'), 5)
        json.loads(raw)

    def test_both_languages_produce_valid_json(self):
        for text_field in (TEXT_FIELD_ARABIC, TEXT_FIELD_FRENCH):
            style = load_style(text_field)
            places = [layer for layer in style["layers"] if layer["id"].startswith("places-")]
            self.assertEqual(len(places), 5)
            for layer in places:
                self.assertIsInstance(layer["layout"]["text-field"], list)
                self.assertEqual(layer["layout"]["text-field"][0], "coalesce")

    def test_source_is_a_local_vector_source(self):
        style = load_style()
        source = style["sources"]["algeria"]
        self.assertEqual(source["type"], "vector")
        self.assertTrue(source["url"].startswith("mbtiles://"))
        self.assertEqual(style["version"], 8)

    def test_layers_use_existing_tile_layers_and_unique_ids(self):
        style = load_style()
        ids = [layer["id"] for layer in style["layers"]]
        self.assertEqual(len(ids), len(set(ids)))
        for layer in style["layers"]:
            if layer["type"] == "background" or layer["id"] == "alerts":
                continue
            self.assertEqual(layer["source"], "algeria")
            self.assertIn(layer["source-layer"], SOURCE_LAYERS)

    def test_every_road_class_is_drawn_with_casing_below_fill(self):
        style = load_style()
        order = [layer["id"] for layer in style["layers"]]
        produced = {value for value, _ in prepare_layers.ROAD_CLASSES.values()}
        for road_class in produced:
            casing, fill = f"road-casing-{road_class}", f"road-fill-{road_class}"
            self.assertIn(casing, order)
            self.assertIn(fill, order)
            self.assertLess(order.index(casing), order.index(fill))
        casings = [i for i, name in enumerate(order) if name.startswith("road-casing-")]
        fills = [i for i, name in enumerate(order) if name.startswith("road-fill-")]
        self.assertLess(max(casings), min(fills))

    def test_class_values_in_filters_exist_in_tiles(self):
        style = load_style()
        produced_roads = {value for value, _ in prepare_layers.ROAD_CLASSES.values()}
        for layer in style["layers"]:
            values = class_values(layer)
            if layer.get("source-layer") == "roads":
                self.assertLessEqual(values, produced_roads, layer["id"])

    def test_fonts_match_downloaded_glyph_folder(self):
        style = load_style()
        for layer in style["layers"]:
            fonts = layer.get("layout", {}).get("text-font")
            if fonts:
                self.assertEqual(fonts, [fetch_glyphs.TARGET_FONT], layer["id"])
        self.assertEqual(style["glyphs"], "asset://glyphs/{fontstack}/{range}.pbf")

    def test_place_properties_used_by_labels_exist_in_tiles(self):
        raw = STYLE_PATH.read_text(encoding="utf-8")
        for key in ("name", "name_ar", "name_fr", "class", "ref"):
            self.assertIn(f'"{key}"', raw + TEXT_FIELD_ARABIC)
        place, _ = prepare_layers.place_feature({
            "properties": {"place": "town", "name": "A", "name:ar": "ب", "name:fr": "C"},
            "geometry": {"type": "Point", "coordinates": [3.0, 36.0]},
        })
        self.assertEqual(set(place["properties"]), {"class", "name", "name_ar", "name_fr"})
        road, _ = prepare_layers.road_feature({
            "properties": {"highway": "primary", "ref": "RN 5"},
            "geometry": {"type": "LineString", "coordinates": [[3.0, 36.0], [3.1, 36.1]]},
        })
        self.assertEqual(set(road["properties"]), {"class", "ref"})

    def test_border_and_coast_layers_sit_below_roads(self):
        style = load_style()
        order = [layer["id"] for layer in style["layers"]]
        below = ["coast", "border-8", "border-4", "border-2"]
        for name in below:
            self.assertIn(name, order)
            self.assertLess(order.index(name), order.index("road-casing-minor"))
        self.assertEqual(order[0], "background")

    def test_border_levels_exist_in_tiles(self):
        style = load_style()
        produced = {int(level) for level in prepare_layers.BORDER_MIN_ZOOM}
        drawn = set()
        for layer in style["layers"]:
            if layer.get("source-layer") == "borders":
                self.assertEqual(layer["filter"][0:2], ["==", ["get", "level"]])
                drawn.add(layer["filter"][2])
                self.assertGreaterEqual(layer["minzoom"], prepare_layers.BORDER_MIN_ZOOM[str(layer["filter"][2])])
        self.assertEqual(drawn, produced)

    def test_alert_layer_is_last_and_uses_known_icons(self):
        style = load_style()
        last = style["layers"][-1]
        self.assertEqual(last["id"], "alerts")
        self.assertEqual(last["source"], "alerts")
        self.assertEqual(style["sources"]["alerts"]["type"], "geojson")
        self.assertEqual(last["layout"]["icon-image"], ["get", "type"])
        kotlin = (ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "dztrafic" / "carte" / "Alert.kt").read_text(encoding="utf-8")
        for icon in ("route", "accident", "danger"):
            self.assertIn(f'"{icon}"', kotlin)

    def test_place_names_appear_when_zoomed_out(self):
        style = load_style()
        places = {layer["id"]: layer for layer in style["layers"] if layer["id"].startswith("places-")}
        self.assertEqual(set(places), {"places-city", "places-town", "places-village", "places-hamlet", "places-suburb"})
        self.assertEqual(places["places-city"]["minzoom"], 4)
        self.assertEqual(places["places-town"]["minzoom"], 6)
        self.assertEqual(places["places-village"]["minzoom"], 8)
        self.assertEqual(places["places-suburb"]["minzoom"], 10)
        self.assertEqual(places["places-hamlet"]["minzoom"], 11)
        for cls, layer in places.items():
            self.assertEqual(layer["filter"], ["==", ["get", "class"], cls.removeprefix("places-")])

    def test_place_label_sizes_follow_importance(self):
        style = load_style()
        places = {layer["id"]: layer for layer in style["layers"] if layer["id"].startswith("places-")}
        sizes = {key.removeprefix("places-"): layer["layout"]["text-size"] for key, layer in places.items()}
        self.assertGreaterEqual(sizes["city"], sizes["town"])
        self.assertGreaterEqual(sizes["town"], sizes["village"])
        self.assertGreaterEqual(sizes["village"], sizes["hamlet"])
        self.assertGreaterEqual(min(sizes.values()), 10)

    def test_background_is_darker_and_rural_roads_have_contrast(self):
        style = load_style()
        layers = {layer["id"]: layer for layer in style["layers"]}
        self.assertEqual(layers["background"]["paint"]["background-color"], "#c2bdb2")
        self.assertEqual(layers["road-fill-minor"]["paint"]["line-color"], "#ffffff")
        self.assertEqual(layers["road-casing-minor"]["paint"]["line-color"], "#777064")

    def test_larger_places_win_label_collisions(self):
        style = load_style()
        places = [layer for layer in style["layers"] if layer["id"].startswith("places-")]
        self.assertEqual(len(places), 5)
        for layer in places:
            key = layer["layout"]["symbol-sort-key"]
            self.assertEqual(key[0], "-")
            self.assertIn("population", json.dumps(key))

    def test_zoom_ranges_are_ordered(self):
        style = load_style()
        for layer in style["layers"]:
            stops = layer.get("paint", {}).get("line-width")
            if isinstance(stops, list) and stops[0] == "interpolate":
                zooms = stops[3::2]
                self.assertEqual(zooms, sorted(zooms), layer["id"])


if __name__ == "__main__":
    unittest.main()
