#!/usr/bin/env python3
"""Inspect Android shared libraries and enforce 16 KiB ELF alignment on 64-bit ABIs.

Accepts directories, individual .so files, or AAR/APK/ZIP archives. The script
uses only the Python standard library, so it can run in GitHub Actions and on
Windows without readelf.
"""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path
from typing import Iterable, Iterator, Tuple

ELF_MAGIC = b"\x7fELF"
ELFCLASS32 = 1
ELFCLASS64 = 2
ELFDATA2LSB = 1
PT_LOAD = 1
MIN_ALIGNMENT = 16 * 1024

EM_386 = 3
EM_ARM = 40
EM_X86_64 = 62
EM_AARCH64 = 183

ARCHITECTURES = {
    (ELFCLASS32, EM_ARM): "armeabi-v7a",
    (ELFCLASS64, EM_AARCH64): "arm64-v8a",
    (ELFCLASS32, EM_386): "x86",
    (ELFCLASS64, EM_X86_64): "x86_64",
}
KNOWN_ABIS = frozenset(ARCHITECTURES.values())


def declared_abi_from_name(name: str) -> str | None:
    normalized = name.replace("\\", "/")
    parts = normalized.split("/")
    for part in parts:
        if part in KNOWN_ABIS:
            return part
    return None


class VerificationError(RuntimeError):
    pass


def iter_files(inputs: Iterable[Path]) -> Iterator[Tuple[str, bytes]]:
    for input_path in inputs:
        if not input_path.exists():
            raise VerificationError(f"path does not exist: {input_path}")

        if input_path.is_dir():
            for so_path in sorted(input_path.rglob("*.so")):
                yield str(so_path), so_path.read_bytes()
            continue

        if input_path.suffix.lower() in {".aar", ".zip", ".apk"}:
            with zipfile.ZipFile(input_path) as archive:
                for name in sorted(archive.namelist()):
                    if name.endswith(".so"):
                        yield f"{input_path}!/{name}", archive.read(name)
            continue

        if input_path.suffix.lower() == ".so":
            yield str(input_path), input_path.read_bytes()
            continue

        raise VerificationError(f"unsupported input type: {input_path}")


def unpack_from(fmt: str, data: bytes, offset: int):
    size = struct.calcsize(fmt)
    if offset < 0 or offset + size > len(data):
        raise VerificationError("truncated ELF structure")
    return struct.unpack_from(fmt, data, offset)


def parse_elf(name: str, data: bytes) -> tuple[str, list[int], bool]:
    if len(data) < 52 or data[:4] != ELF_MAGIC:
        raise VerificationError(f"{name}: not an ELF file")
    elf_class = data[4]
    if elf_class not in {ELFCLASS32, ELFCLASS64}:
        raise VerificationError(f"{name}: unsupported ELF class {elf_class}")
    if data[5] != ELFDATA2LSB:
        raise VerificationError(f"{name}: expected little-endian ELF")

    machine = unpack_from("<H", data, 18)[0]
    abi = ARCHITECTURES.get((elf_class, machine))
    if abi is None:
        raise VerificationError(
            f"{name}: unsupported Android architecture class={elf_class}, e_machine={machine}"
        )

    if elf_class == ELFCLASS64:
        program_header_offset = unpack_from("<Q", data, 32)[0]
        program_header_entry_size = unpack_from("<H", data, 54)[0]
        program_header_count = unpack_from("<H", data, 56)[0]
        minimum_header_size = 56
        align_offset = 48
        align_format = "<Q"
    else:
        program_header_offset = unpack_from("<I", data, 28)[0]
        program_header_entry_size = unpack_from("<H", data, 42)[0]
        program_header_count = unpack_from("<H", data, 44)[0]
        minimum_header_size = 32
        align_offset = 28
        align_format = "<I"

    if program_header_entry_size < minimum_header_size:
        raise VerificationError(
            f"{name}: invalid program header size {program_header_entry_size}"
        )

    load_alignments: list[int] = []
    for index in range(program_header_count):
        offset = program_header_offset + index * program_header_entry_size
        p_type = unpack_from("<I", data, offset)[0]
        if p_type != PT_LOAD:
            continue
        p_align = unpack_from(align_format, data, offset + align_offset)[0]
        load_alignments.append(p_align)

    if not load_alignments:
        raise VerificationError(f"{name}: no PT_LOAD program headers")

    requires_16k = elf_class == ELFCLASS64
    return abi, load_alignments, requires_16k


def verify_elf(name: str, data: bytes, alignment_mode: str) -> tuple[str, list[str], str]:
    abi, load_alignments, is_64_bit = parse_elf(name, data)

    enforce = alignment_mode == "all" or (
        alignment_mode == "auto" and is_64_bit
    )
    if enforce:
        bad = [value for value in load_alignments if value < MIN_ALIGNMENT]
        if bad:
            pretty = ", ".join(hex(value) for value in load_alignments)
            raise VerificationError(
                f"{name}: {abi} PT_LOAD alignment is not 16 KiB compatible: {pretty}"
            )

    status = "16K-required" if enforce else "alignment-reported"
    return abi, [hex(value) for value in load_alignments], status


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument(
        "--alignment",
        choices=("auto", "all", "none"),
        default="auto",
        help="auto enforces 16 KiB for 64-bit ABIs; all enforces it for every ABI",
    )
    parser.add_argument(
        "--require-abis",
        default="",
        help="comma-separated ABI set that must be present, for example arm64-v8a,armeabi-v7a",
    )
    args = parser.parse_args()

    count = 0
    seen_abis: set[str] = set()
    try:
        for name, data in iter_files(args.paths):
            abi, alignments, status = verify_elf(name, data, args.alignment)
            declared_abi = declared_abi_from_name(name)
            if declared_abi is not None and declared_abi != abi:
                raise VerificationError(
                    f"{name}: directory/archive ABI {declared_abi} does not match ELF ABI {abi}"
                )
            print(f"OK  {name}  ABI={abi}  {status}  PT_LOAD={','.join(alignments)}")
            seen_abis.add(abi)
            count += 1
    except (VerificationError, OSError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    if count == 0:
        print("ERROR: no shared libraries found", file=sys.stderr)
        return 1

    required_abis = {
        value.strip() for value in args.require_abis.split(",") if value.strip()
    }
    unknown_required = required_abis - KNOWN_ABIS
    if unknown_required:
        print(
            "ERROR: unsupported ABI names in --require-abis: "
            + ",".join(sorted(unknown_required)),
            file=sys.stderr,
        )
        return 1
    if required_abis and seen_abis != required_abis:
        print(
            f"ERROR: required ABI set {sorted(required_abis)} but found {sorted(seen_abis)}",
            file=sys.stderr,
        )
        return 1

    print(f"Verified {count} shared libraries; ABIs={','.join(sorted(seen_abis))}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
