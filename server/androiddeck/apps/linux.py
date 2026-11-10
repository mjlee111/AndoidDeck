"""Linux: freedesktop .desktop entries + icon theme lookup."""

from __future__ import annotations

import configparser
import logging
import os
import re
import shlex
import shutil
import subprocess
from pathlib import Path

from . import Provider

log = logging.getLogger(__name__)

_ICON_EXTS = (".png", ".svg", ".svgz", ".xpm")
_FIELD_CODES = re.compile(r"%[fFuUdDnNickvm]")
_SIZE_DIR = re.compile(r"^(\d+)x\d+(?:@(\d+)x?)?$")


def _data_dirs() -> list[Path]:
    home = Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local" / "share")
    dirs = [home]
    for d in (os.environ.get("XDG_DATA_DIRS") or "/usr/local/share:/usr/share").split(":"):
        if d:
            dirs.append(Path(d))
    dirs += [
        Path.home() / ".local/share/flatpak/exports/share",
        Path("/var/lib/flatpak/exports/share"),
        Path("/var/lib/snapd/desktop"),
        Path("/usr/share"),
    ]
    out: list[Path] = []
    for d in dirs:
        if d not in out and d.is_dir():
            out.append(d)
    return out


def _locale_keys(key: str) -> list[str]:
    lang = os.environ.get("LC_ALL") or os.environ.get("LC_MESSAGES") or os.environ.get("LANG") or ""
    lang = lang.split(".")[0].split("@")[0]
    keys = []
    if lang and lang not in ("C", "POSIX"):
        keys.append(f"{key}[{lang}]")
        if "_" in lang:
            keys.append(f"{key}[{lang.split('_')[0]}]")
    keys.append(key)
    return keys


def _current_desktops() -> set[str]:
    return {d.strip().lower() for d in (os.environ.get("XDG_CURRENT_DESKTOP") or "").split(":") if d.strip()}


def _current_icon_theme() -> str | None:
    if shutil.which("gsettings"):
        try:
            r = subprocess.run(
                ["gsettings", "get", "org.gnome.desktop.interface", "icon-theme"],
                capture_output=True, text=True, timeout=3,
            )
            theme = r.stdout.strip().strip("'\"")
            if r.returncode == 0 and theme:
                return theme
        except Exception:  # noqa: BLE001
            pass
    kdeglobals = Path.home() / ".config" / "kdeglobals"
    if kdeglobals.exists():
        cp = configparser.ConfigParser(interpolation=None, strict=False)
        try:
            cp.read(kdeglobals, encoding="utf-8")
            theme = cp.get("Icons", "Theme", fallback=None)
            if theme:
                return theme
        except configparser.Error:
            pass
    return None


class IconFinder:
    """Resolves Icon= names using the active theme, its parents, hicolor and pixmaps."""

    def __init__(self) -> None:
        bases = [Path.home() / ".icons"] + [d / "icons" for d in _data_dirs()]
        self.bases = [b for b in dict.fromkeys(bases) if b.is_dir()]
        self.pixmaps = [d / "pixmaps" for d in _data_dirs() if (d / "pixmaps").is_dir()]
        self.themes = self._theme_chain(_current_icon_theme())
        self._index: dict[str, tuple[int, float, Path]] | None = None

    def _theme_dirs(self, theme: str) -> list[Path]:
        return [b / theme for b in self.bases if (b / theme).is_dir()]

    def _theme_chain(self, start: str | None) -> list[str]:
        chain: list[str] = []
        todo = [start] if start else []
        while todo:
            t = todo.pop(0)
            if t in chain:
                continue
            dirs = self._theme_dirs(t)
            if not dirs:
                continue
            chain.append(t)
            for d in dirs:
                idx = d / "index.theme"
                if idx.exists():
                    cp = configparser.ConfigParser(interpolation=None, strict=False)
                    try:
                        cp.read(idx, encoding="utf-8")
                        inherits = cp.get("Icon Theme", "Inherits", fallback="")
                        todo += [x.strip() for x in inherits.split(",") if x.strip()]
                    except configparser.Error:
                        pass
        for fallback in ("Adwaita", "hicolor"):
            if fallback not in chain and self._theme_dirs(fallback):
                chain.append(fallback)
        return chain

    @staticmethod
    def _score(path: Path) -> float:
        """Higher is better. Prefer 96-256px PNG, then SVG, then small bitmaps."""
        ext = path.suffix.lower()
        if "symbolic" in path.name or "symbolic" in path.parts:
            return 1
        size = None
        for part in path.parts:
            m = _SIZE_DIR.match(part)
            if m:
                size = int(m.group(1)) * int(m.group(2) or 1)
            elif part == "scalable":
                size = -1
        if ext in (".svg", ".svgz"):
            return 900
        if ext == ".xpm":
            return 50
        if size is None or size == -1:
            return 400
        if size >= 96:
            return 1000 - abs(size - 128) * 0.5
        return size * 5

    def _build(self) -> dict[str, tuple[int, float, Path]]:
        index: dict[str, tuple[int, float, Path]] = {}
        for rank, theme in enumerate(self.themes):
            for tdir in self._theme_dirs(theme):
                for root, _dirs, files in os.walk(tdir, followlinks=True):
                    for f in files:
                        stem, ext = os.path.splitext(f)
                        if ext.lower() not in _ICON_EXTS:
                            continue
                        p = Path(root) / f
                        score = self._score(p)
                        cur = index.get(stem)
                        if cur is None or (rank, -score) < (cur[0], -cur[1]):
                            index[stem] = (rank, score, p)
        return index

    def find(self, icon: str) -> Path | None:
        if not icon:
            return None
        if icon.startswith("/"):
            p = Path(icon)
            return p if p.is_file() else None
        if self._index is None:
            self._index = self._build()
        name = icon
        if name.lower().endswith(_ICON_EXTS):
            name = os.path.splitext(name)[0]
        hit = self._index.get(name)
        if hit and hit[1] > 1:
            return hit[2]
        for pm in self.pixmaps:
            for ext in ("", *_ICON_EXTS):
                p = pm / f"{icon}{ext}" if ext else pm / icon
                if p.is_file():
                    return p
        return hit[2] if hit else None


class LinuxProvider(Provider):
    def __init__(self) -> None:
        self._gio = "/usr/bin/gio" if Path("/usr/bin/gio").exists() else None

    def scan(self, icon_dir: Path) -> list[tuple[str, str, Path | None, str | None]]:
        finder = IconFinder()
        desktops = _current_desktops()
        seen_ids: set[str] = set()
        out: list[tuple[str, str, Path | None, str | None]] = []
        for base in _data_dirs():
            app_dir = base / "applications"
            if not app_dir.is_dir():
                continue
            for path in sorted(app_dir.rglob("*.desktop")):
                desktop_id = str(path.relative_to(app_dir)).replace(os.sep, "-")
                if desktop_id in seen_ids:
                    continue
                seen_ids.add(desktop_id)
                entry = self._parse(path)
                if entry is None:
                    continue
                if not self._visible(entry, desktops):
                    continue
                name = next((entry[k] for k in _locale_keys("Name") if entry.get(k)), None)
                if not name:
                    continue
                icon = finder.find(entry.get("Icon", "").strip())
                argv = self._exec_argv(entry, path)
                while argv and (argv[0] == "env" or "=" in argv[0]):
                    argv = argv[1:]
                out.append((name, f"desktop:{path}", icon, argv[0] if argv else None))
        return out

    @staticmethod
    def _parse(path: Path) -> dict[str, str] | None:
        cp = configparser.ConfigParser(interpolation=None, strict=False)
        cp.optionxform = str  # keep key case, e.g. Name[ko]
        try:
            cp.read(path, encoding="utf-8")
        except (configparser.Error, UnicodeDecodeError, OSError):
            return None
        if not cp.has_section("Desktop Entry"):
            return None
        return dict(cp.items("Desktop Entry"))

    @staticmethod
    def _visible(e: dict[str, str], desktops: set[str]) -> bool:
        if e.get("Type", "Application") != "Application":
            return False
        if e.get("NoDisplay", "").lower() == "true" or e.get("Hidden", "").lower() == "true":
            return False
        if not e.get("Exec"):
            return False
        only = {d.strip().lower() for d in e.get("OnlyShowIn", "").split(";") if d.strip()}
        if only and not (only & desktops):
            return False
        not_in = {d.strip().lower() for d in e.get("NotShowIn", "").split(";") if d.strip()}
        if not_in & desktops:
            return False
        try_exec = e.get("TryExec", "").strip()
        if try_exec and not (shutil.which(try_exec) or Path(try_exec).is_file()):
            return False
        return True

    def launch(self, target: str) -> None:
        kind, _, value = target.partition(":")
        if kind != "desktop":
            value = target
        path = Path(value)
        if not path.is_file():
            raise RuntimeError(f"desktop file not found: {path}")
        if self._gio:
            r = subprocess.run([self._gio, "launch", str(path)], capture_output=True, text=True, timeout=10)
            if r.returncode == 0:
                return
            log.debug("gio launch failed (%s), falling back to Exec", r.stderr.strip())
        entry = self._parse(path) or {}
        argv = self._exec_argv(entry, path)
        if not argv:
            raise RuntimeError("desktop entry has no Exec line")
        if entry.get("Terminal", "").lower() == "true":
            for term in ("x-terminal-emulator", "gnome-terminal", "konsole", "xterm"):
                if shutil.which(term):
                    argv = [term, "--", *argv] if term == "gnome-terminal" else [term, "-e", *argv]
                    break
        subprocess.Popen(
            argv,
            cwd=entry.get("Path") or str(Path.home()),
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True,
        )

    @staticmethod
    def _exec_argv(entry: dict[str, str], path: Path) -> list[str]:
        try:
            tokens = shlex.split(entry.get("Exec", ""))
        except ValueError:
            return []
        argv: list[str] = []
        for tok in tokens:
            if tok == "%i":
                if entry.get("Icon"):
                    argv += ["--icon", entry["Icon"]]
                continue
            tok = tok.replace("%c", entry.get("Name", "")).replace("%k", str(path))
            tok = _FIELD_CODES.sub("", tok).replace("%%", "%")
            if tok:
                argv.append(tok)
        return argv
