"""Start-at-login registration: Windows Run key, macOS LaunchAgent, Linux XDG autostart."""

from __future__ import annotations

import os
import plistlib
import subprocess
import sys
from pathlib import Path

APP_NAME = "AndroidDeck"
MAC_LABEL = "dev.menggu.androiddeck.server"


def launch_command() -> list[str]:
    """Command that starts the server in tray mode, for the current installation."""
    if getattr(sys, "frozen", False):
        exe = sys.executable
        # Inside a macOS .app bundle, launch the bundle binary itself.
        return [exe, "--tray"]
    py = sys.executable
    if sys.platform == "win32":
        pyw = Path(py).with_name("pythonw.exe")
        if pyw.exists():
            py = str(pyw)  # no console window at login
    runner = Path(__file__).resolve().parent.parent / "run_server.py"
    return [py, str(runner), "--tray"]


def _linux_file() -> Path:
    base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / "autostart" / "androiddeck.desktop"


def _mac_file() -> Path:
    return Path.home() / "Library" / "LaunchAgents" / f"{MAC_LABEL}.plist"


def _desktop_quote(arg: str) -> str:
    # Desktop Entry spec: quote with double quotes; escape " ` $ \ inside.
    if arg and not any(ch in arg for ch in ' \t\n"\'\\><~|&;$*?#()`'):
        return arg
    escaped = "".join("\\" + ch if ch in '"`$\\' else ch for ch in arg)
    return f'"{escaped}"'


def is_enabled() -> bool:
    if sys.platform == "win32":
        import winreg  # type: ignore

        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as k:
                winreg.QueryValueEx(k, APP_NAME)
                return True
        except OSError:
            return False
    if sys.platform == "darwin":
        return _mac_file().exists()
    return _linux_file().exists()


def set_enabled(on: bool) -> None:
    cmd = launch_command()
    if sys.platform == "win32":
        import winreg  # type: ignore

        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run",
                            0, winreg.KEY_SET_VALUE) as k:
            if on:
                winreg.SetValueEx(k, APP_NAME, 0, winreg.REG_SZ, subprocess.list2cmdline(cmd))
            else:
                try:
                    winreg.DeleteValue(k, APP_NAME)
                except FileNotFoundError:
                    pass
        return
    if sys.platform == "darwin":
        path = _mac_file()
        if on:
            path.parent.mkdir(parents=True, exist_ok=True)
            with open(path, "wb") as f:
                plistlib.dump({
                    "Label": MAC_LABEL,
                    "ProgramArguments": cmd,
                    "RunAtLoad": True,
                    "ProcessType": "Interactive",
                }, f)
        else:
            path.unlink(missing_ok=True)
        return
    path = _linux_file()
    if on:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            "[Desktop Entry]\n"
            "Type=Application\n"
            f"Name={APP_NAME} Server\n"
            "Comment=Control this PC from the AndroidDeck phone app\n"
            f"Exec={' '.join(_desktop_quote(a) for a in cmd)}\n"
            "Terminal=false\n"
            "NoDisplay=true\n"
            "X-GNOME-Autostart-enabled=true\n",
            encoding="utf-8",
        )
    else:
        path.unlink(missing_ok=True)
