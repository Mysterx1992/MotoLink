#!/usr/bin/env python3
"""Rebuild the EXACT already-tested vc32 APK from the existing unsigned CI artifact.
The separate signing block is public data extracted from the distributed APK:
no keystore, private key, password, or signing capability is included.
Fail closed unless the entire final APK matches the tester's SHA-256.
"""
from pathlib import Path
from zipfile import ZipFile
import argparse
import base64
import hashlib
import struct
import zlib

EXPECTED_UNSIGNED = "1ec6b92f1894522da4914febea8fdfa68db9c4131642a96076d527b9b0694a52"
EXPECTED_SIGNED = "ad30dac38b9d0174d0b6422d92b167b6293a8e26196ea5ca7b3c1a6566429625"
EXPECTED_BLOCK = "174a16a0295ab6d3a651fe84474d7def593da912bfe208e6798dfe848db73bed"

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def rebuild(unsigned_path, public_block_path, output_path):
    raw = Path(unsigned_path).read_bytes()
    if sha256(raw) != EXPECTED_UNSIGNED:
        raise RuntimeError("Unsigned CI artifact does not match frozen source APK")
    payload = Path(public_block_path).read_text(encoding="ascii").strip()
    sig = zlib.decompress(base64.b64decode(payload, validate=True))
    if len(sig) != 8192 or sig[-16:] != b"APK Sig Block 42" or sha256(sig) != EXPECTED_BLOCK:
        raise RuntimeError("Public APK signing block mismatch")
    eocd_at = raw.rfind(b"PK\x05\x06")
    if eocd_at < 0 or eocd_at + 22 != len(raw):
        raise RuntimeError("Invalid ZIP end-of-central-directory")
    old_cd = struct.unpack_from("<I", raw, eocd_at + 16)[0]
    cd_size = struct.unpack_from("<I", raw, eocd_at + 12)[0]
    with ZipFile(unsigned_path) as zip_file:
        infos = sorted(zip_file.infolist(), key=lambda z: z.header_offset)
    output = bytearray()
    offsets = {}
    for index, info in enumerate(infos):
        off = info.header_offset
        if raw[off:off+4] != b"PK\x03\x04":
            raise RuntimeError("Invalid local ZIP file header")
        name_len, extra_len = struct.unpack_from("<HH", raw, off+26)
        hdr = bytearray(raw[off:off+30])
        name = raw[off+30:off+30+name_len]
        old_extra = raw[off+30+name_len:off+30+name_len+extra_len]
        if info.compress_type == 0:
            alignment = 16384 if info.filename.endswith(".so") else 4
            new_extra_len = 6 + (-(len(output) + 30 + name_len + 6)) % alignment
            extra = struct.pack("<HHH", 0xD935, new_extra_len-4, alignment) + bytes(new_extra_len-6)
        else:
            extra = old_extra
        struct.pack_into("<H", hdr, 28, len(extra))
        offsets[info.filename] = len(output)
        output += hdr + name + extra
        data_at = off+30+name_len+extra_len
        next_off = infos[index+1].header_offset if index+1 < len(infos) else old_cd
        output += raw[data_at:data_at+info.compress_size]
        output += raw[data_at+info.compress_size:next_off]

    # Mirror the frozen APK's alignment and append its PUBLIC (already existing) signing block.
    output += bytes((-len(output)) % 4096)
    output += sig
    new_cd = len(output)
    ptr = old_cd
    count = struct.unpack_from("<H", raw, eocd_at+10)[0]
    if count != len(infos) or count != len(offsets):
        raise RuntimeError("ZIP entry count mismatch")
    for _ in range(count):
        if raw[ptr:ptr+4] != b"PK\x01\x02":
            raise RuntimeError("Invalid ZIP central directory entry")
        name_len, extra_len, comment_len = struct.unpack_from("<HHH", raw, ptr+28)
        entry = bytearray(raw[ptr:ptr+46+name_len+extra_len+comment_len])
        entry_name = entry[46:46+name_len].decode("utf-8")
        struct.pack_into("<I", entry, 42, offsets[entry_name])
        output += entry
        ptr += len(entry)
    if ptr != old_cd + cd_size:
        raise RuntimeError("ZIP central directory boundary mismatch")
    eocd = bytearray(raw[eocd_at:])
    struct.pack_into("<I", eocd, 16, new_cd)
    output += eocd
    if sha256(output) != EXPECTED_SIGNED:
        raise RuntimeError("Rebuilt APK differs from signed field-test APK; DO NOT PUBLISH")
    Path(output_path).write_bytes(output)
    print(f"PASS exact signed APK bytes sha256={EXPECTED_SIGNED} size={len(output)}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("unsigned")
    parser.add_argument("public_block")
    parser.add_argument("output")
    args = parser.parse_args()
    rebuild(args.unsigned, args.public_block, args.output)
