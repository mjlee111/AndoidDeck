"""Windows: Start menu apps (desktop + Store) via shell:AppsFolder, icons via PowerShell."""

from __future__ import annotations

import json
import logging
import os
import shutil
import subprocess
from pathlib import Path

from . import Provider

log = logging.getLogger(__name__)

CREATE_NO_WINDOW = 0x08000000
_SKIP_WORDS = ("uninstall", "제거", "readme", "release notes")


class WindowsProvider(Provider):
    def scan(self, icon_dir: Path) -> list[tuple[str, str, Path | None, str | None]]:
        try:
            return self._scan_apps_folder(icon_dir)
        except Exception as e:  # noqa: BLE001
            log.warning("AppsFolder scan failed (%s); falling back to Start menu shortcuts", e)
            return self._scan_start_menu()

    def _scan_apps_folder(self, icon_dir: Path) -> list[tuple[str, str, Path | None, str | None]]:
        script = Path(__file__).with_name("windows_apps.ps1")
        work = icon_dir.parent / "win_scan"
        shutil.rmtree(work, ignore_errors=True)
        work.mkdir(parents=True, exist_ok=True)
        r = subprocess.run(
            ["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
             "-File", str(script), "-OutDir", str(work)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=300, creationflags=CREATE_NO_WINDOW,
        )
        index = work / "apps.json"
        if r.returncode != 0 or not index.exists():
            raise RuntimeError(r.stderr.strip()[:500] or f"exit code {r.returncode}")
        items = json.loads(index.read_text(encoding="utf-8-sig"))
        if isinstance(items, dict):
            items = [items]
        out: list[tuple[str, str, Path | None, str | None]] = []
        for it in items:
            name = (it.get("name") or "").strip()
            path = (it.get("path") or "").strip()
            if not name or not path or any(w in name.lower() for w in _SKIP_WORDS):
                continue
            icon = work / it["icon"] if it.get("icon") else None
            exe = path if path.lower().endswith(".exe") else None
            out.append((name, f"aumid:{path}", icon, exe))
        return out

    @staticmethod
    def _scan_start_menu() -> list[tuple[str, str, Path | None, str | None]]:
        roots = [
            Path(os.environ.get("ProgramData", r"C:\ProgramData")) / r"Microsoft\Windows\Start Menu\Programs",
            Path(os.environ.get("APPDATA", "")) / r"Microsoft\Windows\Start Menu\Programs",
        ]
        out: list[tuple[str, str, Path | None, str | None]] = []
        for root in roots:
            if not root.is_dir():
                continue
            for lnk in root.rglob("*.lnk"):
                name = lnk.stem
                if any(w in name.lower() for w in _SKIP_WORDS):
                    continue
                out.append((name, f"file:{lnk}", None, None))
        return out

    def launch(self, target: str) -> None:
        kind, _, value = target.partition(":")
        if kind == "aumid":
            # Paths like C:\...\app.exe are also valid AppsFolder ids; explorer resolves both.
            subprocess.Popen(["explorer.exe", f"shell:AppsFolder\\{value}"], creationflags=CREATE_NO_WINDOW)
            return
        path = value if kind == "file" else target
        os.startfile(path)  # type: ignore[attr-defined]
