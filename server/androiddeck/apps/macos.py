"""macOS: .app bundles from the standard Applications folders, icons via sips."""

from __future__ import annotations

import logging
import plistlib
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from . import Provider

log = logging.getLogger(__name__)

_ROOTS = [
    Path("/Applications"),
    Path("/Applications/Utilities"),
    Path("/System/Applications"),
    Path("/System/Applications/Utilities"),
    Path.home() / "Applications",
]
_EXTRA = [Path("/System/Library/CoreServices/Finder.app")]


def _bundle_info(app: Path) -> dict:
    try:
        with open(app / "Contents" / "Info.plist", "rb") as f:
            return plistlib.load(f)
    except Exception:  # noqa: BLE001
        return {}


def _icns_path(app: Path, info: dict) -> Path | None:
    res = app / "Contents" / "Resources"
    for key in ("CFBundleIconFile", "CFBundleIconName"):
        name = info.get(key)
        if not name:
            continue
        p = res / name
        if p.suffix != ".icns":
            p = p.with_name(p.name + ".icns")
        if p.is_file():
            return p
    for cand in ("AppIcon.icns", "app.icns"):
        if (res / cand).is_file():
            return res / cand
    return None


class MacProvider(Provider):
    def _find_apps(self) -> list[Path]:
        apps: list[Path] = []
        for root in _ROOTS:
            if not root.is_dir():
                continue
            for p in sorted(root.iterdir()):
                if p.suffix == ".app":
                    apps.append(p)
                elif p.is_dir() and not p.name.startswith(".") and p != Path("/Applications/Utilities"):
                    # One level deeper for vendor folders, e.g. /Applications/Adobe Photoshop/*.app
                    try:
                        apps += [c for c in sorted(p.iterdir()) if c.suffix == ".app"]
                    except OSError:
                        pass
        apps += [p for p in _EXTRA if p.exists()]
        return apps

    def scan(self, icon_dir: Path) -> list[tuple[str, str, Path | None, str | None]]:
        work = icon_dir.parent / "mac_scan"
        work.mkdir(parents=True, exist_ok=True)

        def one(idx_app: tuple[int, Path]) -> tuple[str, str, Path | None, str | None] | None:
            idx, app = idx_app
            info = _bundle_info(app)
            if info.get("LSBackgroundOnly") in (True, "1"):
                return None
            name = info.get("CFBundleDisplayName") or info.get("CFBundleName") or app.stem
            png = work / f"{idx}.png"
            icon: Path | None = None
            icns = _icns_path(app, info)
            try:
                if icns is not None:
                    r = subprocess.run(
                        ["sips", "-s", "format", "png", "-Z", "128", str(icns), "--out", str(png)],
                        capture_output=True, timeout=15,
                    )
                    if r.returncode == 0 and png.exists():
                        icon = png
                if icon is None:
                    # Asset-catalog-only apps: let Quick Look render the bundle icon.
                    out_dir = work / f"ql{idx}"
                    out_dir.mkdir(exist_ok=True)
                    subprocess.run(["qlmanage", "-t", "-s", "128", "-o", str(out_dir), str(app)],
                                   capture_output=True, timeout=15)
                    found = next(out_dir.glob("*.png"), None)
                    if found is not None:
                        icon = found
            except Exception as e:  # noqa: BLE001
                log.debug("icon extraction failed for %s: %s", app, e)
            return str(name), f"app:{app}", icon, info.get("CFBundleExecutable")

        apps = self._find_apps()
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(one, enumerate(apps)))
        return [r for r in results if r is not None]

    def launch(self, target: str) -> None:
        kind, _, value = target.partition(":")
        path = value if kind == "app" else target
        subprocess.Popen(["open", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
