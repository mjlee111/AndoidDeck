"""Normalize icon files (png/ico/icns/xpm/svg) into small PNGs (or plain SVG) for the phone."""

from __future__ import annotations

import gzip
import logging
import shutil
import subprocess
from pathlib import Path

log = logging.getLogger(__name__)

ICON_SIZE = 128

try:
    from PIL import Image  # type: ignore

    _HAS_PIL = True
except Exception:  # noqa: BLE001
    _HAS_PIL = False

_RSVG = shutil.which("rsvg-convert")


def normalize_icon(src: Path, out_dir: Path, stem: str) -> str | None:
    """Convert src into out_dir/<stem>.png (or .svg). Returns the file name or None."""
    try:
        ext = src.suffix.lower()
        png = out_dir / f"{stem}.png"
        if src.parent == out_dir and src.name == png.name:
            return png.name
        if ext in (".svg", ".svgz"):
            data = src.read_bytes()
            if ext == ".svgz" or data[:2] == b"\x1f\x8b":
                data = gzip.decompress(data)
            if _RSVG:
                tmp = out_dir / f"_{stem}.svg"
                tmp.write_bytes(data)
                try:
                    r = subprocess.run(
                        [_RSVG, "-w", str(ICON_SIZE), "-h", str(ICON_SIZE), "-a", "-o", str(png), str(tmp)],
                        capture_output=True, timeout=10,
                    )
                    if r.returncode == 0 and png.exists():
                        return png.name
                finally:
                    tmp.unlink(missing_ok=True)
            svg = out_dir / f"{stem}.svg"
            svg.write_bytes(data)
            return svg.name
        if _HAS_PIL:
            with Image.open(src) as im:
                # ICO/ICNS open at their largest embedded size by default.
                im = im.convert("RGBA")
                im.thumbnail((ICON_SIZE, ICON_SIZE), Image.LANCZOS)
                im.save(png, "PNG", optimize=True)
            return png.name
        if ext == ".png":
            shutil.copyfile(src, png)
            return png.name
    except Exception as e:  # noqa: BLE001
        log.debug("icon conversion failed for %s: %s", src, e)
    return None
