"""Now-playing information and media transport control.

Linux: MPRIS over the session bus (busctl JSON, gdbus fallback for commands).
Windows: Global System Media Transport Controls via pywinrt; commands use media keys.
macOS: AppleScript for Spotify and Music (no public system-wide API); commands use media keys.
"""

from __future__ import annotations

import hashlib
import json
import logging
import shutil
import subprocess
import sys
import threading
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

log = logging.getLogger(__name__)

KEYS = {"play_pause": "media_play_pause", "next": "media_next", "previous": "media_previous", "stop": "media_stop"}
MPRIS_METHODS = {"play_pause": "PlayPause", "next": "Next", "previous": "Previous", "stop": "Stop"}
MAX_ART_BYTES = 4 * 1024 * 1024

EMPTY: dict[str, Any] = {"available": False, "playing": False, "title": None, "artist": None, "album": None,
                         "source": None, "artUrl": None}


def sniff_image_type(data: bytes) -> str:
    if data.startswith(b"\x89PNG"):
        return "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[:3] == b"GIF":
        return "image/gif"
    return "application/octet-stream"


class Provider:
    def snapshot(self) -> tuple[dict[str, Any], bytes | None]:
        """Returns (info, local art bytes). info['artUrl'] is set only for internet URLs."""
        return dict(EMPTY), None


class MprisProvider(Provider):
    def __init__(self) -> None:
        self.busctl = shutil.which("busctl")
        if not self.busctl:
            raise RuntimeError("busctl not found")
        self.active: str | None = None

    def _bus(self, *args: str) -> Any:
        r = subprocess.run([self.busctl, "--user", "--json=short", *args], capture_output=True, text=True, timeout=3)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip())
        return json.loads(r.stdout or "null")

    def players(self) -> list[str]:
        names = [e.get("name", "") for e in self._bus("list") or []]
        return [n for n in names if n.startswith("org.mpris.MediaPlayer2.")]

    @staticmethod
    def _val(v: Any) -> Any:
        """Unwrap busctl's {"type":..., "data":...} variant encoding."""
        while isinstance(v, dict) and "type" in v and "data" in v:
            v = v["data"]
        return v

    def _props(self, player: str) -> dict[str, Any]:
        reply = self._bus("call", player, "/org/mpris/MediaPlayer2", "org.freedesktop.DBus.Properties",
                          "GetAll", "s", "org.mpris.MediaPlayer2.Player")
        data = (reply or {}).get("data") or [{}]
        return {k: self._val(v) for k, v in (data[0] or {}).items()}

    def snapshot(self) -> tuple[dict[str, Any], bytes | None]:
        best: tuple[str, dict[str, Any]] | None = None
        for player in self.players():
            try:
                props = self._props(player)
            except Exception as e:  # noqa: BLE001
                log.debug("mpris %s: %s", player, e)
                continue
            if props.get("PlaybackStatus") == "Playing":
                best = (player, props)
                break
            if best is None and props.get("Metadata"):
                best = (player, props)
        if best is None:
            self.active = None
            return dict(EMPTY), None
        player, props = best
        self.active = player
        meta = {k: self._val(v) for k, v in (props.get("Metadata") or {}).items()}
        artist = meta.get("xesam:artist")
        if isinstance(artist, list):
            artist = ", ".join(str(a) for a in artist)
        info = {
            "available": True,
            "playing": props.get("PlaybackStatus") == "Playing",
            "title": meta.get("xesam:title") or None,
            "artist": artist or None,
            "album": meta.get("xesam:album") or None,
            "source": player.removeprefix("org.mpris.MediaPlayer2.").split(".")[0],
            "artUrl": None,
        }
        art_bytes = None
        art = str(meta.get("mpris:artUrl") or "")
        if art.startswith(("http://", "https://")):
            info["artUrl"] = art
        elif art.startswith("file://"):
            path = Path(unquote(urlparse(art).path))
            try:
                if path.is_file() and path.stat().st_size <= MAX_ART_BYTES:
                    art_bytes = path.read_bytes()
            except OSError:
                pass
        return info, art_bytes

    def command(self, key: str) -> bool:
        player = self.active or next(iter(self.players()), None)
        if player is None:
            return False
        r = subprocess.run(
            [self.busctl, "--user", "call", player, "/org/mpris/MediaPlayer2",
             "org.mpris.MediaPlayer2.Player", MPRIS_METHODS[key]],
            capture_output=True, timeout=3,
        )
        return r.returncode == 0


class WindowsProvider(Provider):
    """GSMTC through pywinrt. Each poll runs a short-lived event loop on the polling thread."""

    def __init__(self) -> None:
        from winrt.windows.media.control import (  # type: ignore  # noqa: F401
            GlobalSystemMediaTransportControlsSessionManager,
        )

    async def _snapshot(self) -> tuple[dict[str, Any], bytes | None]:
        from winrt.windows.media.control import (  # type: ignore
            GlobalSystemMediaTransportControlsSessionManager as Manager,
            GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
        )

        mgr = await Manager.request_async()
        session = mgr.get_current_session()
        if session is None:
            return dict(EMPTY), None
        props = await session.try_get_media_properties_async()
        playback = session.get_playback_info()
        info = {
            "available": True,
            "playing": playback is not None and playback.playback_status == Status.PLAYING,
            "title": props.title or None,
            "artist": props.artist or None,
            "album": props.album_title or None,
            "source": pretty_source(session.source_app_user_model_id or ""),
            "artUrl": None,
        }
        art = None
        try:
            if props.thumbnail is not None:
                from winrt.windows.storage.streams import DataReader  # type: ignore

                stream = await props.thumbnail.open_read_async()
                size = int(stream.size)
                if 0 < size <= MAX_ART_BYTES:
                    reader = DataReader(stream.get_input_stream_at(0))
                    await reader.load_async(size)
                    buf = bytearray(size)
                    reader.read_bytes(buf)
                    art = bytes(buf)
        except Exception as e:  # noqa: BLE001
            log.debug("thumbnail read failed: %s", e)
        return info, art

    def snapshot(self) -> tuple[dict[str, Any], bytes | None]:
        import asyncio

        return asyncio.run(self._snapshot())


def pretty_source(aumid: str) -> str:
    # e.g. "Spotify.exe", "SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify", "Chrome"
    name = aumid.split("!")[-1].rsplit("\\", 1)[-1]
    return name[:-4] if name.lower().endswith(".exe") else name


_MAC_SCRIPT = """
tell application "{app}"
    if player state is stopped then return ""
    set t to current track
    set art to ""
    {art_line}
    return (player state as text) & tab & (name of t) & tab & (artist of t) & tab & (album of t) & tab & art
end tell
"""


class MacProvider(Provider):
    APPS = {"Spotify": "set art to artwork url of t", "Music": ""}

    def snapshot(self) -> tuple[dict[str, Any], bytes | None]:
        for app, art_line in self.APPS.items():
            if not self._running(app):
                continue
            try:
                r = subprocess.run(["osascript", "-e", _MAC_SCRIPT.format(app=app, art_line=art_line)],
                                   capture_output=True, text=True, timeout=4)
            except subprocess.TimeoutExpired:
                continue
            parts = r.stdout.rstrip("\n").split("\t")
            if r.returncode != 0 or len(parts) < 5:
                continue
            state, title, artist, album, art = parts[:5]
            return {
                "available": True, "playing": state == "playing", "title": title or None,
                "artist": artist or None, "album": album or None, "source": app,
                "artUrl": art if art.startswith("http") else None,
            }, None
        return dict(EMPTY), None

    @staticmethod
    def _running(app: str) -> bool:
        try:
            return subprocess.run(["pgrep", "-xq", app], timeout=2).returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            return False


def _make_provider() -> Provider:
    try:
        if sys.platform == "win32":
            return WindowsProvider()
        if sys.platform == "darwin":
            return MacProvider()
        return MprisProvider()
    except Exception as e:  # noqa: BLE001
        log.info("Now-playing unavailable: %s", e)
        return Provider()


class MediaService:
    def __init__(self, key_combo=None) -> None:
        self.provider = _make_provider()
        self.supported = type(self.provider) is not Provider
        self._key_combo = key_combo
        self._lock = threading.Lock()
        self.art: bytes | None = None
        self.art_type = "application/octet-stream"
        self.art_key: str | None = None

    def poll(self) -> dict[str, Any]:
        """Blocking; call from a worker thread."""
        try:
            info, art = self.provider.snapshot()
        except Exception as e:  # noqa: BLE001
            log.debug("now-playing poll failed: %s", e)
            info, art = dict(EMPTY), None
        with self._lock:
            if art:
                key = hashlib.sha1(art).hexdigest()[:12]
                if key != self.art_key:
                    self.art, self.art_type, self.art_key = art, sniff_image_type(art), key
                info["art"] = f"/api/media/art?k={self.art_key}"
            else:
                self.art, self.art_key = None, None
                info["art"] = info.get("artUrl")
        info.pop("artUrl", None)
        return info

    def control(self, key: str) -> None:
        if key not in KEYS:
            raise ValueError(f"unknown media key: {key}")
        if isinstance(self.provider, MprisProvider):
            try:
                if self.provider.command(key):
                    return
            except Exception as e:  # noqa: BLE001
                log.debug("mpris command failed: %s", e)
        if self._key_combo is None:
            raise RuntimeError("no media control available")
        self._key_combo([KEYS[key]])

