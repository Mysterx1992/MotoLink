#!/usr/bin/env python3
"""Reconstruct one already-signed MotoLink V1.8 APK, never sign arbitrary bytes.

The included public APK signing block is a byte-exact signature for a single
frozen V1.8 unsigned artifact. No private key or password is present.
"""
import base64
import hashlib
from pathlib import Path
import struct
import sys
from zipfile import ZipFile
import zlib

UNSIGNED_SHA256 = "e831a5d1b60eb466df055aad7f6ba2893c9118a4efb67b9e8f795f86502e51cb"
BLOCK_SHA256 = "9a12bae560ca81afca5776eef500948d35e2c922b867deff3093baa9a21e5f47"
SIGNED_SHA256 = "7775f37990a46f6a4544177920cbbb5abfc3e7e5e4f059624b8d58122aad89f6"

def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def rebuild(unsigned_path: str, block_path: str, signed_path: str) -> None:
    unsigned = Path(unsigned_path).read_bytes()
    if digest(unsigned) != UNSIGNED_SHA256:
        raise ValueError("Unsigned APK SHA mismatch: refusing release")
    block = zlib.decompress(base64.b64decode(Path(block_path).read_bytes().strip(), validate=True))
    if digest(block) != BLOCK_SHA256:
        raise ValueError("Public signing block SHA mismatch")
    if len(block) != 11808 or block[-16:] != b"APK Sig Block 42":
        raise ValueError("Public signature block has unexpected structure")
    block_size = struct.unpack_from("<Q", block, len(block) - 24)[0]
    actual_start = len(block) - block_size - 8
    if actual_start < 0 or struct.unpack_from("<Q", block, actual_start)[0] != block_size:
        raise ValueError("APK signature block size disagreement")

    eocd = unsigned.rfind(b"PK\x05\x06")
    if eocd < 0 or eocd + 22 != len(unsigned):
        raise ValueError("Unsupported unsigned ZIP EOCD")
    offset = struct.unpack_from("<I", unsigned, eocd + 16)[0]
    if unsigned[offset:offset + 4] != b"PK\x01\x02":
        raise ValueError("Unexpected central-directory offset")
    if offset + len(block) > 0xFFFFFFFF:
        raise ValueError("ZIP32 central-directory offset exceeds limit")

    # apksigner inserted only signed public bytes before the Central Directory
    # and updated the EOCD pointer. No unsigned input bytes are changed.
    signed = (unsigned[:offset] + block + unsigned[offset:eocd]
              + unsigned[eocd:eocd + 16] + struct.pack("<I", offset + len(block))
              + unsigned[eocd + 20:])
    if digest(signed) != SIGNED_SHA256:
        raise ValueError("Reconstructed signed APK differs from local verified release")
    output = Path(signed_path)
    output.write_bytes(signed)
    with ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError("APK ZIP CRC failure")
    print("PASS exact signed V1.8:", SIGNED_SHA256, "bytes", len(signed))

if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: rebuild_verified_v18.py aligned_unsigned.apk public_block.b64 output.apk")
    rebuild(*sys.argv[1:])
