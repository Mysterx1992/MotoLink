#!/usr/bin/env python3
"""Fail-closed 16-KiB ELF PT_LOAD and APK ZIP alignment audit for 64-bit ABIs.

Checks a newly built APK, not the source Gradle graph. Uses only Python stdlib.
32-bit ABI results are informational; Play's 16-KiB requirement is for 64-bit.
An AAB and device still require independent validation.
"""
import argparse
from pathlib import Path
import struct
import sys
import zipfile

PAGE = 16 * 1024
ABI_64 = {"arm64-v8a", "x86_64", "riscv64"}


def load_alignments(blob: bytes) -> list[tuple[int, int, int]]:
    if len(blob) < 64 or blob[:4] != b"\x7fELF":
        raise ValueError("not an ELF binary")
    cls, endian = blob[4], blob[5]
    if cls not in (1, 2) or endian not in (1, 2):
        raise ValueError("invalid ELF class/endianness")
    bo = "<" if endian == 1 else ">"
    if cls == 2:
        phoff = struct.unpack_from(bo + "Q", blob, 32)[0]
        phentsize, phnum = struct.unpack_from(bo + "HH", blob, 54)
        min_size, fmt = 56, bo + "IIQQQQQQ"
    else:
        phoff = struct.unpack_from(bo + "I", blob, 28)[0]
        phentsize, phnum = struct.unpack_from(bo + "HH", blob, 42)
        min_size, fmt = 32, bo + "IIIIIIII"
    if not phnum or phentsize < min_size or phoff + phentsize * phnum > len(blob):
        raise ValueError("invalid or missing program header table")
    segments = []
    for i in range(phnum):
        v = struct.unpack_from(fmt, blob, phoff + i * phentsize)
        if v[0] == 1:  # PT_LOAD
            off, vaddr, align = (v[2], v[3], v[7]) if cls == 2 else (v[1], v[2], v[7])
            segments.append((off, vaddr, align))
    if not segments:
        raise ValueError("no PT_LOAD segments")
    return segments


def data_offset(archive: zipfile.ZipFile, info: zipfile.ZipInfo) -> int:
    archive.fp.seek(info.header_offset)
    hdr = archive.fp.read(30)
    if len(hdr) != 30 or hdr[:4] != b"PK\x03\x04":
        raise ValueError("invalid ZIP local header")
    name_len, extra_len = struct.unpack_from("<HH", hdr, 26)
    return info.header_offset + 30 + name_len + extra_len


def audit(apk: Path) -> bool:
    fails = []
    count64 = 0
    with zipfile.ZipFile(apk) as z:
        libs = sorted((x for x in z.infolist()
                       if x.filename.startswith("lib/") and x.filename.endswith(".so")),
                      key=lambda x: x.filename)
        for info in libs:
            parts = info.filename.split("/")
            if len(parts) != 3:
                fails.append(f"{info.filename}: unexpected ABI path")
                continue
            is64 = parts[1] in ABI_64
            if is64:
                count64 += 1
            try:
                segs = load_alignments(z.read(info))
            except (ValueError, struct.error) as exc:
                fails.append(f"{info.filename}: {exc}")
                continue
            min_align = min(s[2] for s in segs)
            print(f"{'CHECK' if is64 else 'INFO '} {info.filename}: PT_LOAD min=0x{min_align:x}")
            if not is64:
                continue
            for off, vaddr, align in segs:
                if align < PAGE or (vaddr - off) % PAGE != 0:
                    fails.append(
                        f"{info.filename}: invalid PT_LOAD "
                        f"off=0x{off:x} vaddr=0x{vaddr:x} align=0x{align:x}"
                    )
            if info.compress_type == zipfile.ZIP_STORED:
                off = data_offset(z, info)
                if off % PAGE != 0:
                    fails.append(f"{info.filename}: ZIP data offset {off} not 16-KiB aligned")
            else:
                print(f"INFO  {info.filename}: compressed, ZIP offset alignment not needed")
    if count64 == 0:
        fails.append("No 64-bit shared libraries: cannot certify alignment")
    if fails:
        print("\nFAIL — 16-KiB native check:", file=sys.stderr)
        for issue in fails:
            print("  - " + issue, file=sys.stderr)
        return False
    print(f"\nPASS — all {count64} 64-bit libraries have 16-KiB ELF / APK ZIP alignment")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error("APK not found")
    try:
        return 0 if audit(args.apk) else 1
    except (OSError, zipfile.BadZipFile, RuntimeError, ValueError) as exc:
        print(f"FAIL — APK audit error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
