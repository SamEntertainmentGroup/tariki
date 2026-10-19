#!/usr/bin/env python3
"""Build the compact place index used by the app search from an osmium GeoJSON sequence."""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

import prepare_layers

CLASS_ORDER = ("city", "town", "village", "suburb", "hamlet")
COORDINATE_DECIMALS = 5


def index_row(feature):
    converted, label = prepare_layers.place_feature(feature)
    if converted is None:
        return None, label
    properties = converted["properties"]
    longitude, latitude = converted["geometry"]["coordinates"]
    return [
        properties.get("name", ""),
        properties.get("name_ar", ""),
        properties.get("name_fr", ""),
        properties["class"],
        round(latitude, COORDINATE_DECIMALS),
        round(longitude, COORDINATE_DECIMALS),
    ], label


def read_rows(path):
    kept = Counter()
    skipped = Counter()
    rows = []
    with open(path, "r", encoding="utf-8") as source:
        for line in source:
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
            row, label = index_row(feature)
            if row is None:
                skipped[label] += 1
                continue
            kept[label] += 1
            rows.append(row)
    rows.sort(key=lambda row: (CLASS_ORDER.index(row[3]), row[1] or row[2] or row[0]))
    return rows, kept, skipped


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="GeoJSON sequence of place nodes")
    parser.add_argument("--output", required=True, type=Path, help="places-index.json to write")
    args = parser.parse_args(argv)
    if not args.input.is_file():
        parser.error(f"Fichier introuvable : {args.input}")

    rows, kept, skipped = read_rows(args.input)
    if not rows:
        print("ÉCHEC : aucun lieu dans l'index", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps({"version": 1, "places": rows}, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    print(f"Index de recherche : {len(rows):,} lieux, {args.output.stat().st_size:,} octets")
    for name, count in sorted(kept.items()):
        print(f"  {name} : {count:,}")
    for reason, count in sorted(skipped.items()):
        print(f"  ignorés ({reason}) : {count:,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
