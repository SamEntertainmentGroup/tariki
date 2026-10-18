#!/usr/bin/env python3
"""Turn osmium GeoJSON sequences into lean tippecanoe inputs (roads and place names)."""

import argparse
import json
import math
import sys
from collections import Counter
from pathlib import Path

ROAD_CLASSES = {
    "motorway": ("motorway", 5),
    "trunk": ("trunk", 5),
    "primary": ("primary", 7),
    "secondary": ("secondary", 9),
    "tertiary": ("tertiary", 11),
    "unclassified": ("minor", 12),
    "residential": ("minor", 12),
}
LINK_MIN_ZOOM = 10
LINK_ZOOM_OFFSET = 2
PLACE_MIN_ZOOM = {"city": 4, "town": 7, "village": 10, "hamlet": 12, "suburb": 12}
COORDINATE_DECIMALS = 6


def valid_position(position):
    if not isinstance(position, (list, tuple)) or len(position) < 2:
        return False
    lon, lat = position[0], position[1]
    for value in (lon, lat):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            return False
    return -180 <= lon <= 180 and -90 <= lat <= 90


def rounded(position):
    return [round(position[0], COORDINATE_DECIMALS), round(position[1], COORDINATE_DECIMALS)]


def clean_text(value):
    return " ".join(value.split()) if isinstance(value, str) else ""


def road_feature(feature):
    properties = feature.get("properties") or {}
    geometry = feature.get("geometry") or {}
    highway = properties.get("highway")
    is_link = isinstance(highway, str) and highway.endswith("_link")
    base = highway[: -len("_link")] if is_link else highway
    if base not in ROAD_CLASSES:
        return None, "type de route non retenu"
    if geometry.get("type") != "LineString":
        return None, "géométrie non linéaire"
    positions = geometry.get("coordinates")
    if not isinstance(positions, list) or len(positions) < 2:
        return None, "géométrie trop courte"
    if not all(valid_position(position) for position in positions):
        return None, "coordonnées invalides"

    road_class, min_zoom = ROAD_CLASSES[base]
    if is_link:
        min_zoom = max(min_zoom + LINK_ZOOM_OFFSET, LINK_MIN_ZOOM)
    output = {"class": road_class}
    ref = clean_text(properties.get("ref"))
    if ref:
        output["ref"] = ref
    return {
        "type": "Feature",
        "properties": output,
        "geometry": {"type": "LineString", "coordinates": [rounded(p) for p in positions]},
        "tippecanoe": {"minzoom": min_zoom},
    }, road_class


def place_feature(feature):
    properties = feature.get("properties") or {}
    geometry = feature.get("geometry") or {}
    place = properties.get("place")
    if place not in PLACE_MIN_ZOOM:
        return None, "type de lieu non retenu"
    if geometry.get("type") != "Point" or not valid_position(geometry.get("coordinates")):
        return None, "coordonnées invalides"

    name = clean_text(properties.get("name"))
    name_ar = clean_text(properties.get("name:ar"))
    name_fr = clean_text(properties.get("name:fr"))
    if not (name or name_ar or name_fr):
        return None, "lieu sans nom"

    output = {"class": place}
    for key, value in (("name", name), ("name_ar", name_ar), ("name_fr", name_fr)):
        if value:
            output[key] = value
    return {
        "type": "Feature",
        "properties": output,
        "geometry": {"type": "Point", "coordinates": rounded(geometry["coordinates"])},
        "tippecanoe": {"minzoom": PLACE_MIN_ZOOM[place]},
    }, place


def convert(source, target, converter):
    kept = Counter()
    skipped = Counter()
    with open(source, "r", encoding="utf-8") as reader, open(target, "w", encoding="utf-8") as writer:
        for line in reader:
            line = line.strip("\x1e \t\r\n")
            if not line:
                continue
            try:
                feature = json.loads(line)
            except ValueError:
                skipped["ligne JSON illisible"] += 1
                continue
            if not isinstance(feature, dict):
                skipped["ligne JSON illisible"] += 1
                continue
            converted, label = converter(feature)
            if converted is None:
                skipped[label] += 1
                continue
            kept[label] += 1
            writer.write(json.dumps(converted, ensure_ascii=False, separators=(",", ":")) + "\n")
    return kept, skipped


def describe(title, kept, skipped):
    lines = [f"{title} : {sum(kept.values()):,} conservés"]
    lines += [f"  {name} : {count:,}" for name, count in sorted(kept.items())]
    lines += [f"  ignorés ({reason}) : {count:,}" for reason, count in sorted(skipped.items())]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--roads-in", required=True, type=Path)
    parser.add_argument("--roads-out", required=True, type=Path)
    parser.add_argument("--places-in", required=True, type=Path)
    parser.add_argument("--places-out", required=True, type=Path)
    args = parser.parse_args()

    for path in (args.roads_in, args.places_in):
        if not path.is_file():
            parser.error(f"Fichier introuvable : {path}")

    roads_kept, roads_skipped = convert(args.roads_in, args.roads_out, road_feature)
    places_kept, places_skipped = convert(args.places_in, args.places_out, place_feature)
    print(describe("Routes", roads_kept, roads_skipped))
    print(describe("Lieux", places_kept, places_skipped))

    if not roads_kept or not places_kept:
        print("ÉCHEC : aucune route ou aucun lieu conservé", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
