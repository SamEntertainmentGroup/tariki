#!/usr/bin/env python3
"""Download glyph PBF ranges for offline map labels and report Arabic coverage."""

import argparse
import gzip
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE_URL = "https://protomaps.github.io/basemaps-assets/fonts"
SOURCE_FONT = "Noto Sans Regular"
TARGET_FONT = "NotoSans-Regular"
RANGES = (
    (0, 255), (256, 511), (512, 767), (768, 1023),
    (1536, 1791), (1792, 2047), (2048, 2303),
    (8192, 8447),
    (64256, 64511), (64512, 64767), (64768, 65023), (65024, 65279), (65280, 65535),
)
LATIN_RANGE = (0, 255)
ARABIC_BASE_LETTERS = {0x0627, 0x0644, 0x0645, 0x0628}
ARABIC_SHAPED_FORMS = {0xFE8D, 0xFE8E, 0xFEDD, 0xFEDF, 0xFEE0, 0xFEFB}
RETRIES = 3
TIMEOUT_SECONDS = 60


class GlyphFormatError(ValueError):
    pass


def read_varint(data, position):
    result = shift = 0
    while True:
        if position >= len(data):
            raise GlyphFormatError("PBF tronqué")
        byte = data[position]
        position += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, position
        shift += 7


def read_fields(data):
    position = 0
    while position < len(data):
        key, position = read_varint(data, position)
        field, wire_type = key >> 3, key & 7
        if wire_type == 0:
            value, position = read_varint(data, position)
        elif wire_type == 2:
            length, position = read_varint(data, position)
            if position + length > len(data):
                raise GlyphFormatError("PBF tronqué")
            value = data[position:position + length]
            position += length
        elif wire_type == 1:
            value, position = None, position + 8
        elif wire_type == 5:
            value, position = None, position + 4
        else:
            raise GlyphFormatError(f"Type de champ inconnu : {wire_type}")
        yield field, wire_type, value


def glyph_ids(data):
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    ids = set()
    for field, wire_type, stack in read_fields(data):
        if field != 1 or wire_type != 2:
            continue
        for stack_field, stack_wire, glyph in read_fields(stack):
            if stack_field != 3 or stack_wire != 2:
                continue
            for glyph_field, glyph_wire, value in read_fields(glyph):
                if glyph_field == 1 and glyph_wire == 0:
                    ids.add(value)
    return ids


def arabic_coverage(ids):
    missing_letters = sorted(ARABIC_BASE_LETTERS - ids)
    missing_forms = sorted(ARABIC_SHAPED_FORMS - ids)
    return missing_letters, missing_forms


def fetch(url):
    last_error = None
    for attempt in range(RETRIES):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "dz-carte-build"})
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            last_error = error
        except (urllib.error.URLError, TimeoutError) as error:
            last_error = error
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"Téléchargement impossible : {url} ({last_error})")


def hex_list(codes):
    return ", ".join(f"U+{code:04X}" for code in codes)


def run(output):
    folder = output / TARGET_FONT
    folder.mkdir(parents=True, exist_ok=True)
    all_ids = set()
    lines = [f"=== POLICE {SOURCE_FONT} ==="]
    latin_ok = False

    for start, end in RANGES:
        name = f"{start}-{end}.pbf"
        url = f"{BASE_URL}/{urllib.parse.quote(SOURCE_FONT)}/{name}"
        data = fetch(url)
        if data is None:
            lines.append(f"  {name} : absent")
            continue
        if data[:2] == b"\x1f\x8b":
            data = gzip.decompress(data)
        ids = glyph_ids(data)
        (folder / name).write_bytes(data)
        all_ids |= ids
        lines.append(f"  {name} : {len(ids)} glyphes, {len(data):,} octets")
        if (start, end) == LATIN_RANGE and ids:
            latin_ok = True

    missing_letters, missing_forms = arabic_coverage(all_ids)
    if not missing_letters and not missing_forms:
        lines.append("Arabe : OK (lettres et formes jointes présentes)")
    else:
        details = []
        if missing_letters:
            details.append("lettres manquantes " + hex_list(missing_letters))
        if missing_forms:
            details.append("formes jointes manquantes " + hex_list(missing_forms))
        lines.append("Arabe : INCOMPLET (" + " ; ".join(details) + ")")
    lines.append("Latin : " + ("OK" if latin_ok else "ÉCHEC"))
    return latin_ok, "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="Directory that receives the font folder")
    args = parser.parse_args(argv)
    try:
        latin_ok, report = run(args.output)
    except (RuntimeError, GlyphFormatError) as error:
        print(f"ÉCHEC : {error}", file=sys.stderr)
        return 1
    print(report)
    return 0 if latin_ok else 1


if __name__ == "__main__":
    sys.exit(main())
