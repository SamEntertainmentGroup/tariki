#!/usr/bin/env python3
"""Print a size report for an MBTiles file and fail if it looks wrong."""

import argparse
import json
import sqlite3
import sys
from pathlib import Path

EXPECTED_LAYERS = ("roads", "places")


def mib(size):
    return f"{size / (1 << 20):.2f} Mo"


def inspect(path):
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        metadata = dict(connection.execute("SELECT name, value FROM metadata"))
        per_zoom = connection.execute(
            "SELECT zoom_level, COUNT(*), SUM(LENGTH(tile_data)) FROM tiles "
            "GROUP BY zoom_level ORDER BY zoom_level"
        ).fetchall()
    finally:
        connection.close()

    layers = []
    if metadata.get("json"):
        layers = [layer["id"] for layer in json.loads(metadata["json"]).get("vector_layers", [])]

    problems = []
    if not per_zoom:
        problems.append("aucune tuile dans le fichier")
    missing = [layer for layer in EXPECTED_LAYERS if layer not in layers]
    if missing:
        problems.append("couches absentes : " + ", ".join(missing))

    lines = [
        "=== RAPPORT DE LA CARTE ===",
        f"Fichier : {path.name} ({path.stat().st_size:,} octets, {mib(path.stat().st_size)})",
        f"Format : {metadata.get('format', '?')} | zooms {metadata.get('minzoom', '?')} à {metadata.get('maxzoom', '?')}",
        f"Zone : {metadata.get('bounds', '?')}",
        f"Couches : {', '.join(layers) or 'aucune'}",
        "Tuiles par niveau de zoom (nombre, poids) :",
        *(f"  zoom {zoom:>2} : {count:>9,} tuiles, {mib(size or 0)}" for zoom, count, size in per_zoom),
    ]
    lines.append("Contrôles : " + ("ÉCHEC - " + " ; ".join(problems) if problems else "OK"))
    return "\n".join(lines), not problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mbtiles", type=Path)
    args = parser.parse_args()
    if not args.mbtiles.is_file():
        parser.error(f"Fichier introuvable : {args.mbtiles}")
    report, ok = inspect(args.mbtiles)
    print(report)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
