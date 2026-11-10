"""Executes button actions sent by the phone."""

from __future__ import annotations

import asyncio
import logging
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from typing import Any

from .apps import AppIndex
from .audio import INPUT, OUTPUT, AudioService
from .keyboard import KeyBackend, normalize_combo
from .media import MediaService

log = logging.getLogger(__name__)


class ActionError(Exception):
    pass


def launch_env() -> dict[str, str] | None:
    """Environment for programs started from a PyInstaller build on Linux (None = inherit)."""
    # The bootloader points LD_LIBRARY_PATH at the bundled libraries; desktop programs must get the
    # original value back, or they may load the wrong libssl/libz and fail to start.
    if not (sys.platform.startswith("linux") and getattr(sys, "frozen", False)):
        return None
    env = dict(os.environ)
    orig = env.pop("LD_LIBRARY_PATH_ORIG", None)
    if orig is not None:
        env["LD_LIBRARY_PATH"] = orig
    else:
        env.pop("LD_LIBRARY_PATH", None)
    return env


def _popen_detached(args: list[str] | str, shell: bool = False) -> None:
    kwargs: dict[str, Any] = {
        "stdin": subprocess.DEVNULL, "stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL,
        "shell": shell, "env": launch_env(),
    }
    if sys.platform == "win32":
        # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
        kwargs["creationflags"] = 0x00000008 | 0x00000200
    else:
        kwargs["start_new_session"] = True
        kwargs["cwd"] = os.path.expanduser("~")
    subprocess.Popen(args, **kwargs)


def open_uri(uri: str) -> None:
    if sys.platform == "win32":
        os.startfile(uri)  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        _popen_detached(["open", uri])
    else:
        _popen_detached(["xdg-open", uri])


def system_op(op: str) -> None:
    if op == "lock":
        if sys.platform == "win32":
            _popen_detached(["rundll32.exe", "user32.dll,LockWorkStation"])
        elif sys.platform == "darwin":
            _popen_detached(["pmset", "displaysleepnow"])
        else:
            for cmd in (["loginctl", "lock-session"], ["xdg-screensaver", "lock"], ["gnome-screensaver-command", "-l"]):
                if shutil.which(cmd[0]):
                    r = subprocess.run(cmd, capture_output=True, timeout=5)
                    if r.returncode == 0:
                        return
            raise ActionError("no screen locker found")
    elif op == "sleep":
        if sys.platform == "win32":
            _popen_detached(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"])
        elif sys.platform == "darwin":
            _popen_detached(["pmset", "sleepnow"])
        else:
            _popen_detached(["systemctl", "suspend"])
    else:
        raise ActionError(f"unknown system op: {op}")


class ActionRunner:
    def __init__(self, apps: AppIndex, audio: AudioService, media: MediaService,
                 keys: KeyBackend | None, allow_commands: bool):
        self.apps = apps
        self.audio = audio
        self.media = media
        self.keys = keys
        self.allow_commands = allow_commands
        # Single worker keeps key sequences from interleaving.
        self._pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="actions")

    async def _blocking(self, fn, *args) -> None:
        await asyncio.get_running_loop().run_in_executor(self._pool, fn, *args)

    def _need_keys(self) -> KeyBackend:
        if self.keys is None:
            raise ActionError("keyboard injection is not available on this PC")
        return self.keys

    async def run(self, a: dict[str, Any]) -> None:
        t = a.get("type")
        try:
            if t == "app":
                await self._blocking(self.apps.launch, a.get("appId"), a.get("target"))
            elif t == "hotkey":
                keys = a.get("keys") or []
                normalize_combo(keys)  # validate early for a clear error
                await self._blocking(self._need_keys().combo, keys)
            elif t == "text":
                await self._blocking(self._need_keys().type_text, str(a.get("text") or ""))
            elif t == "media":
                await self._blocking(self.media.control, str(a.get("key") or ""))
            elif t == "volume":
                await self._level(OUTPUT, a)
            elif t == "mic":
                await self._level(INPUT, a)
            elif t == "mixer":
                await self._mixer(a)
            elif t == "url":
                url = str(a.get("url") or "").strip()
                if not url:
                    raise ActionError("empty URL")
                if "://" not in url and not url.startswith(("mailto:", "steam:", "spotify:")):
                    url = "https://" + url
                await self._blocking(open_uri, url)
            elif t == "command":
                if not self.allow_commands:
                    raise ActionError("command actions are disabled in the server config")
                cmd = str(a.get("command") or "").strip()
                if not cmd:
                    raise ActionError("empty command")
                await self._blocking(_popen_detached, cmd, True)
            elif t == "system":
                await self._blocking(system_op, str(a.get("op") or ""))
            elif t == "multi":
                await self._multi(a.get("steps") or [])
            elif t in ("none", "folder", "wake"):
                return  # folder and wake are handled on the phone
            else:
                raise ActionError(f"unknown action type: {t}")
        except ActionError:
            raise
        except Exception as e:  # noqa: BLE001 - surface platform errors to the phone
            log.warning("action %s failed: %s", t, e)
            raise ActionError(str(e) or e.__class__.__name__) from e

    async def _multi(self, steps: list[Any]) -> None:
        for i, step in enumerate(steps[:16], start=1):
            if not isinstance(step, dict) or step.get("type") in ("multi", "folder"):
                raise ActionError(f"step {i}: not allowed in a multi-action")
            delay = step.get("delay") or 0
            if delay:
                await asyncio.sleep(min(float(delay), 60_000) / 1000)
            try:
                await self.run(step)
            except ActionError as e:
                raise ActionError(f"step {i}: {e}") from e

    async def _level(self, which: str, a: dict[str, Any]) -> None:
        op = a.get("op")
        value = a.get("value")
        if op == "set":
            if value is None:
                raise ActionError("set needs a value")
            await self.audio.set_level_coalesced(which, float(value))
        elif op in ("up", "down"):
            step = abs(float(value)) if value else 0.05
            await self.audio.step(which, step if op == "up" else -step)
        elif op in ("mute", "toggle"):
            await self.audio.set_mute(which, None if value is None else bool(value))
        elif op == "unmute":
            await self.audio.set_mute(which, False)
        else:
            raise ActionError(f"unknown op: {op}")

    async def _mixer(self, a: dict[str, Any]) -> None:
        sid = str(a.get("target") or "")
        if not sid:
            raise ActionError("mixer action needs a target app")
        op = a.get("op")
        if op == "set":
            await self.audio.set_level_coalesced("mixer", float(a.get("value") or 0), target=sid)
        elif op in ("mute", "toggle"):
            value = a.get("value")
            await self.audio.mixer_mute(sid, None if value is None else bool(value))
        else:
            raise ActionError(f"unknown mixer op: {op}")
