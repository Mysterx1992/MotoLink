#!/usr/bin/env python3
"""Rebuild *one fixed* already-signed MotoLink V1.8.2 APK.

This is NOT a signing tool and has no private signing key. A publicly distributable
binary recipe reconstructs an APK signed offline with the original official key.
It can only reconstruct the exact frozen signed APK from the exact frozen unsigned
APK. SHA-256 gates reject any other input, recipe, or output.
"""
import base64
import hashlib
import lzma
from pathlib import Path
import struct
import sys
from zipfile import ZipFile

UNSIGNED_SHA256 = "056889336902fe1401061d12d0538997c3203666b27326cfa55f61bf261e0aa1"
SIGNED_SHA256 = "17e41c3c916a2f7662c786bb3da4926077f36afd5838ae0ba08e98a6d948ab72"
PATCH_SHA256 = "3d67ce2f2c63ff75e8ac748902217280c1323cbc424850d8343d1b9d3156a621"
SIGNED_SIZE = 73504525
FORMAT = "<IqI"
MAGIC = b"MTAP1"

def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def rebuild(unsigned_path: str, patch_path: str, target_path: str) -> None:
    unsigned = Path(unsigned_path).read_bytes()
    if sha256(unsigned) != UNSIGNED_SHA256:
        raise ValueError("UNSIGNED_SHA_MISMATCH: release blocked")
    patch_text = Path(patch_path).read_text(encoding="ascii").strip()
    patch = base64.b64decode(patch_text, validate=True)
    if sha256(patch) != PATCH_SHA256:
        raise ValueError("PUBLIC_PATCH_SHA_MISMATCH: release blocked")
    recipe = lzma.decompress(patch)
    if not recipe.startswith(MAGIC):
        raise ValueError("PUBLIC_PATCH_FORMAT: release blocked")
    count = struct.unpack_from("<I", recipe, 5)[0]
    if count != 1064:
        raise ValueError("PUBLIC_PATCH_COUNT: release blocked")
    cursor = 9 + count * struct.calcsize(FORMAT)
    if cursor > len(recipe):
        raise ValueError("PUBLIC_PATCH_TRUNCATED")
    out = bytearray()
    for index in range(count):
        literal_length, source_offset, copy_length = struct.unpack_from(
            FORMAT, recipe, 9 + index * struct.calcsize(FORMAT))
        end = cursor + literal_length
        if end > len(recipe):
            raise ValueError("PUBLIC_PATCH_LITERAL_OVERFLOW")
        out.extend(recipe[cursor:end])
        cursor = end
        if source_offset == -1 and copy_length == 0:
            continue
        if source_offset < 0 or source_offset + copy_length > len(unsigned):
            raise ValueError("PUBLIC_PATCH_COPY_OUT_OF_BOUNDS")
        out.extend(unsigned[source_offset:source_offset + copy_length])
    if cursor != len(recipe) or len(out) != SIGNED_SIZE:
        raise ValueError("PUBLIC_PATCH_SIZE_MISMATCH")
    if sha256(out) != SIGNED_SHA256:
        raise ValueError("SIGNED_SHA_MISMATCH: release blocked")
    target = Path(target_path)
    target.write_bytes(out)
    with ZipFile(target) as apk:
        bad = apk.testzip()
        if bad is not None:
            raise ValueError("SIGNED_APK_ZIP_CRC_FAIL: " + bad)
    print(f"PASS signed APK EXACT: {SIGNED_SHA256} ({SIGNED_SIZE} bytes); no private key used")

if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: python3 reconstruct_exact_v182.py UNSIGNED.apk public_patch.xz.b64 SIGNED.apk")
    rebuild(*sys.argv[1:])
