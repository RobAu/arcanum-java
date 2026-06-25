#!/usr/bin/env python3
"""Reader/extractor for Arcanum .dat archives (the TIG database format).

Reference implementation used to validate the format before the Java port (see
java-port/ART_PORT_PLAN.md and TigDatabase). Recovered from
first_party/tig/src/database.c (tig_database_open, fopen_internal).

Format (all little-endian int32). The archive is read from the END:

  Trailer (last 12 bytes):
    [size-12]  fourcc   ' TAD' (FOURCC_DAT) or '1TAD' (FOURCC_DAT1)
    [size- 8]  name_table_size      (informational; not needed to parse)
    [size- 4]  entry_table_offset

  Entry table (at  size - 4 - entry_table_offset):
    i32 entry_table_size
    u32 entries_count
    repeated entries_count times:
      i32 name_size
      u8  name[name_size]    (Windows path, NUL-terminated, '\\' separators)
      i32 _reserved          (skipped)
      u32 flags              0x01 PLAIN, 0x02 COMPRESSED(zlib), 0x400 DIRECTORY
      u32 size               (uncompressed)
      u32 compressed_size
      i32 offset             (relative; add base_offset)

  base_offset = size - entry_table_size - entry_table_offset
  Data for an entry lives at  base_offset + offset:
    COMPRESSED -> compressed_size bytes, zlib-inflate to `size`
    PLAIN      -> `size` raw bytes

Usage:
    dat_extract.py list    <archive.dat> [glob]
    dat_extract.py cat     <archive.dat> <internal/path>   # raw bytes to stdout
    dat_extract.py extract <archive.dat> <outdir> [glob]
    dat_extract.py verify  <archive.dat>                   # decompress everything
"""

from __future__ import annotations

import fnmatch
import os
import struct
import sys
import zlib
from dataclasses import dataclass

FOURCC_DAT = b" TAD"
FOURCC_DAT1 = b"1TAD"

FLAG_PLAIN = 0x01
FLAG_COMPRESSED = 0x02
FLAG_DIRECTORY = 0x400
FLAG_IGNORED = 0x800


@dataclass
class Entry:
    path: str          # normalized: lowercase, '/' separators
    flags: int
    size: int
    compressed_size: int
    offset: int        # absolute offset into the archive

    @property
    def is_dir(self) -> bool:
        return bool(self.flags & FLAG_DIRECTORY)

    @property
    def is_compressed(self) -> bool:
        return bool(self.flags & FLAG_COMPRESSED)


class DatArchive:
    def __init__(self, path: str):
        self.path = path
        with open(path, "rb") as f:
            self.data = f.read()
        self.entries: dict[str, Entry] = {}
        self._parse()

    def _parse(self) -> None:
        d = self.data
        size = len(d)
        if size < 12:
            raise ValueError("file too small to be a .dat")
        fourcc = bytes(d[size - 12:size - 8])
        if fourcc not in (FOURCC_DAT, FOURCC_DAT1):
            raise ValueError("bad magic %r (not a TIG .dat)" % fourcc)
        self.fourcc = fourcc
        entry_table_offset = struct.unpack_from("<i", d, size - 4)[0]
        ets_pos = size - 4 - entry_table_offset
        entry_table_size = struct.unpack_from("<i", d, ets_pos)[0]
        base_offset = size - entry_table_size - entry_table_offset

        q = ets_pos + 4
        entries_count = struct.unpack_from("<I", d, q)[0]
        q += 4
        for _ in range(entries_count):
            name_size = struct.unpack_from("<i", d, q)[0]
            q += 4
            raw_name = d[q:q + name_size]
            q += name_size
            q += 4  # reserved
            flags, usize, csize, off = struct.unpack_from("<IIIi", d, q)
            q += 16
            name = (raw_name.rstrip(b"\x00").decode("latin1")
                    .replace("\\", "/").lower())
            self.entries[name] = Entry(name, flags, usize, csize,
                                       off + base_offset)

    def files(self) -> list[Entry]:
        return [e for e in self.entries.values() if not e.is_dir]

    def read(self, internal_path: str) -> bytes:
        key = internal_path.replace("\\", "/").lower()
        e = self.entries.get(key)
        if e is None:
            raise KeyError(internal_path)
        return self.read_entry(e)

    def read_entry(self, e: Entry) -> bytes:
        if e.is_dir:
            return b""
        blob = self.data[e.offset:e.offset + (e.compressed_size
                                              if e.is_compressed else e.size)]
        if e.is_compressed:
            out = zlib.decompress(blob)
            if len(out) != e.size:
                raise ValueError("size mismatch for %s: got %d, expected %d"
                                 % (e.path, len(out), e.size))
            return out
        return blob


# -- CLI ---------------------------------------------------------------------
def _cmd_list(archive: str, pattern: str = "*") -> int:
    a = DatArchive(archive)
    n = 0
    for e in sorted(a.files(), key=lambda e: e.path):
        if fnmatch.fnmatch(e.path, pattern.lower()):
            kind = "zlib" if e.is_compressed else "raw "
            print("%s  %8d  %s" % (kind, e.size, e.path))
            n += 1
    print("# %d file(s) (%d total entries) in %s [%s]"
          % (n, len(a.entries), os.path.basename(archive), a.fourcc.decode()))
    return 0


def _cmd_cat(archive: str, internal: str) -> int:
    a = DatArchive(archive)
    sys.stdout.buffer.write(a.read(internal))
    return 0


def _cmd_extract(archive: str, outdir: str, pattern: str = "*") -> int:
    a = DatArchive(archive)
    n = 0
    for e in a.files():
        if not fnmatch.fnmatch(e.path, pattern.lower()):
            continue
        dst = os.path.join(outdir, e.path)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "wb") as f:
            f.write(a.read_entry(e))
        n += 1
    print("extracted %d file(s) to %s" % (n, outdir))
    return 0


def _cmd_verify(archive: str) -> int:
    a = DatArchive(archive)
    ok = 0
    total = 0
    failed = []
    for e in a.files():
        total += 1
        try:
            blob = a.read_entry(e)
            if len(blob) != e.size:
                raise ValueError("len %d != %d" % (len(blob), e.size))
            ok += 1
        except Exception as ex:  # noqa: BLE001
            failed.append((e.path, str(ex)))
    print("verified %d/%d file(s) in %s"
          % (ok, total, os.path.basename(archive)))
    for p, err in failed[:20]:
        print("  FAIL %s -- %s" % (p, err))
    return 0 if not failed else 1


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cmd = argv[0]
    try:
        if cmd == "list" and len(argv) >= 2:
            return _cmd_list(argv[1], argv[2] if len(argv) > 2 else "*")
        if cmd == "cat" and len(argv) == 3:
            return _cmd_cat(argv[1], argv[2])
        if cmd == "extract" and len(argv) >= 3:
            return _cmd_extract(argv[1], argv[2], argv[3] if len(argv) > 3 else "*")
        if cmd == "verify" and len(argv) == 2:
            return _cmd_verify(argv[1])
    except (ValueError, KeyError, OSError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
