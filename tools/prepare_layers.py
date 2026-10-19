#!/usr/bin/env python3
"""Turn osmium GeoJSON sequences into lean tippecanoe inputs (roads, places, borders, coast)."""

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
PLACE_MIN_ZOOM = {"city": 4, "town": 5, "village": 6, "hamlet": 8, "suburb": 9}
BORDER_MIN_ZOOM = {"2": 4, "4": 6, "8": 10}
COAST_MIN_ZOOM = 4
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


def line_positions(geometry):
    if geometry.get("type") != "LineString":
        return None, "géométrie non linéaire"
    positions = geometry.get("coordinates")
    if not isinstance(positions, list) or len(positions) < 2:
        return None, "géométrie trop courte"
    if not all(valid_position(position) for position in positions):
        return None, "coordonnées invalides"
    return [rounded(position) for position in positions], None


def road_feature(feature):
    properties = feature.get("properties") or {}
    geometry = feature.get("geometry") or {}
    highway = properties.get("highway")
    is_link = isinstance(highway, str) and highway.endswith("_link")
    base = highway[: -len("_link")] if is_link else highway
    if base not in ROAD_CLASSES:
        return None, "type de route non retenu"
    positions, problem = line_positions(geometry)
    if problem:
        return None, problem

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
        "geometry": {"type": "LineString", "coordinates": positions},
        "tippecanoe": {"minzoom": min_zoom},
    }, road_class


def population_value(value):
    digits = "".join(char for char in clean_text(value) if char.isdigit())
    return int(digits) if digits else 0


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
    population = population_value(properties.get("population"))
    if population > 0:
        output["population"] = population
    return {
        "type": "Feature",
        "properties": output,
        "geometry": {"type": "Point", "coordinates": rounded(geometry["coordinates"])},
        "tippecanoe": {"minzoom": PLACE_MIN_ZOOM[place]},
    }, place


def border_feature(feature):
    properties = feature.get("properties") or {}
    if properties.get("boundary") != "administrative":
        return None, "limite non administrative"
    if properties.get("maritime") == "yes":
        return None, "limite maritime"
    level = clean_text(properties.get("admin_level"))
    if level not in BORDER_MIN_ZOOM:
        return None, "niveau administratif non retenu"
    positions, problem = line_positions(feature.get("geometry") or {})
    if problem:
        return None, problem
    return {
        "type": "Feature",
        "properties": {"level": int(level)},
        "geometry": {"type": "LineString", "coordinates": positions},
        "tippecanoe": {"minzoom": BORDER_MIN_ZOOM[level]},
    }, f"niveau {level}"


def coast_feature(feature):
    properties = feature.get("properties") or {}
    if properties.get("natural") != "coastline":
        return None, "pas une côte"
    positions, problem = line_positions(feature.get("geometry") or {})
    if problem:
        return None, problem
    return {
        "type": "Feature",
        "properties": {},
        "geometry": {"type": "LineString", "coordinates": positions},
        "tippecanoe": {"minzoom": COAST_MIN_ZOOM},
    }, "côte"


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


LAYERS = (
    ("roads", "Routes", road_feature, True),
    ("places", "Lieux", place_feature, True),
    ("borders", "Limites", border_feature, False),
    ("coast", "Côte", coast_feature, False),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name, _, _, _ in LAYERS:
        parser.add_argument(f"--{name}-in", type=Path)
        parser.add_argument(f"--{name}-out", type=Path)
    args = parser.parse_args()

    failed = False
    for name, title, converter, required in LAYERS:
        source, target = getattr(args, f"{name}_in"), getattr(args, f"{name}_out")
        if source is None and target is None and not required:
            continue
        if source is None or target is None:
            parser.error(f"--{name}-in et --{name}-out vont ensemble")
        if not source.is_file():
            parser.error(f"Fichier introuvable : {source}")
        kept, skipped = convert(source, target, converter)
        print(describe(title, kept, skipped))
        if required and not kept:
            print(f"ÉCHEC : aucun élément conservé pour {title.lower()}", file=sys.stderr)
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
