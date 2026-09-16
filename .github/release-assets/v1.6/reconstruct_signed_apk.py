#!/usr/bin/env python3
from pathlib import Path
import sys, struct, zipfile, hashlib, lzma

TARGET_SHA = "cedba0a0f8cc9beacacbf8c8c85258a72bb89223061511f75b298bfc120b3f9e"

def u32(b, p):
    if p + 4 > len(b):
        raise SystemExit("blueprint truncated")
    return struct.unpack_from("<I", b, p)[0], p + 4

def raw_payload(src, zi):
    off = zi.header_offset
    vals = struct.unpack_from("<4s5H3L2H", src, off)
    if vals[0] != b"PK\x03\x04":
        raise SystemExit(f"bad local header: {zi.filename}")
    fnlen, exlen = vals[9], vals[10]
    start = off + 30 + fnlen + exlen
    return src[start:start + zi.compress_size]

def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: reconstruct.py unsigned.apk blueprint.mlbp.xz output.apk")
    src_path, bp_path, out_path = map(Path, sys.argv[1:])
    src = src_path.read_bytes()
    bp = lzma.decompress(bp_path.read_bytes())
    p = 0
    if bp[:5] != b"MLBP1":
        raise SystemExit("bad blueprint magic")
    p = 5
    n, p = u32(bp, p)
    with zipfile.ZipFile(src_path) as z:
        byname = {zi.filename: zi for zi in z.infolist()}
    out = bytearray()
    for _ in range(n):
        hlen, p = u32(bp, p)
        hdr = bp[p:p + hlen]
        p += hlen
        if len(hdr) != hlen:
            raise SystemExit("header truncated")
        vals = struct.unpack_from("<4s5H3L2H", hdr, 0)
        if vals[0] != b"PK\x03\x04":
            raise SystemExit("bad target header")
        csize, fnlen = vals[7], vals[9]
        name = hdr[30:30 + fnlen].decode("utf-8")
        mode = bp[p]
        p += 1
        out += hdr
        if mode == 1:
            dlen, p = u32(bp, p)
            raw = bp[p:p + dlen]
            p += dlen
        elif mode == 0:
            if name not in byname:
                raise SystemExit(f"source entry missing: {name}")
            raw = raw_payload(src, byname[name])
        else:
            raise SystemExit(f"bad mode {mode} for {name}")
        if len(raw) != csize:
            raise SystemExit(f"compressed size mismatch {name}: source={len(raw)} target={csize}")
        out += raw
    for label in ("padding", "signing_block", "central_suffix"):
        ln, p = u32(bp, p)
        chunk = bp[p:p + ln]
        p += ln
        if len(chunk) != ln:
            raise SystemExit(f"{label} truncated")
        out += chunk
    if p != len(bp):
        raise SystemExit(f"blueprint trailing bytes: {len(bp)-p}")
    Path(out_path).write_bytes(out)
    got = hashlib.sha256(out).hexdigest()
    print(f"reconstructed_sha256={got}")
    if got != TARGET_SHA:
        raise SystemExit("FINAL SHA256 MISMATCH")
    print("EXACT_SIGNED_APK_RECONSTRUCTION_PASS")

if __name__ == "__main__":
    main()
