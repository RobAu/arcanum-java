#!/usr/bin/env python3
"""Generate a SYNTHETIC .dat fixture for the Java DatArchive test.

Builds a tiny, license-safe TIG archive (our own content, safe to commit) with
one PLAIN entry and one zlib-COMPRESSED entry plus a directory entry, then
round-trips it through the reference reader (dat_extract.py) to confirm and to
emit golden expectations. Mirrors c2java/tools/make_art_fixture.py.
"""

from __future__ import annotations

import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(__file__))
import dat_extract  # noqa: E402

HERE = os.path.dirname(__file__)
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(REPO, "java-port", "src", "test", "resources", "dat",
                   "synthetic.dat")

PLAIN_NAME = "data\\hello.txt"
PLAIN_BODY = b"hello arcanum\n"
COMP_NAME = "data\\big.txt"
COMP_BODY = (b"the quick brown fox jumps over the lazy dog. " * 16)
DIR_NAME = "data"


def _entry_record(name: str, flags: int, size: int, csize: int, off: int) -> bytes:
    raw = name.encode("latin1") + b"\x00"
    return (struct.pack("<i", len(raw)) + raw
            + struct.pack("<i", 0)               # reserved
            + struct.pack("<IIIi", flags, size, csize, off))


def build_dat() -> bytes:
    # Data section: concatenated entry blobs at the front (base_offset = 0).
    data = bytearray()
    plain_off = len(data)
    data += PLAIN_BODY
    comp_blob = zlib.compress(COMP_BODY, 9)
    comp_off = len(data)
    data += comp_blob

    # Entry table body: entries_count + records.
    entries = [
        _entry_record(DIR_NAME, dat_extract.FLAG_DIRECTORY, 0, 0, 0),
        _entry_record(PLAIN_NAME, dat_extract.FLAG_PLAIN,
                      len(PLAIN_BODY), len(PLAIN_BODY), plain_off),
        _entry_record(COMP_NAME, dat_extract.FLAG_COMPRESSED,
                      len(COMP_BODY), len(comp_blob), comp_off),
    ]
    body = struct.pack("<I", len(entries)) + b"".join(entries)

    # Layout (base_offset must come out 0 so entry offsets are absolute):
    #   [0 .. D)            data section            (D = len(data))
    #   [D .. D+4)          entry_table_size i32
    #   [D+4 .. D+4+B)      body (entries_count + records, B = len(body))
    #   [.. +12)            trailer
    # The reader computes: ets_pos = size-4-eto, reads entry_table_size there,
    # reads entries_count at size-eto, base_offset = size-entry_table_size-eto.
    # Solving for base_offset == 0 and entries_count at D+4 gives:
    #   eto = B + 12 ;  entry_table_size = D + 4
    D = len(data)
    B = len(body)
    entry_table_size = D + 4
    eto = B + 12
    name_table_size = 0  # informational only

    blob = bytes(data)
    blob += struct.pack("<i", entry_table_size)
    blob += body
    blob += dat_extract.FOURCC_DAT              # ' TAD'
    blob += struct.pack("<i", name_table_size)
    blob += struct.pack("<i", eto)
    return blob


def main() -> int:
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    blob = build_dat()
    with open(OUT, "wb") as f:
        f.write(blob)

    a = dat_extract.DatArchive(OUT)             # round-trip through reference
    assert a.read("data/hello.txt") == PLAIN_BODY
    assert a.read("data/big.txt") == COMP_BODY
    print("wrote %s (%d bytes)" % (OUT, len(blob)))
    print("  entries: %s" % sorted(a.entries))
    print("  hello.txt crc=%08X  big.txt crc=%08X"
          % (zlib.crc32(PLAIN_BODY) & 0xFFFFFFFF,
             zlib.crc32(COMP_BODY) & 0xFFFFFFFF))
    print("  big.txt size=%d" % len(COMP_BODY))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
