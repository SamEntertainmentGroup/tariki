import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import build_routing_graph as rg


def way(highway, coordinates, **tags):
    return {"type": "Feature", "properties": {"highway": highway, **tags},
            "geometry": {"type": "LineString", "coordinates": coordinates}}


def place(kind, lon, lat, **tags):
    return {"type": "Feature", "properties": {"place": kind, **tags},
            "geometry": {"type": "Point", "coordinates": [lon, lat]}}


def build(roads, places=()):
    with tempfile.TemporaryDirectory() as folder:
        roads_path, places_path = Path(folder) / "roads", Path(folder) / "places"
        # osmium prefixes each line with the RS control character
        roads_path.write_text("".join("\x1e" + json.dumps(r) + "\n" for r in roads), encoding="utf-8")
        places_path.write_text("".join("\x1e" + json.dumps(p, ensure_ascii=False) + "\n" for p in places), encoding="utf-8")
        data, graph, loaded_places, ways, skipped, largest = rg.build(roads_path, places_path)
    return rg.Reader(data), data, graph, skipped, largest


# Three roads meeting at (3.10, 36.10): A----J----B, and C from J going north.
A, J, B, C = [3.00, 36.10], [3.10, 36.10], [3.20, 36.10], [3.10, 36.20]


class NetworkTest(unittest.TestCase):
    def test_junction_splits_roads_into_edges(self):
        reader, *_ = build([way("primary", [A, J, B]), way("secondary", [J, C])])
        # A-J, J-B, J-C: 4 nodes (A, J, B, C), 3 edges, 6 arcs
        self.assertEqual((reader.nodes, reader.edges, reader.arc_count), (4, 3, 6))

    def test_shortest_path_between_roads_that_meet(self):
        reader, *_ = build([way("primary", [A, J, B]), way("secondary", [J, C])])
        start = reader.nearest_node(36.10, 3.00 + 0.0)
        goal = reader.nearest_node(36.20, 3.10)
        seconds, path = reader.shortest(start, goal)
        self.assertEqual(len(path), 2)
        self.assertGreater(seconds, 0)

    def test_roads_that_cross_without_shared_node_are_not_connected(self):
        # a bridge: the lines cross but share no vertex
        reader, *_ = build([way("primary", [[3.0, 36.0], [3.2, 36.2]]), way("primary", [[3.0, 36.2], [3.2, 36.0]])])
        self.assertEqual(reader.nodes, 4)
        start = reader.nearest_node(36.0, 3.0, main_only=False)
        goal = reader.nearest_node(36.0, 3.2, main_only=False)
        self.assertNotEqual(start, goal)
        self.assertIsNone(reader.shortest(start, goal))

    def test_oneway_is_respected(self):
        reader, *_ = build([way("residential", [A, J], oneway="yes")])
        a = reader.nearest_node(36.10, 3.00)
        j = reader.nearest_node(36.10, 3.10)
        self.assertIsNotNone(reader.shortest(a, j))
        self.assertIsNone(reader.shortest(j, a))

    def test_reverse_oneway_and_roundabout_and_motorway(self):
        reader, *_ = build([way("residential", [A, J], oneway="-1")])
        a, j = reader.nearest_node(36.10, 3.00), reader.nearest_node(36.10, 3.10)
        self.assertIsNone(reader.shortest(a, j))
        self.assertIsNotNone(reader.shortest(j, a))
        reader, *_ = build([way("motorway", [A, J])])
        a, j = reader.nearest_node(36.10, 3.00), reader.nearest_node(36.10, 3.10)
        self.assertIsNotNone(reader.shortest(a, j))
        self.assertIsNone(reader.shortest(j, a))
        reader, *_ = build([way("motorway", [A, J], oneway="no")])
        a, j = reader.nearest_node(36.10, 3.00), reader.nearest_node(36.10, 3.10)
        self.assertIsNotNone(reader.shortest(j, a))

    def test_geometry_round_trip_keeps_curve_in_both_directions(self):
        curve = [[3.000, 36.000], [3.001, 36.0015], [3.002, 36.001], [3.003, 36.002]]
        reader, *_ = build([way("tertiary", curve)])
        points = reader.edge_points(0)
        self.assertEqual(points, [(round(lat * 1e6), round(lon * 1e6)) for lon, lat in curve])
        self.assertEqual(reader.edge_points(0, reverse=True), points[::-1])

    def test_long_roads_are_split_so_snapping_stays_close(self):
        # about 11 km of straight road without any junction
        line = [[3.0 + 0.001 * i, 36.0] for i in range(0, 121)]
        reader, *_ = build([way("primary", line)])
        self.assertGreaterEqual(reader.edges, 7)
        for e in range(reader.edges):
            self.assertLessEqual(reader.edge_length[e] / 10.0, rg.MAX_EDGE_METRES + 120)

    def test_speed_class_and_length(self):
        reader, *_ = build([way("motorway_link", [A, J], maxspeed="80")])
        attr = reader.edge_attr[0]
        self.assertEqual(attr & 7, rg.CLASS_INDEX["motorway"])
        self.assertEqual(attr >> 3, 68)  # 80 km/h * 0.85
        self.assertAlmostEqual(reader.edge_length[0] / 10.0, 8_970, delta=120)  # 0.1 degree of longitude at 36.1 N
        reader, *_ = build([way("residential", [A, J])])
        self.assertEqual(reader.edge_attr[0] >> 3, 30)

    def test_unused_road_types_are_ignored(self):
        reader, _, _, skipped, _ = build([way("track", [A, J]), way("footway", [A, J]), way("service", [A, J])])
        self.assertEqual(reader.edges, 0)
        self.assertEqual(skipped["type de route non retenu"], 3)

    def test_small_disconnected_fragment_is_not_the_main_network(self):
        far = [[5.0, 35.0], [5.1, 35.0]]
        reader, _, _, _, largest = build([way("primary", [A, J, B]), way("residential", far)])
        self.assertEqual(largest, 3)
        main_nodes = [n for n in range(reader.nodes) if reader.flags[n]]
        self.assertEqual(len(main_nodes), 3)
        # snapping near the fragment still returns a node of the main network
        node = reader.nearest_node(35.0, 5.0, main_only=True)
        self.assertTrue(reader.flags[node])

    def test_places_are_sorted_and_names_kept(self):
        places = [
            place("village", 5.5, 36.0, name="Petit village"),
            place("city", 5.41, 36.19, name="Sétif", **{"name:ar": "سطيف", "name:fr": "Sétif", "population": "288461"}),
            place("hamlet", 5.0, 36.0, name="Hameau"),
            place("town", 5.3, 36.1, **{"name:ar": "العلمة"}),
            place("city", 5.4, 36.2),  # no name: ignored
        ]
        reader, *_ = build([way("primary", [A, J])], places)
        names = [p[4] or p[5] for p in reader.places]
        self.assertEqual(names, ["Sétif", "العلمة", "Petit village"])
        kind, population, lat, lon, name, name_ar, name_fr = reader.places[0]
        self.assertEqual((kind, population, lat, lon), (0, 288461, 36_190_000, 5_410_000))
        self.assertEqual((name_ar, name_fr), ("سطيف", "Sétif"))

    def test_file_header_and_alignment(self):
        reader, data, *_ = build([way("primary", [A, J, B])], [place("city", 3.0, 36.0, name="Alger")])
        self.assertEqual(data[:4], b"TRK1")
        self.assertEqual(reader.version, 1)
        self.assertEqual(reader.place_count, 1)

    def test_command_line_writes_gzip_and_report(self):
        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            (folder / "roads").write_text(json.dumps(way("primary", [A, J, B])) + "\n", encoding="utf-8")
            (folder / "places").write_text(json.dumps(place("city", 3.0, 36.0, name="Alger")) + "\n", encoding="utf-8")
            argv = sys.argv
            sys.argv = ["x", "--roads", str(folder / "roads"), "--places", str(folder / "places"),
                        "--output", str(folder / "out" / "r.bin.gz"), "--report", str(folder / "r.txt")]
            try:
                rg.main()
            finally:
                sys.argv = argv
            reader = rg.Reader(gzip.decompress((folder / "out" / "r.bin.gz").read_bytes()))
            self.assertEqual(reader.edges, 2)
            self.assertIn("RAPPORT DES ITINÉRAIRES", (folder / "r.txt").read_text(encoding="utf-8"))


KOTLIN_DIR = Path(__file__).resolve().parent.parent / "android" / "app" / "src" / "main" / "java" / "com" / "dztrafic" / "carte"


class KotlinReaderMatchesFileFormat(unittest.TestCase):
    """The Kotlin reader hard-codes header positions: they must match what the Python writer produces."""

    def setUp(self):
        self.kotlin = (KOTLIN_DIR / "RoutingGraph.kt").read_text(encoding="utf-8")
        roads = [way("primary", [A, J, B]), way("secondary", [J, C], oneway="yes")]
        self.reader, self.data, *_ = build(roads, [place("city", 3.0, 36.0, name="Alger")])

    def section(self, name):
        import re
        import struct
        match = re.search(rf"{name} = map\(header\.getInt\((\d+)\)", self.kotlin)
        self.assertIsNotNone(match, name)
        position = int(match.group(1))
        return struct.unpack_from("<I", self.data, position)[0]

    def test_header_constants(self):
        self.assertIn('"TRK1"', self.kotlin)
        self.assertIn("header.getInt(4) != 1", self.kotlin)
        for name, position in (("nodeCount", 8), ("edgeCount", 12), ("arcCount", 16)):
            self.assertRegex(self.kotlin, rf"{name} = header\.getInt\({position}\)")
        self.assertEqual(int.from_bytes(self.data[8:12], "little"), self.reader.nodes)
        self.assertEqual(int.from_bytes(self.data[12:16], "little"), self.reader.edges)
        self.assertEqual(int.from_bytes(self.data[16:20], "little"), self.reader.arc_count)
        self.assertEqual(rg.HEADER_SIZE, 128)
        self.assertIn("HEADER_SIZE = 128", self.kotlin)

    def test_every_section_offset_points_at_the_right_data(self):
        import struct
        from array import array

        def ints(typecode, offset, count):
            values = array(typecode)
            values.frombytes(self.data[offset: offset + count * values.itemsize])
            return values

        n, e, a = self.reader.nodes, self.reader.edges, self.reader.arc_count
        self.assertEqual(list(ints("i", self.section("lat"), n)), list(self.reader.lat))
        self.assertEqual(list(ints("i", self.section("lon"), n)), list(self.reader.lon))
        offset = self.section("flags")
        self.assertEqual(self.data[offset: offset + n], bytes(self.reader.flags))
        self.assertEqual(list(ints("I", self.section("arcStart"), n + 1)), list(self.reader.arc_start))
        self.assertEqual(list(ints("I", self.section("arcs"), a)), list(self.reader.arcs))
        self.assertEqual(list(ints("I", self.section("edgeFrom"), e)), list(self.reader.edge_from))
        self.assertEqual(list(ints("I", self.section("edgeTo"), e)), list(self.reader.edge_to))
        self.assertEqual(list(ints("I", self.section("edgeLength"), e)), list(self.reader.edge_length))
        self.assertEqual(list(ints("H", self.section("edgeAttr"), e)), list(self.reader.edge_attr))
        self.assertEqual(list(ints("I", self.section("edgeGeometry"), e)), list(self.reader.edge_geom))
        offset = self.section("geometry")
        self.assertEqual(self.data[offset: offset + len(self.reader.geometry)], bytes(self.reader.geometry))
        # places: offset and size in the header, 13 fixed bytes per record then three length-prefixed strings
        places_offset = struct.unpack_from("<I", self.data, 24)[0]
        places_size = struct.unpack_from("<I", self.data, 28)[0]
        self.assertIn("placesOffset = header.getInt(24)", self.kotlin)
        self.assertIn("placesSize = header.getInt(28)", self.kotlin)
        self.assertIn("buffer.get(position + 12)", self.kotlin)
        self.assertEqual(self.data[places_offset + 13], len("Alger".encode()))
        self.assertGreater(places_size, 13)
        # the geometry section is the last one: its end is the end of the file
        self.assertEqual(offset + len(self.reader.geometry), len(self.data))

    def test_arc_sections_are_four_byte_aligned(self):
        for name in ("lat", "lon", "arcStart", "arcs", "edgeFrom", "edgeTo", "edgeLength", "edgeAttr", "edgeGeometry"):
            self.assertEqual(self.section(name) % 4, 0, name)


if __name__ == "__main__":
    unittest.main()
