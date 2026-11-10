"""Output volume, microphone and per-app mixer. Everything runs on one dedicated worker thread
because Windows Core Audio objects must stay on the COM-initialized thread that created them."""

from __future__ import annotations

import asyncio
import json
import logging
import re
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from typing import Any, Callable, Optional, Tuple

log = logging.getLogger(__name__)

State = Tuple[float, bool]  # (level 0..1, muted)
OUTPUT, INPUT = "output", "input"


def _run(args: list[str], timeout: float = 3.0) -> str:
    kwargs: dict[str, Any] = {}
    if sys.platform == "win32":
        kwargs["creationflags"] = 0x08000000  # CREATE_NO_WINDOW
    r = subprocess.run(args, capture_output=True, text=True, timeout=timeout, **kwargs)
    if r.returncode != 0:
        raise RuntimeError(f"{args[0]} failed: {r.stderr.strip() or r.returncode}")
    return r.stdout


def _clamp(v: float) -> float:
    return max(0.0, min(1.0, v))


def pretty_app_name(raw: str) -> str:
    name = raw.rsplit("\\", 1)[-1].rsplit("/", 1)[-1]
    if name.lower().endswith(".exe"):
        name = name[:-4]
    return name[:1].upper() + name[1:] if name else raw


# ---- endpoint backends (default speaker / default microphone) ---------------------------------

class Endpoint:
    name = "none"

    def get(self) -> State:
        raise NotImplementedError

    def set_level(self, level: float) -> None:
        raise NotImplementedError

    def set_mute(self, muted: bool) -> None:
        raise NotImplementedError


class WpctlEndpoint(Endpoint):
    name = "wpctl"

    def __init__(self, kind: str) -> None:
        if not shutil.which("wpctl"):
            raise RuntimeError("wpctl not found")
        self.target = "@DEFAULT_AUDIO_SINK@" if kind == OUTPUT else "@DEFAULT_AUDIO_SOURCE@"
        self.get()

    def get(self) -> State:
        out = _run(["wpctl", "get-volume", self.target])
        m = re.search(r"Volume:\s*([\d.]+)", out)
        if not m:
            raise RuntimeError(f"unexpected wpctl output: {out!r}")
        return _clamp(float(m.group(1))), "[MUTED]" in out

    def set_level(self, level: float) -> None:
        _run(["wpctl", "set-volume", self.target, f"{_clamp(level):.3f}"])

    def set_mute(self, muted: bool) -> None:
        _run(["wpctl", "set-mute", self.target, "1" if muted else "0"])


class PactlEndpoint(Endpoint):
    name = "pactl"

    def __init__(self, kind: str) -> None:
        if not shutil.which("pactl"):
            raise RuntimeError("pactl not found")
        self.obj = "sink" if kind == OUTPUT else "source"
        self.target = "@DEFAULT_SINK@" if kind == OUTPUT else "@DEFAULT_SOURCE@"
        self.get()

    def get(self) -> State:
        vol = _run(["pactl", f"get-{self.obj}-volume", self.target])
        m = re.search(r"(\d+)%", vol)
        if not m:
            raise RuntimeError(f"unexpected pactl output: {vol!r}")
        mute = _run(["pactl", f"get-{self.obj}-mute", self.target])
        return _clamp(int(m.group(1)) / 100.0), "yes" in mute.lower()

    def set_level(self, level: float) -> None:
        _run(["pactl", f"set-{self.obj}-volume", self.target, f"{round(_clamp(level) * 100)}%"])

    def set_mute(self, muted: bool) -> None:
        _run(["pactl", f"set-{self.obj}-mute", self.target, "1" if muted else "0"])


class AmixerEndpoint(Endpoint):
    name = "amixer"

    def __init__(self, kind: str) -> None:
        if not shutil.which("amixer"):
            raise RuntimeError("amixer not found")
        self.control = "Master" if kind == OUTPUT else "Capture"
        self.on, self.off = ("unmute", "mute") if kind == OUTPUT else ("cap", "nocap")
        self.get()

    def get(self) -> State:
        out = _run(["amixer", "sget", self.control])
        m = re.search(r"\[(\d+)%\]", out)
        if not m:
            raise RuntimeError(f"amixer: {self.control} not found")
        return _clamp(int(m.group(1)) / 100.0), "[off]" in out

    def set_level(self, level: float) -> None:
        _run(["amixer", "-q", "sset", self.control, f"{round(_clamp(level) * 100)}%"])

    def set_mute(self, muted: bool) -> None:
        _run(["amixer", "-q", "sset", self.control, self.off if muted else self.on])


class MacEndpoint(Endpoint):
    """macOS has no input mute; muting the microphone sets input volume to 0 and restores it."""

    name = "osascript"

    def __init__(self, kind: str) -> None:
        self.kind = kind
        self._restore = 0.7
        self.get()

    def get(self) -> State:
        prop = "output volume" if self.kind == OUTPUT else "input volume"
        script = ["-e", "set s to get volume settings"]
        if self.kind == OUTPUT:
            script += ["-e", f'return ({prop} of s as text) & "," & (output muted of s as text)']
        else:
            script += ["-e", f"return ({prop} of s as text)"]
        out = _run(["osascript", *script]).strip()
        parts = out.split(",", 1)
        if parts[0] == "missing value":
            raise RuntimeError("device has no volume control")
        level = _clamp(int(parts[0]) / 100.0)
        muted = parts[1].strip() == "true" if self.kind == OUTPUT else level == 0.0
        return level, muted

    def set_level(self, level: float) -> None:
        prop = "output volume" if self.kind == OUTPUT else "input volume"
        _run(["osascript", "-e", f"set volume {prop} {round(_clamp(level) * 100)}"])

    def set_mute(self, muted: bool) -> None:
        if self.kind == OUTPUT:
            _run(["osascript", "-e", f"set volume output muted {'true' if muted else 'false'}"])
            return
        level, _ = self.get()
        if muted:
            if level > 0:
                self._restore = level
            self.set_level(0.0)
        else:
            self.set_level(self._restore if level == 0 else level)


class WindowsEndpoint(Endpoint):
    name = "pycaw"

    def __init__(self, kind: str) -> None:
        from ctypes import POINTER, cast

        from comtypes import CLSCTX_ALL  # type: ignore
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume  # type: ignore

        dev = AudioUtilities.GetSpeakers() if kind == OUTPUT else AudioUtilities.GetMicrophone()
        if dev is None:
            raise RuntimeError(f"no default {kind} device")
        ev = getattr(dev, "EndpointVolume", None)  # pycaw >= 2025 wraps the device
        if ev is None:
            iface = dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            ev = cast(iface, POINTER(IAudioEndpointVolume))
        self._ev = ev
        self.get()

    def get(self) -> State:
        return _clamp(float(self._ev.GetMasterVolumeLevelScalar())), bool(self._ev.GetMute())

    def set_level(self, level: float) -> None:
        self._ev.SetMasterVolumeLevelScalar(_clamp(level), None)

    def set_mute(self, muted: bool) -> None:
        self._ev.SetMute(1 if muted else 0, None)


def _endpoint_candidates() -> list[type[Endpoint]]:
    if sys.platform == "win32":
        return [WindowsEndpoint]
    if sys.platform == "darwin":
        return [MacEndpoint]
    return [WpctlEndpoint, PactlEndpoint, AmixerEndpoint]


# ---- per-application mixer --------------------------------------------------------------------

class Mixer:
    """Sessions are grouped by application; ids are stable names, not per-stream handles."""

    name = "none"

    def sessions(self) -> list[dict[str, Any]]:
        raise NotImplementedError

    def set_level(self, sid: str, level: float) -> None:
        raise NotImplementedError

    def set_mute(self, sid: str, muted: bool) -> None:
        raise NotImplementedError


class WindowsMixer(Mixer):
    name = "pycaw"

    def __init__(self) -> None:
        from pycaw.pycaw import AudioUtilities  # type: ignore

        self._au = AudioUtilities
        self.sessions()

    def _grouped(self) -> dict[str, list[Any]]:
        groups: dict[str, list[Any]] = {}
        for s in self._au.GetAllSessions():
            proc = getattr(s, "Process", None)
            key = proc.name().lower() if proc is not None else "system"
            groups.setdefault(key, []).append(s)
        return groups

    def sessions(self) -> list[dict[str, Any]]:
        out = []
        for key, items in self._grouped().items():
            vol = items[0].SimpleAudioVolume
            out.append({
                "id": key,
                "name": "System sounds" if key == "system" else pretty_app_name(key),
                "exe": key.removesuffix(".exe"),
                "level": round(_clamp(float(vol.GetMasterVolume())), 3),
                "muted": bool(vol.GetMute()),
            })
        return out

    def set_level(self, sid: str, level: float) -> None:
        for s in self._grouped().get(sid, []):
            s.SimpleAudioVolume.SetMasterVolume(_clamp(level), None)

    def set_mute(self, sid: str, muted: bool) -> None:
        for s in self._grouped().get(sid, []):
            s.SimpleAudioVolume.SetMute(1 if muted else 0, None)


class PipeWireMixer(Mixer):
    """Lists playback streams with pw-dump; wpctl applies changes (same cubic scale it reports)."""

    name = "pipewire"

    def __init__(self) -> None:
        if not (shutil.which("pw-dump") and shutil.which("wpctl")):
            raise RuntimeError("pw-dump/wpctl not found")
        self.sessions()

    def _streams(self) -> dict[str, dict[str, Any]]:
        groups: dict[str, dict[str, Any]] = {}
        for obj in json.loads(_run(["pw-dump"], timeout=5) or "[]"):
            if obj.get("type") != "PipeWire:Interface:Node":
                continue
            info = obj.get("info") or {}
            props = info.get("props") or {}
            if props.get("media.class") != "Stream/Output/Audio":
                continue
            name = props.get("application.name") or props.get("application.process.binary") or props.get("node.name") or "?"
            p = ((info.get("params") or {}).get("Props") or [{}])[0]
            vols = p.get("channelVolumes") or [1.0]
            g = groups.setdefault(name, {
                "id": name,
                "name": pretty_app_name(name),
                "exe": (props.get("application.process.binary") or "").lower(),
                "level": round(_clamp((sum(vols) / len(vols)) ** (1 / 3)), 3),
                "muted": bool(p.get("mute", False)),
                "nodes": [],
            })
            g["nodes"].append(str(obj.get("id")))
        return groups

    def sessions(self) -> list[dict[str, Any]]:
        return [{k: v for k, v in g.items() if k != "nodes"} for g in self._streams().values()]

    def set_level(self, sid: str, level: float) -> None:
        for node in self._streams().get(sid, {}).get("nodes", []):
            _run(["wpctl", "set-volume", node, f"{_clamp(level):.3f}"])

    def set_mute(self, sid: str, muted: bool) -> None:
        for node in self._streams().get(sid, {}).get("nodes", []):
            _run(["wpctl", "set-mute", node, "1" if muted else "0"])


class PactlMixer(Mixer):
    name = "pactl"

    def __init__(self) -> None:
        if not shutil.which("pactl"):
            raise RuntimeError("pactl not found")
        self.sessions()

    def _inputs(self) -> dict[str, dict[str, Any]]:
        groups: dict[str, dict[str, Any]] = {}
        for si in json.loads(_run(["pactl", "-f", "json", "list", "sink-inputs"]) or "[]"):
            props = si.get("properties") or {}
            name = props.get("application.name") or props.get("application.process.binary") or "?"
            vols = [int(str(c.get("value_percent", "100%")).rstrip("%")) for c in (si.get("volume") or {}).values()] or [100]
            g = groups.setdefault(name, {
                "id": name,
                "name": pretty_app_name(name),
                "exe": (props.get("application.process.binary") or "").lower(),
                "level": round(_clamp(sum(vols) / len(vols) / 100.0), 3),
                "muted": bool(si.get("mute", False)),
                "nodes": [],
            })
            g["nodes"].append(str(si.get("index")))
        return groups

    def sessions(self) -> list[dict[str, Any]]:
        return [{k: v for k, v in g.items() if k != "nodes"} for g in self._inputs().values()]

    def set_level(self, sid: str, level: float) -> None:
        for idx in self._inputs().get(sid, {}).get("nodes", []):
            _run(["pactl", "set-sink-input-volume", idx, f"{round(_clamp(level) * 100)}%"])

    def set_mute(self, sid: str, muted: bool) -> None:
        for idx in self._inputs().get(sid, {}).get("nodes", []):
            _run(["pactl", "set-sink-input-mute", idx, "1" if muted else "0"])


def _mixer_candidates() -> list[type[Mixer]]:
    if sys.platform == "win32":
        return [WindowsMixer]
    if sys.platform == "darwin":
        return []  # no public per-app volume API
    return [PipeWireMixer, PactlMixer]


# ---- async facade -----------------------------------------------------------------------------

class AudioService:
    """Async facade over speaker, microphone and mixer. Falls back to media keys for the speaker."""

    def __init__(self, key_combo: Callable[[list[str]], None] | None = None) -> None:
        self._pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="audio")
        self.output: Endpoint | None = None
        self.mic: Endpoint | None = None
        self.mixer: Mixer | None = None
        self._key_combo = key_combo
        self._pending: dict[tuple[str, str], float] = {}
        self._drain_task: asyncio.Task | None = None
        self._ready = self._pool.submit(self._init)

    def _init(self) -> None:
        if sys.platform == "win32":
            import comtypes  # type: ignore

            comtypes.CoInitialize()
        self.output = self._first(_endpoint_candidates(), OUTPUT)
        self.mic = self._first(_endpoint_candidates(), INPUT)
        for cls in _mixer_candidates():
            try:
                self.mixer = cls()
                break
            except Exception as e:  # noqa: BLE001
                log.debug("mixer %s unavailable: %s", cls.name, e)
        log.info(
            "Audio: output=%s mic=%s mixer=%s",
            self.output.name if self.output else "media keys",
            self.mic.name if self.mic else "none",
            self.mixer.name if self.mixer else "none",
        )

    @staticmethod
    def _first(classes: list[type[Endpoint]], kind: str) -> Endpoint | None:
        for cls in classes:
            try:
                return cls(kind)
            except Exception as e:  # noqa: BLE001
                log.debug("%s %s unavailable: %s", kind, cls.name, e)
        return None

    @property
    def ready(self) -> bool:
        return self._ready.done()

    @property
    def available(self) -> bool:
        return self.ready and self.output is not None

    async def _call(self, fn, *args):
        await asyncio.wrap_future(self._ready)
        return await asyncio.get_running_loop().run_in_executor(self._pool, fn, *args)

    async def _state(self, ep: Endpoint | None) -> dict[str, Any]:
        if ep is None:
            return {"available": False, "level": None, "muted": None}
        try:
            level, muted = await self._call(ep.get)
            return {"available": True, "level": round(level, 3), "muted": muted}
        except Exception as e:  # noqa: BLE001
            log.debug("audio get failed: %s", e)
            return {"available": False, "level": None, "muted": None}

    async def get(self) -> dict[str, Any]:
        await asyncio.wrap_future(self._ready)
        return await self._state(self.output)

    async def get_mic(self) -> dict[str, Any]:
        await asyncio.wrap_future(self._ready)
        return await self._state(self.mic)

    async def mixer_sessions(self) -> dict[str, Any]:
        await asyncio.wrap_future(self._ready)
        if self.mixer is None:
            return {"available": False, "sessions": []}
        try:
            return {"available": True, "sessions": await self._call(self.mixer.sessions)}
        except Exception as e:  # noqa: BLE001
            log.debug("mixer list failed: %s", e)
            return {"available": False, "sessions": []}

    def _endpoint(self, which: str) -> Endpoint:
        ep = self.output if which == OUTPUT else self.mic
        if ep is None:
            raise RuntimeError("volume control is not available on this PC" if which == OUTPUT
                               else "no microphone control on this PC")
        return ep

    # Slider drags send many values; keep only the newest per target and apply when free.
    async def set_level_coalesced(self, which: str, level: float, target: str = "") -> None:
        await asyncio.wrap_future(self._ready)
        if which == "mixer":
            if self.mixer is None:
                raise RuntimeError("per-app volume is not available on this PC")
        else:
            self._endpoint(which)
        self._pending[(which, target)] = level
        if self._drain_task is None or self._drain_task.done():
            self._drain_task = asyncio.get_running_loop().create_task(self._drain())

    async def _drain(self) -> None:
        while self._pending:
            (which, target), level = self._pending.popitem()
            try:
                if which == "mixer":
                    await self._call(self.mixer.set_level, target, level)  # type: ignore[union-attr]
                else:
                    await self.set_level(which, level)
            except Exception as e:  # noqa: BLE001
                log.warning("volume set failed: %s", e)

    async def set_level(self, which: str, level: float) -> None:
        ep = self._endpoint(which)
        await self._call(ep.set_level, _clamp(level))
        if level > 0:  # moving a slider up implies wanting to hear it
            _, muted = await self._call(ep.get)
            if muted:
                await self._call(ep.set_mute, False)

    async def step(self, which: str, delta: float) -> None:
        await asyncio.wrap_future(self._ready)
        if which == OUTPUT and self.output is None:
            await self._keys(["volume_up" if delta > 0 else "volume_down"])
            return
        ep = self._endpoint(which)
        level, _ = await self._call(ep.get)
        await self.set_level(which, _clamp(level + delta))

    async def set_mute(self, which: str, muted: Optional[bool]) -> None:
        """muted=None toggles."""
        await asyncio.wrap_future(self._ready)
        if which == OUTPUT and self.output is None:
            await self._keys(["volume_mute"])
            return
        ep = self._endpoint(which)
        if muted is None:
            _, cur = await self._call(ep.get)
            muted = not cur
        await self._call(ep.set_mute, muted)

    async def mixer_mute(self, sid: str, muted: Optional[bool]) -> None:
        await asyncio.wrap_future(self._ready)
        mixer = self.mixer
        if mixer is None:
            raise RuntimeError("per-app volume is not available on this PC")
        if muted is None:
            cur = next((s for s in await self._call(mixer.sessions) if s["id"] == sid), None)
            if cur is None:
                raise RuntimeError("that app is no longer playing audio")
            muted = not cur["muted"]
        await self._call(mixer.set_mute, sid, muted)

    async def _keys(self, combo: list[str]) -> None:
        if self._key_combo is None:
            raise RuntimeError("no volume control available")
        await asyncio.get_running_loop().run_in_executor(None, self._key_combo, combo)
