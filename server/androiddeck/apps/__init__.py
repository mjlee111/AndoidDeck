"""Installed application discovery, icon caching and launching."""

from __future__ import annotations

import asyncio
import hashlib
import logging
import shutil
import sys
import threading
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from ..config import cache_dir, read_json, write_json_atomic
from ..icons import normalize_icon

log = logging.getLogger(__name__)


@dataclass
class AppEntry:
    id: str
    name: str
    target: str  # provider-specific launch spec, e.g. "desktop:/usr/share/applications/x.desktop"
    icon: str | None = None  # cached icon filename inside the icon cache dir
    exe: str | None = None  # lower-case executable name, used to match mixer sessions to icons


def app_id(target: str) -> str:
    return hashlib.sha1(target.encode("utf-8")).hexdigest()[:16]


class Provider:
    """Platform specific scanner. scan() returns (name, target, icon_source_path|None, exe|None)."""

    def scan(self, icon_dir: Path) -> list[tuple[str, str, Path | None, str | None]]:
        raise NotImplementedError

    def launch(self, target: str) -> None:
        raise NotImplementedError


def _make_provider() -> Provider:
    if sys.platform == "win32":
        from .windows import WindowsProvider

        return WindowsProvider()
    if sys.platform == "darwin":
        from .macos import MacProvider

        return MacProvider()
    from .linux import LinuxProvider

    return LinuxProvider()


class AppIndex:
    def __init__(self) -> None:
        self.provider = _make_provider()
        self.dir = cache_dir()
        self.icon_dir = self.dir / "icons"
        self.icon_dir.mkdir(parents=True, exist_ok=True)
        self.index_path = self.dir / "apps.json"
        self._lock = threading.Lock()
        self._scan_task: asyncio.Task | None = None
        self.apps: dict[str, AppEntry] = {}
        cached = read_json(self.index_path)
        if isinstance(cached, list):
            for a in cached:
                try:
                    e = AppEntry(**a)
                    self.apps[e.id] = e
                except TypeError:
                    continue

    @property
    def scanning(self) -> bool:
        return self._scan_task is not None and not self._scan_task.done()

    def start_scan(self) -> asyncio.Task:
        if not self.scanning:
            self._scan_task = asyncio.get_running_loop().create_task(self._scan())
        assert self._scan_task is not None
        return self._scan_task

    async def wait(self, timeout: float = 90.0) -> None:
        task = self._scan_task
        if task is not None and not task.done():
            try:
                await asyncio.wait_for(asyncio.shield(task), timeout)
            except asyncio.TimeoutError:
                pass

    async def _scan(self) -> None:
        loop = asyncio.get_running_loop()
        try:
            apps = await loop.run_in_executor(None, self._scan_sync)
        except Exception:  # noqa: BLE001
            log.exception("App scan failed")
            return
        self.apps = apps
        log.info("Indexed %d applications", len(apps))

    def _scan_sync(self) -> dict[str, AppEntry]:
        with self._lock:
            raw = self.provider.scan(self.icon_dir)
            apps: dict[str, AppEntry] = {}
            for name, target, icon_src, exe in raw:
                aid = app_id(target)
                if aid in apps:
                    continue
                icon_file = None
                if icon_src is not None:
                    icon_file = normalize_icon(icon_src, self.icon_dir, aid)
                apps[aid] = AppEntry(id=aid, name=name, target=target, icon=icon_file,
                                     exe=_exe_key(exe) if exe else None)
            # Remove icons that no longer belong to any app.
            keep = {a.icon for a in apps.values() if a.icon}
            for f in self.icon_dir.iterdir():
                if f.name not in keep and not f.name.startswith("_"):
                    try:
                        f.unlink()
                    except OSError:
                        pass
            write_json_atomic(self.index_path, [asdict(a) for a in apps.values()])
            return apps

    def list(self) -> list[dict[str, Any]]:
        items = sorted(self.apps.values(), key=lambda a: a.name.casefold())
        return [{"id": a.id, "name": a.name, "target": a.target, "hasIcon": bool(a.icon)} for a in items]

    def icon_path(self, aid: str) -> Path | None:
        a = self.apps.get(aid)
        if a is None or not a.icon:
            return None
        p = self.icon_dir / a.icon
        return p if p.is_file() else None

    def match(self, exe: str | None, name: str | None) -> str | None:
        """Best-effort app id for a process (mixer session) so the phone can show its icon."""
        key = _exe_key(exe) if exe else None
        lname = (name or "").strip().lower()
        if key:
            for a in self.apps.values():
                if a.exe == key:
                    return a.id
        if lname:
            for a in self.apps.values():
                if a.name.lower() == lname:
                    return a.id
            for a in self.apps.values():
                if len(lname) >= 4 and lname in a.name.lower():
                    return a.id
        return None

    def launch(self, app_id_: str | None, target: str | None) -> None:
        entry = self.apps.get(app_id_ or "")
        spec = target or (entry.target if entry else None)
        if not spec:
            raise RuntimeError("application not found; re-select it in the app")
        self.provider.launch(spec)


def _exe_key(exe: str) -> str:
    base = exe.replace("\\", "/").rsplit("/", 1)[-1].lower()
    return base[:-4] if base.endswith(".exe") else base


def has_tool(name: str) -> bool:
    return shutil.which(name) is not None
