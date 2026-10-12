#!/usr/bin/env python3
"""Recreate EXACT previously signed vc33 bytes from pinned unsigned CI artifact.

public_reconstruction_template.b64 contains only public ZIP headers and
already-existing APK v3 signing-block bytes. It has NO private signing key,
password, or capability to sign an altered APK. Fail closed on every SHA.
"""
from pathlib import Path
from zipfile import ZipFile
import base64
import hashlib
import io
import json
import struct
import sys
import zlib

UNSIGNED_SHA256 = "1541459cd10a7c3a5f49aab29ef0c506f3a76c68e4062be67e5ab5862f481f2e"
SIGNED_SHA256 = "c4d68f35e5c96b348d003d36e80b4648eff9a6e947a18e326c0411f1104e954e"
TEMPLATE_SHA256 = "6848a286366a24c472bf1bec2b95f3c56134a2649ac2ba37b93f7c9620539fc5"

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def rebuild(unsigned_path, template_path, output_path):
    data = Path(unsigned_path).read_bytes()
    assert sha256(data) == UNSIGNED_SHA256, "Wrong unsigned artifact: refuse"
    public_bytes = Path(template_path).read_bytes()
    assert sha256(public_bytes) == TEMPLATE_SHA256, "Public reconstruction template changed"
    template = json.loads(zlib.decompress(base64.b64decode(public_bytes.strip(), validate=True)))
    assert template["schema"] == "MotoLink-vc33-public-byte-reconstruction-v1"
    assert template["unsigned_sha256"] == UNSIGNED_SHA256
    assert template["signed_sha256"] == SIGNED_SHA256
    with ZipFile(io.BytesIO(data)) as z:
        infos = sorted(z.infolist(), key=lambda i: i.header_offset)
    assert len(infos) == 530
    assert [i.filename for i in infos] == template["names"]
    assert len(template["headers"]) == len(infos) == len(template["tails"])
    out = bytearray()
    for n, info in enumerate(infos):
        offset = info.header_offset
        assert data[offset:offset+4] == b"PK\x03\x04"
        name_len, extra_len = struct.unpack_from("<HH", data, offset+26)
        data_offset = offset+30+name_len+extra_len
        name = data[offset+30:offset+30+name_len]
        header = base64.b64decode(template["headers"][n], validate=True)
        assert header[:4] == b"PK\x03\x04"
        name_len_public, extra_len_public = struct.unpack_from("<HH", header, 26)
        assert name == header[30:30+name_len_public]
        assert len(header) == 30 + name_len_public + extra_len_public
        tail = base64.b64decode(template["tails"][n], validate=True)
        out += header
        out += data[data_offset:data_offset+info.compress_size]
        out += tail
    out += base64.b64decode(template["suffix"], validate=True)
    assert sha256(out) == SIGNED_SHA256, "vc33 reconstructed APK differs from official signed bytes"
    Path(output_path).write_bytes(out)
    with ZipFile(output_path) as z:
        assert z.testzip() is None
    print(f"PASS byte-exact officially signed vc33 sha256={SIGNED_SHA256} bytes={len(out)}")

if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("Usage: rebuild_verified_vc33.py unsigned.apk public_template.b64 signed.apk")
    rebuild(*sys.argv[1:])
