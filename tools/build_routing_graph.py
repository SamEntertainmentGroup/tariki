#!/usr/bin/env python3
"""Turn osmium road/place GeoJSON sequences into one compact routing file for the app.

The file (format "TRK1", little endian) holds a road graph for the whole country plus a list of
cities/towns/villages for the search box. The app memory-maps it and runs a shortest-path search.

Layout: a 128-byte header, then 4-byte aligned sections (offsets are in the header):
  places | node lat | node lon | node flags | arc start | arcs | edge from | edge to |
  edge length | edge attr | edge geometry offset | geometry blob
Units: coordinates in 1e-6 degrees (int32), lengths in decimetres (uint32).
Edge attr (uint16) = speed_kmh << 3 | road class (0 motorway .. 6 residential).
Arc (uint32) = edge << 1 | direction (0 = from->to, 1 = to->from).
Node flag (uint8) = 1 when the node belongs to the largest connected network.
Geometry: varint count of intermediate points, then zigzag varint (dlat, dlon) deltas.
"""
import argparse
import gzip
import json
import math
import struct
import sys
from array import array
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from prepare_layers import clean_text, population_value, valid_position  # noqa: E402

MAGIC = b"TRK1"
VERSION = 1
HEADER_SIZE = 128
HEADER_FORMAT = "<4s5I14I"
MAX_EDGE_METRES = 1500.0
MAX_PLACES = 40000

CLASS_INDEX = {
    "motorway": 0, "trunk": 1, "primary": 2, "secondary": 3,
    "tertiary": 4, "unclassified": 5, "residential": 6,
}
CLASS_NAMES = list(CLASS_INDEX)
DEFAULT_SPEED = [100, 85, 75, 60, 50, 40, 30]
LINK_SPEED_CAP = 50
PLACE_CLASS = {"city": 0, "town": 1, "village": 2}

ONEWAY_NONE, ONEWAY_FORWARD, ONEWAY_BACKWARD = 0, 1, 2


def iter_features(path):
    with open(path, "r", encoding="utf-8") as reader:
        for line in reader:
            line = line.strip("\x1e \t\r\n")
            if not line:
                continue
            try:
                feature = json.loads(line)
            except ValueError:
                continue
            if isinstance(feature, dict):
                yield feature


def parse_maxspeed(raw):
    if not isinstance(raw, str):
        return 0
    text = raw.strip().lower()
    digits = ""
    for char in text:
        if char.isdigit():
            digits += char
        elif digits:
            break
    if not digits:
        return 0
    value = int(digits)
    if "mph" in text:
        value = round(value * 1.609)
    return value if 5 <= value <= 140 else 0


def speed_for(road_class, is_link, maxspeed):
    speed = DEFAULT_SPEED[road_class]
    if is_link:
        speed = min(speed, LINK_SPEED_CAP)
    value = parse_maxspeed(maxspeed)
    if value:
        speed = max(10, min(110, round(value * 0.85)))
    return speed


def oneway_for(properties, base):
    tag = str(properties.get("oneway", "")).strip().lower()
    if tag in ("yes", "true", "1"):
        return ONEWAY_FORWARD
    if tag in ("-1", "reverse"):
        return ONEWAY_BACKWARD
    if tag in ("no", "false", "0"):
        return ONEWAY_NONE
    if str(properties.get("junction", "")).strip().lower() in ("roundabout", "circular"):
        return ONEWAY_FORWARD
    if base == "motorway":
        return ONEWAY_FORWARD
    return ONEWAY_NONE


def vertex_key(lat, lon):
    return ((lat + 90_000_000) << 32) | (lon + 180_000_000)


def segment_metres(lat1, lon1, lat2, lon2):
    d_lat = (lat2 - lat1) * 0.110574
    d_lon = (lon2 - lon1) * 0.111320 * math.cos(math.radians((lat1 + lat2) / 2e6))
    return math.hypot(d_lat, d_lon)


def load_ways(path):
    """Read road lines into compact ways and count how many times each vertex is used."""
    ways = []
    counts = {}
    skipped = Counter()
    for feature in iter_features(path):
        properties = feature.get("properties") or {}
        geometry = feature.get("geometry") or {}
        highway = properties.get("highway")
        if not isinstance(highway, str):
            skipped["pas une route"] += 1
            continue
        is_link = highway.endswith("_link")
        base = highway[: -len("_link")] if is_link else highway
        if base not in CLASS_INDEX:
            skipped["type de route non retenu"] += 1
            continue
        if geometry.get("type") != "LineString":
            skipped["géométrie non linéaire"] += 1
            continue
        positions = geometry.get("coordinates")
        if not isinstance(positions, list) or len(positions) < 2:
            skipped["géométrie trop courte"] += 1
            continue
        if not all(valid_position(position) for position in positions):
            skipped["coordonnées invalides"] += 1
            continue
        coords = array("i")
        last = None
        for position in positions:
            point = (round(position[1] * 1e6), round(position[0] * 1e6))
            if point == last:
                continue
            coords.append(point[0])
            coords.append(point[1])
            last = point
        if len(coords) < 4:
            skipped["géométrie trop courte"] += 1
            continue
        road_class = CLASS_INDEX[base]
        speed = speed_for(road_class, is_link, properties.get("maxspeed"))
        ways.append((coords, road_class, speed, oneway_for(properties, base)))
        for i in range(0, len(coords), 2):
            key = vertex_key(coords[i], coords[i + 1])
            counts[key] = counts.get(key, 0) + 1
    return ways, counts, skipped


def write_varint(buffer, value):
    while value >= 0x80:
        buffer.append((value & 0x7F) | 0x80)
        value >>= 7
    buffer.append(value)


def zigzag(value):
    return (value << 1) ^ (value >> 63)


class Graph:
    def __init__(self):
        self.node_lat = array("i")
        self.node_lon = array("i")
        self.edge_from = array("I")
        self.edge_to = array("I")
        self.edge_length = array("I")
        self.edge_attr = array("H")
        self.edge_oneway = array("B")
        self.edge_geom = array("I")
        self.geometry = bytearray()
        self.node_flags = bytearray()
        self.arc_start = array("I")
        self.arcs = array("I")


def build_graph(ways, counts):
    graph = Graph()
    node_ids = {}

    def node(lat, lon):
        key = vertex_key(lat, lon)
        found = node_ids.get(key)
        if found is None:
            found = len(graph.node_lat)
            node_ids[key] = found
            graph.node_lat.append(lat)
            graph.node_lon.append(lon)
        return found

    for coords, road_class, speed, oneway in ways:
        count = len(coords) // 2
        first = 0
        length = 0.0
        for i in range(1, count):
            length += segment_metres(coords[2 * (i - 1)], coords[2 * (i - 1) + 1], coords[2 * i], coords[2 * i + 1])
            last = i == count - 1
            if not (last or counts[vertex_key(coords[2 * i], coords[2 * i + 1])] >= 2 or length >= MAX_EDGE_METRES):
                continue
            start_node = node(coords[2 * first], coords[2 * first + 1])
            end_node = node(coords[2 * i], coords[2 * i + 1])
            decimetres = round(length * 10)
            if start_node != end_node and decimetres > 0:
                graph.edge_from.append(start_node)
                graph.edge_to.append(end_node)
                graph.edge_length.append(decimetres)
                graph.edge_attr.append((speed << 3) | road_class)
                graph.edge_oneway.append(oneway)
                graph.edge_geom.append(len(graph.geometry))
                write_varint(graph.geometry, i - first - 1)
                previous_lat, previous_lon = coords[2 * first], coords[2 * first + 1]
                for j in range(first + 1, i):
                    write_varint(graph.geometry, zigzag(coords[2 * j] - previous_lat))
                    write_varint(graph.geometry, zigzag(coords[2 * j + 1] - previous_lon))
                    previous_lat, previous_lon = coords[2 * j], coords[2 * j + 1]
            first = i
            length = 0.0
    return graph


def finish_graph(graph):
    """Arc table (respecting one-way roads) and the largest connected network."""
    node_count = len(graph.node_lat)
    edge_count = len(graph.edge_from)

    outgoing = [0] * (node_count + 1)
    for e in range(edge_count):
        oneway = graph.edge_oneway[e]
        if oneway != ONEWAY_BACKWARD:
            outgoing[graph.edge_from[e]] += 1
        if oneway != ONEWAY_FORWARD:
            outgoing[graph.edge_to[e]] += 1
    arc_start = array("I", [0] * (node_count + 1))
    total = 0
    for n in range(node_count):
        arc_start[n] = total
        total += outgoing[n]
    arc_start[node_count] = total
    arcs = array("I", [0] * total)
    cursor = array("I", arc_start)
    for e in range(edge_count):
        oneway = graph.edge_oneway[e]
        if oneway != ONEWAY_BACKWARD:
            a = graph.edge_from[e]
            arcs[cursor[a]] = e << 1
            cursor[a] += 1
        if oneway != ONEWAY_FORWARD:
            b = graph.edge_to[e]
            arcs[cursor[b]] = (e << 1) | 1
            cursor[b] += 1
    graph.arc_start = arc_start
    graph.arcs = arcs

    parent = list(range(node_count))

    def find(x):
        root = x
        while parent[root] != root:
            root = parent[root]
        while parent[x] != root:
            parent[x], x = root, parent[x]
        return root

    for e in range(edge_count):
        a, b = find(graph.edge_from[e]), find(graph.edge_to[e])
        if a != b:
            parent[a] = b
    sizes = Counter(find(n) for n in range(node_count))
    biggest = sizes.most_common(1)[0][0] if sizes else -1
    graph.node_flags = bytearray(1 if find(n) == biggest else 0 for n in range(node_count))
    return sizes.most_common(1)[0][1] if sizes else 0


def short_text(text):
    data = text.encode("utf-8")
    while len(data) > 255:
        text = text[:-1]
        data = text.encode("utf-8")
    return data


def load_places(path):
    places = []
    for feature in iter_features(path):
        properties = feature.get("properties") or {}
        kind = properties.get("place")
        geometry = feature.get("geometry") or {}
        if kind not in PLACE_CLASS or geometry.get("type") != "Point":
            continue
        position = geometry.get("coordinates")
        if not valid_position(position):
            continue
        name = clean_text(properties.get("name"))
        name_ar = clean_text(properties.get("name:ar"))
        name_fr = clean_text(properties.get("name:fr"))
        if not (name or name_ar or name_fr):
            continue
        population = min(population_value(properties.get("population")), 2_000_000_000)
        places.append((PLACE_CLASS[kind], -population, name or name_ar or name_fr,
                       round(position[1] * 1e6), round(position[0] * 1e6), name, name_ar, name_fr))
    places.sort()
    return places[:MAX_PLACES]


def encode_places(places):
    blob = bytearray()
    for kind, negative_population, _, lat, lon, name, name_ar, name_fr in places:
        blob += struct.pack("<iiIB", lat, lon, -negative_population, kind)
        for text in (name, name_ar, name_fr):
            data = short_text(text)
            blob.append(len(data))
            blob += data
    return bytes(blob)


def to_bytes(values):
    data = array(values.typecode, values)
    if sys.byteorder == "big":
        data.byteswap()
    return data.tobytes()


def serialize(graph, places):
    sections = [
        encode_places(places),
        to_bytes(graph.node_lat),
        to_bytes(graph.node_lon),
        bytes(graph.node_flags),
        to_bytes(graph.arc_start),
        to_bytes(graph.arcs),
        to_bytes(graph.edge_from),
        to_bytes(graph.edge_to),
        to_bytes(graph.edge_length),
        to_bytes(graph.edge_attr),
        to_bytes(graph.edge_geom),
        bytes(graph.geometry),
    ]
    offsets = []
    body = bytearray()
    for section in sections:
        while len(body) % 4:
            body.append(0)
        offsets.append(HEADER_SIZE + len(body))
        body += section
    (o_places, o_lat, o_lon, o_flags, o_arc_start, o_arcs, o_from, o_to, o_len, o_attr, o_geom_off, o_geom) = offsets
    header = struct.pack(
        HEADER_FORMAT, MAGIC, VERSION,
        len(graph.node_lat), len(graph.edge_from), len(graph.arcs), len(places),
        o_places, len(sections[0]), o_lat, o_lon, o_flags, o_arc_start, o_arcs,
        o_from, o_to, o_len, o_attr, o_geom_off, o_geom, len(sections[-1]),
    )
    header += bytes(HEADER_SIZE - len(header))
    return header + bytes(body)


def build(roads_path, places_path):
    ways, counts, skipped = load_ways(roads_path)
    graph = build_graph(ways, counts)
    largest = finish_graph(graph)
    places = load_places(places_path)
    data = serialize(graph, places)
    return data, graph, places, ways, skipped, largest


def report(data, graph, places, ways, skipped, largest, compressed_size=None):
    classes = Counter()
    for e in range(len(graph.edge_attr)):
        classes[CLASS_NAMES[graph.edge_attr[e] & 7]] += 1
    nodes = len(graph.node_lat)
    lines = [
        "=== RAPPORT DES ITINÉRAIRES ===",
        f"Routes lues : {len(ways):,}",
        f"Nœuds : {nodes:,} | tronçons : {len(graph.edge_from):,} | arcs : {len(graph.arcs):,}",
        f"Plus grand réseau connecté : {largest:,} nœuds ({100 * largest / nodes:.1f} %)" if nodes else "Graphe vide",
        f"Lieux de recherche : {len(places):,}",
        f"Taille du fichier : {len(data) / 1e6:.1f} Mo" + (
            f" | compressé : {compressed_size / 1e6:.1f} Mo" if compressed_size else ""),
        "Tronçons par type :",
    ]
    lines += [f"  {name} : {classes[name]:,}" for name in CLASS_NAMES]
    lines += [f"Ignorés ({reason}) : {count:,}" for reason, count in sorted(skipped.items())]
    return "\n".join(lines)


# ---- Reader used by the self-tests (the app has its own Kotlin reader) ----

class Reader:
    def __init__(self, data):
        if data[:4] != MAGIC:
            raise ValueError("fichier d'itinéraires invalide")
        fields = struct.unpack(HEADER_FORMAT, data[: struct.calcsize(HEADER_FORMAT)])
        (_, self.version, self.nodes, self.edges, self.arc_count, self.place_count,
         o_places, s_places, o_lat, o_lon, o_flags, o_arc_start, o_arcs,
         o_from, o_to, o_len, o_attr, o_geom_off, o_geom, s_geom) = fields
        self.data = data

        def read(typecode, offset, count):
            values = array(typecode)
            values.frombytes(data[offset: offset + count * values.itemsize])
            if sys.byteorder == "big":
                values.byteswap()
            return values

        self.lat = read("i", o_lat, self.nodes)
        self.lon = read("i", o_lon, self.nodes)
        self.flags = data[o_flags: o_flags + self.nodes]
        self.arc_start = read("I", o_arc_start, self.nodes + 1)
        self.arcs = read("I", o_arcs, self.arc_count)
        self.edge_from = read("I", o_from, self.edges)
        self.edge_to = read("I", o_to, self.edges)
        self.edge_length = read("I", o_len, self.edges)
        self.edge_attr = read("H", o_attr, self.edges)
        self.edge_geom = read("I", o_geom_off, self.edges)
        self.geometry = data[o_geom: o_geom + s_geom]
        self.places = self._read_places(o_places, s_places)

    def _read_places(self, offset, size):
        position, end, places = offset, offset + size, []
        while position < end:
            lat, lon, population, kind = struct.unpack_from("<iiIB", self.data, position)
            position += 13
            strings = []
            for _ in range(3):
                length = self.data[position]
                strings.append(self.data[position + 1: position + 1 + length].decode("utf-8"))
                position += 1 + length
            places.append((kind, population, lat, lon, *strings))
        return places

    def edge_points(self, edge, reverse=False):
        """Full polyline of an edge as (lat, lon) e6 pairs, in the direction of travel."""
        position = self.edge_geom[edge]

        def varint():
            nonlocal position
            shift = result = 0
            while True:
                byte = self.geometry[position]
                position += 1
                result |= (byte & 0x7F) << shift
                if byte < 0x80:
                    return result
                shift += 7

        count = varint()
        lat, lon = self.lat[self.edge_from[edge]], self.lon[self.edge_from[edge]]
        points = [(lat, lon)]
        for _ in range(count):
            d_lat, d_lon = varint(), varint()
            lat += (d_lat >> 1) ^ -(d_lat & 1)
            lon += (d_lon >> 1) ^ -(d_lon & 1)
            points.append((lat, lon))
        points.append((self.lat[self.edge_to[edge]], self.lon[self.edge_to[edge]]))
        return points[::-1] if reverse else points

    def nearest_node(self, lat, lon, main_only=True):
        """Nearest node to a position given in degrees."""
        lat, lon = round(lat * 1e6), round(lon * 1e6)
        best, best_distance = -1, float("inf")
        for n in range(self.nodes):
            if main_only and not self.flags[n]:
                continue
            distance = segment_metres(lat, lon, self.lat[n], self.lon[n])
            if distance < best_distance:
                best, best_distance = n, distance
        return best

    def shortest(self, start, goal):
        """Plain Dijkstra on travel time; returns (seconds, [(edge, direction), ...]) or None."""
        import heapq
        best = {start: 0.0}
        parent = {}
        queue = [(0.0, start)]
        done = set()
        while queue:
            cost, node = heapq.heappop(queue)
            if node in done:
                continue
            done.add(node)
            if node == goal:
                path = []
                while node != start:
                    code = parent[node]
                    path.append((code >> 1, code & 1))
                    edge = code >> 1
                    node = self.edge_from[edge] if code & 1 == 0 else self.edge_to[edge]
                return cost, path[::-1]
            for a in range(self.arc_start[node], self.arc_start[node + 1]):
                code = self.arcs[a]
                edge = code >> 1
                target = self.edge_to[edge] if code & 1 == 0 else self.edge_from[edge]
                speed = self.edge_attr[edge] >> 3
                seconds = self.edge_length[edge] / 10.0 / (speed / 3.6)
                if target not in best or cost + seconds < best[target]:
                    best[target] = cost + seconds
                    parent[target] = code
                    heapq.heappush(queue, (cost + seconds, target))
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roads", required=True, type=Path)
    parser.add_argument("--places", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="file to write (.gz is compressed)")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    data, graph, places, ways, skipped, largest = build(args.roads, args.places)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.suffix == ".gz":
        with gzip.open(args.output, "wb", compresslevel=9) as writer:
            writer.write(data)
    else:
        args.output.write_bytes(data)
    text = report(data, graph, places, ways, skipped, largest, args.output.stat().st_size)
    print(text)
    if args.report:
        args.report.write_text(text + "\n", encoding="utf-8")
    if len(graph.edge_from) == 0:
        print("Aucun tronçon : vérifiez le fichier des routes.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
