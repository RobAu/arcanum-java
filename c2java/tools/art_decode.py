#!/usr/bin/env python3
"""Reference decoder for Arcanum .ART sprite/animation files.

This is the Python *reference* implementation used to validate the binary format
before porting it to Java (see java-port/ART_PORT_PLAN.md). The format was
recovered from first_party/tig/src/art.c (art_read_header, tig_art_cache_entry_load).

Format (little-endian):
  Header (132 bytes):
    u32 flags            bit 0x01 set -> 1 rotation, else 8
    i32 fps
    i32 bpp              must be 8 (palette-indexed)
    i32 palette_present[4]  nonzero slot -> 256-entry palette follows
    i32 action_frame
    i32 num_frames
    i32 _frames_ptrs[8]  (skipped)
    i32 data_size[8]
    i32 _pixel_ptrs[8]   (skipped)
  Palettes:  for each present slot: 256 x u32, each 0x00RRGGBB. Index 0 = colorkey.
  Frames:    num_rotations blocks of num_frames x FrameData(28 bytes):
             i32 width,height,data_size,hot_x,hot_y,offset_x,offset_y
  Pixels:    num_rotations blocks; per frame width*height palette indices:
             data_size==w*h -> raw; else RLE:
               value=u8; len=value&0x7F;
               value&0x80 -> copy next len bytes (literal); cnt += 1+len
               else       -> next byte color, write color x len;  cnt += 2

Usage:
    art_decode.py info   <file.art>
    art_decode.py dump   <file.art> <outdir>     # PNG per (rotation,frame)
    art_decode.py verify <dir> [limit]           # batch decode, report failures
"""

from __future__ import annotations

import os
import struct
import sys
import zlib
from dataclasses import dataclass, field

HEADER_SIZE = 132
FRAME_REC = 28
MAX_PALETTES = 4
MAX_ROTATIONS = 8


class ArtError(Exception):
    pass


@dataclass
class Frame:
    width: int
    height: int
    data_size: int
    hot_x: int
    hot_y: int
    offset_x: int
    offset_y: int
    indices: bytes = b""  # width*height palette indices (filled by decode)


@dataclass
class ArtFile:
    flags: int
    fps: int
    bpp: int
    action_frame: int
    num_frames: int
    num_rotations: int
    palette_present: list[int]
    palettes: dict[int, list[int]] = field(default_factory=dict)  # slot -> [0xRRGGBB]*256
    frames: list[list[Frame]] = field(default_factory=list)       # [rotation][frame]

    def first_palette_slot(self) -> int:
        for i, p in enumerate(self.palette_present):
            if p:
                return i
        return -1


class _Reader:
    def __init__(self, data: bytes):
        self.d = data
        self.p = 0

    def i32(self) -> int:
        v = struct.unpack_from("<i", self.d, self.p)[0]
        self.p += 4
        return v

    def u32(self) -> int:
        v = struct.unpack_from("<I", self.d, self.p)[0]
        self.p += 4
        return v

    def u8(self) -> int:
        v = self.d[self.p]
        self.p += 1
        return v

    def take(self, n: int) -> bytes:
        if self.p + n > len(self.d):
            raise ArtError("unexpected EOF (need %d at %d of %d)"
                           % (n, self.p, len(self.d)))
        b = self.d[self.p:self.p + n]
        self.p += n
        return b


def decode(data: bytes) -> ArtFile:
    r = _Reader(data)
    flags = r.u32()
    fps = r.i32()
    bpp = r.i32()
    if bpp != 8:
        raise ArtError("unsupported bpp=%d (only 8 is supported)" % bpp)
    palette_present = [r.i32() for _ in range(MAX_PALETTES)]
    action_frame = r.i32()
    num_frames = r.i32()
    r.p += 4 * MAX_ROTATIONS          # skip frame-table pointers
    _data_size = [r.i32() for _ in range(MAX_ROTATIONS)]
    r.p += 4 * MAX_ROTATIONS          # skip pixel-table pointers
    assert r.p == HEADER_SIZE, (r.p, HEADER_SIZE)

    num_rotations = 1 if (flags & 0x01) else MAX_ROTATIONS

    art = ArtFile(flags, fps, bpp, action_frame, num_frames, num_rotations,
                  palette_present)

    # Palettes (only for present slots, in slot order).
    for slot in range(MAX_PALETTES):
        if palette_present[slot]:
            pal = []
            for _ in range(256):
                v = r.u32()
                pal.append(v & 0x00FFFFFF)   # 0x00RRGGBB
            art.palettes[slot] = pal

    # Frame tables.
    for _rot in range(num_rotations):
        block = []
        for _f in range(num_frames):
            w, h, ds, hx, hy, ox, oy = struct.unpack_from("<7i", r.d, r.p)
            r.p += FRAME_REC
            block.append(Frame(w, h, ds, hx, hy, ox, oy))
        art.frames.append(block)

    # Pixel data.
    for rot in range(num_rotations):
        for fr in art.frames[rot]:
            fr.indices = _decode_frame_pixels(r, fr)

    return art


def _decode_frame_pixels(r: _Reader, fr: Frame) -> bytes:
    n = fr.width * fr.height
    if fr.data_size == n:
        return r.take(n)                      # raw / uncompressed
    if fr.data_size <= 0:
        return bytes(n)                        # empty frame
    out = bytearray(n)
    pos = 0
    cnt = 0
    while cnt < fr.data_size:
        value = r.u8()
        length = value & 0x7F
        if value & 0x80:                       # literal run
            chunk = r.take(length)
            out[pos:pos + length] = chunk
            cnt += 1 + length
        else:                                  # color run
            color = r.u8()
            out[pos:pos + length] = bytes([color]) * length
            cnt += 2
        pos += length
    if pos != n:
        raise ArtError("RLE produced %d px, expected %d (%dx%d)"
                       % (pos, n, fr.width, fr.height))
    return bytes(out)


# -- PNG output (dependency-free, via stdlib zlib) ---------------------------
def _write_png(path: str, width: int, height: int, rgba: bytes) -> None:
    def chunk(tag: bytes, body: bytes) -> bytes:
        return (struct.pack(">I", len(body)) + tag + body
                + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF))

    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0
        raw += rgba[y * width * 4:(y + 1) * width * 4]
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8-bit RGBA
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def frame_to_rgba(art: ArtFile, frame: Frame, slot: int) -> bytes:
    pal = art.palettes[slot]
    out = bytearray(frame.width * frame.height * 4)
    for i, idx in enumerate(frame.indices):
        o = i * 4
        if idx == 0:                           # colorkey -> transparent
            continue
        rgb = pal[idx]
        out[o] = (rgb >> 16) & 0xFF
        out[o + 1] = (rgb >> 8) & 0xFF
        out[o + 2] = rgb & 0xFF
        out[o + 3] = 0xFF
    return bytes(out)


# -- CLI ---------------------------------------------------------------------
def _cmd_info(path: str) -> int:
    art = decode(open(path, "rb").read())
    print("%s" % path)
    print("  flags=0x%X fps=%d bpp=%d action_frame=%d"
          % (art.flags, art.fps, art.bpp, art.action_frame))
    print("  rotations=%d frames=%d palettes_present=%s"
          % (art.num_rotations, art.num_frames,
             [i for i, p in enumerate(art.palette_present) if p]))
    for rot, block in enumerate(art.frames):
        for fi, fr in enumerate(block):
            comp = "raw" if fr.data_size == fr.width * fr.height else "rle"
            print("  rot%d frame%d: %dx%d data=%d(%s) hot=(%d,%d) off=(%d,%d)"
                  % (rot, fi, fr.width, fr.height, fr.data_size, comp,
                     fr.hot_x, fr.hot_y, fr.offset_x, fr.offset_y))
    return 0


def _cmd_dump(path: str, outdir: str) -> int:
    art = decode(open(path, "rb").read())
    slot = art.first_palette_slot()
    if slot < 0:
        raise ArtError("no palette present; cannot render")
    os.makedirs(outdir, exist_ok=True)
    base = os.path.splitext(os.path.basename(path))[0]
    written = 0
    for rot, block in enumerate(art.frames):
        for fi, fr in enumerate(block):
            if fr.width == 0 or fr.height == 0:
                continue
            rgba = frame_to_rgba(art, fr, slot)
            out = os.path.join(outdir, "%s_r%d_f%d.png" % (base, rot, fi))
            _write_png(out, fr.width, fr.height, rgba)
            written += 1
    print("wrote %d PNG(s) to %s" % (written, outdir))
    return 0


def _cmd_verify(root: str, limit: int = 0) -> int:
    files = []
    for dirpath, _dirs, names in os.walk(root):
        for n in names:
            if n.lower().endswith(".art"):
                files.append(os.path.join(dirpath, n))
    files.sort()
    if limit:
        files = files[:limit]
    ok = 0
    frames = 0
    failed = []
    for fp in files:
        try:
            art = decode(open(fp, "rb").read())
            for block in art.frames:
                for fr in block:
                    if len(fr.indices) != fr.width * fr.height:
                        raise ArtError("decoded length mismatch")
                    frames += 1
            ok += 1
        except Exception as e:  # noqa: BLE001
            failed.append((fp, "%s: %s" % (type(e).__name__, e)))
    print("verified %d/%d ART files (%d frames decoded)" % (ok, len(files), frames))
    for fp, err in failed[:20]:
        print("  FAIL %s -- %s" % (fp, err))
    return 0 if not failed else 1


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cmd = argv[0]
    if cmd == "info" and len(argv) == 2:
        return _cmd_info(argv[1])
    if cmd == "dump" and len(argv) == 3:
        return _cmd_dump(argv[1], argv[2])
    if cmd == "verify" and len(argv) >= 2:
        return _cmd_verify(argv[1], int(argv[2]) if len(argv) > 2 else 0)
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
