"""Builds a standalone server for the current OS with PyInstaller (no Python needed to run it).

Usage:  python packaging/build.py [--version 1.2.3]
Output: dist/AndroidDeckServer.exe (Windows), dist/AndroidDeck Server.app + zip (macOS),
        dist/AndroidDeckServer (Linux)
"""

from __future__ import annotations

import argparse
import os
import plistlib
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from androiddeck.branding import icon_image  # noqa: E402

BUNDLE_ID = "dev.menggu.androiddeck.server"


def make_icons(out: Path) -> dict[str, Path]:
    out.mkdir(parents=True, exist_ok=True)
    img = icon_image(1024)
    paths = {"png": out / "icon.png", "ico": out / "icon.ico", "icns": out / "icon.icns"}
    img.save(paths["png"])
    img.save(paths["ico"], sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    try:
        img.save(paths["icns"])
    except Exception as e:  # noqa: BLE001 - only needed on macOS
        print(f"icns not written: {e}")
    return paths


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", help="version string to embed (patches androiddeck/__init__.py in the build copy)")
    args = ap.parse_args()

    work = ROOT / "build"
    dist = ROOT / "dist"
    shutil.rmtree(work, ignore_errors=True)
    shutil.rmtree(dist, ignore_errors=True)
    icons = make_icons(work / "icons")

    win, mac = sys.platform == "win32", sys.platform == "darwin"
    name = "AndroidDeck Server" if mac else "AndroidDeckServer"
    cmd = [
        sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
        "--name", name,
        "--distpath", str(dist), "--workpath", str(work / "pyi"), "--specpath", str(work),
        "--add-data", f"{ROOT / 'androiddeck' / 'apps' / 'windows_apps.ps1'}{os.pathsep}androiddeck/apps",
        # Backends are imported dynamically per platform.
        "--collect-submodules", "pynput",
        "--collect-submodules", "pystray",
    ]
    if win:
        cmd += ["--onefile", "--windowed", "--icon", str(icons["ico"]),
                "--collect-submodules", "winrt", "--hidden-import", "comtypes.stream"]
    elif mac:
        cmd += ["--windowed", "--icon", str(icons["icns"]), "--osx-bundle-identifier", BUNDLE_ID]
    else:
        cmd += ["--onefile"]
    cmd.append(str(ROOT / "run_server.py"))

    init = ROOT / "androiddeck" / "__init__.py"
    original = init.read_text(encoding="utf-8")
    try:
        if args.version:
            patched = "\n".join(
                f'__version__ = "{args.version}"' if line.startswith("__version__") else line
                for line in original.splitlines()
            ) + "\n"
            init.write_text(patched, encoding="utf-8")
        print(" ".join(cmd), flush=True)
        subprocess.run(cmd, check=True, cwd=ROOT)
    finally:
        init.write_text(original, encoding="utf-8")

    if mac:
        app = dist / f"{name}.app"
        plist_path = app / "Contents" / "Info.plist"
        with open(plist_path, "rb") as f:
            plist = plistlib.load(f)
        plist["LSUIElement"] = True  # menu-bar app, no Dock icon
        if args.version:
            plist["CFBundleShortVersionString"] = args.version
        with open(plist_path, "wb") as f:
            plistlib.dump(plist, f)
        subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(app)], check=False)
        subprocess.run(["ditto", "-c", "-k", "--keepParent", str(app), str(dist / "AndroidDeck-Server-macos.zip")],
                       check=True)
    print("\nBuilt:", *sorted(p.name for p in dist.iterdir()), sep="\n  ")
    return 0


if __name__ == "__main__":
    sys.exit(main())
