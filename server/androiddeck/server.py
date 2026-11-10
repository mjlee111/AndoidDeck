"""HTTPS + secure WebSocket API consumed by the Android app."""

from __future__ import annotations

import asyncio
import ipaddress
import json
import logging
import platform
import secrets
import socket
import ssl
import sys
import threading
import time
from typing import Any, Callable

import psutil
from aiohttp import WSMsgType, web

from . import DISCOVERY_PORT, PROTOCOL_VERSION, __version__
from .actions import ActionError, ActionRunner
from .apps import AppIndex
from .audio import AudioService
from .config import Config, config_dir
from .discovery import start_discovery
from .keyboard import make_backend, normalize_combo
from .media import MediaService
from .monitor import Monitor
from .pointer import PointerService, make_mouse_backend
from .profile import ProfileError, ProfileStore
from .tls import ensure_certificate, server_context, short_fingerprint

log = logging.getLogger(__name__)

PUBLIC_PATHS = {"/", "/api/info", "/api/pair"}
PAIR_MAX_FAILURES = 5
PAIR_LOCKOUT_S = 60.0


def os_name() -> str:
    if sys.platform == "win32":
        return "windows"
    if sys.platform == "darwin":
        return "macos"
    return "linux"


def wol_info(local_ip: str | None) -> dict[str, str] | None:
    """MAC and broadcast address of the interface the phone reached us on."""
    if not local_ip:
        return None
    try:
        for addrs in psutil.net_if_addrs().values():
            v4 = next((a for a in addrs if a.family == socket.AF_INET and a.address == local_ip), None)
            if v4 is None:
                continue
            mac = next((a.address for a in addrs if a.family == psutil.AF_LINK), None)
            if not mac:
                return None
            mac = mac.replace("-", ":").lower()
            if mac == "00:00:00:00:00:00":
                return None
            bcast = v4.broadcast
            if not bcast and v4.netmask:
                bcast = str(ipaddress.IPv4Network(f"{local_ip}/{v4.netmask}", strict=False).broadcast_address)
            return {"mac": mac, "broadcast": bcast or "255.255.255.255"}
    except Exception as e:  # noqa: BLE001
        log.debug("wol info failed: %s", e)
    return None


class Client:
    def __init__(self, ws: web.WebSocketResponse, device: str):
        self.ws = ws
        self.device = device
        self.stats = False
        self.mixer = False
        self._lock = asyncio.Lock()

    async def send(self, msg: dict[str, Any]) -> None:
        if self.ws.closed:
            return
        try:
            async with self._lock:
                await self.ws.send_str(json.dumps(msg, separators=(",", ":")))
        except (ConnectionError, RuntimeError):
            pass


class DeckServer:
    def __init__(self, cfg: Config, port: int, discovery: bool = True):
        self.cfg = cfg
        self.port = port
        self.discovery_enabled = discovery
        self.key_path, self.crt_path, self.fingerprint = ensure_certificate(config_dir(), f"AndroidDeck {cfg.name}")
        self.profiles = ProfileStore()
        self.keys = make_backend(cfg.key_backend)
        self.mouse = make_mouse_backend(cfg.key_backend)
        combo = self.keys.combo if self.keys else None
        self.audio = AudioService(combo)
        self.media = MediaService(combo)
        self.apps = AppIndex()
        self.monitor = Monitor()
        self.pointer = PointerService(self.mouse, self.keys)
        self.actions = ActionRunner(self.apps, self.audio, self.media, self.keys, cfg.allow_commands)
        self.clients: dict[web.WebSocketResponse, Client] = {}
        self._pair_failures: dict[str, tuple[int, float]] = {}
        self._last: dict[str, Any] = {}
        self._tasks: list[asyncio.Task] = []
        self._discovery = None
        self.on_paired: Callable[[str], None] | None = None

    # ---- info ---------------------------------------------------------------------------------

    def info(self) -> dict[str, Any]:
        return {
            "id": self.cfg.server_id,
            "name": self.cfg.name,
            "os": os_name(),
            "osVersion": platform.release(),
            "version": __version__,
            "protocol": PROTOCOL_VERSION,
            "port": self.port,
            "tls": True,
            "fp": self.fingerprint,
        }

    def features(self) -> dict[str, Any]:
        return {
            "keys": self.keys.name if self.keys else None,
            "mouse": self.mouse.name if self.mouse else None,
            "volume": self.audio.available,
            "mic": self.audio.ready and self.audio.mic is not None,
            "mixer": self.audio.ready and self.audio.mixer is not None,
            "media": self.media.supported,
            "commands": self.cfg.allow_commands,
        }

    @property
    def short_fingerprint(self) -> str:
        return short_fingerprint(self.fingerprint)

    def ssl_context(self) -> ssl.SSLContext:
        return server_context(self.key_path, self.crt_path)

    # ---- app ----------------------------------------------------------------------------------

    def build_app(self) -> web.Application:
        @web.middleware
        async def auth(request: web.Request, handler):
            if request.path in PUBLIC_PATHS:
                return await handler(request)
            header = request.headers.get("Authorization", "")
            token = header[7:].strip() if header.startswith("Bearer ") else request.query.get("token")
            device = self.cfg.check_token(token)
            if device is None:
                return web.json_response({"error": "unauthorized"}, status=401)
            request["device"] = device
            return await handler(request)

        app = web.Application(middlewares=[auth], client_max_size=2 * 1024 * 1024)
        app.router.add_get("/", self.h_root)
        app.router.add_get("/api/info", self.h_info)
        app.router.add_post("/api/pair", self.h_pair)
        app.router.add_get("/api/apps", self.h_apps)
        app.router.add_get("/api/icon/{id}", self.h_icon)
        app.router.add_get("/api/media/art", self.h_art)
        app.router.add_get("/api/profile", self.h_get_profile)
        app.router.add_put("/api/profile", self.h_put_profile)
        app.router.add_post("/api/action", self.h_action)
        app.router.add_get("/ws", self.h_ws)
        app.on_startup.append(self._on_startup)
        app.on_shutdown.append(self._on_shutdown)
        return app

    async def _on_startup(self, _app: web.Application) -> None:
        self.apps.start_scan()
        if self.discovery_enabled:
            self._discovery = await start_discovery(DISCOVERY_PORT, self.info)
        loop = asyncio.get_running_loop()
        self._tasks = [
            loop.create_task(self._stats_loop()),
            loop.create_task(self._audio_loop()),
            loop.create_task(self._media_loop()),
        ]

    async def _on_shutdown(self, _app: web.Application) -> None:
        for t in self._tasks:
            t.cancel()
        if self._discovery is not None:
            self._discovery.close()
        for ws in list(self.clients):
            await ws.close()
        self.monitor.close()

    # ---- handlers -----------------------------------------------------------------------------

    async def h_root(self, _r: web.Request) -> web.Response:
        return web.Response(text=f"AndroidDeck server {__version__} is running.\n")

    async def h_info(self, _r: web.Request) -> web.Response:
        return web.json_response(self.info())

    async def h_pair(self, request: web.Request) -> web.Response:
        ip = request.remote or "?"
        now = time.monotonic()
        fails, until = self._pair_failures.get(ip, (0, 0.0))
        if until > now:
            return web.json_response(
                {"error": f"Too many attempts. Try again in {int(until - now) + 1}s."}, status=429
            )
        try:
            body = await request.json()
        except (ValueError, UnicodeDecodeError):
            return web.json_response({"error": "invalid JSON"}, status=400)
        pin = str(body.get("pin", "")).strip()
        device = str(body.get("device", "Android"))[:64]
        if not secrets.compare_digest(pin.encode(), self.cfg.pin.encode()):
            fails += 1
            if fails >= PAIR_MAX_FAILURES:
                self._pair_failures[ip] = (0, now + PAIR_LOCKOUT_S)
            else:
                self._pair_failures[ip] = (fails, 0.0)
            log.warning("Wrong pairing PIN from %s", ip)
            return web.json_response({"error": "Wrong PIN"}, status=403)
        self._pair_failures.pop(ip, None)
        token = self.cfg.add_device(device)
        log.info("Paired device '%s' from %s", device, ip)
        if self.on_paired is not None:
            self.on_paired(device)
        return web.json_response({"token": token, "server": self.info(), "wol": wol_info(self._local_ip(request))})

    @staticmethod
    def _local_ip(request: web.Request) -> str | None:
        sock = request.transport.get_extra_info("sockname") if request.transport else None
        return sock[0] if sock else None

    async def h_apps(self, request: web.Request) -> web.Response:
        refresh = request.query.get("refresh") in ("1", "true")
        if refresh or (not self.apps.apps and not self.apps.scanning):
            self.apps.start_scan()
        if refresh or not self.apps.apps:
            await self.apps.wait()
        return web.json_response({"apps": self.apps.list(), "scanning": self.apps.scanning})

    async def h_icon(self, request: web.Request) -> web.StreamResponse:
        path = self.apps.icon_path(request.match_info["id"])
        if path is None:
            return web.json_response({"error": "no icon"}, status=404)
        headers = {"Cache-Control": "private, max-age=86400"}
        if path.suffix == ".svg":
            headers["Content-Type"] = "image/svg+xml"
        return web.FileResponse(path, headers=headers)

    async def h_art(self, _r: web.Request) -> web.Response:
        art = self.media.art
        if art is None:
            return web.json_response({"error": "no artwork"}, status=404)
        return web.Response(body=art, content_type=self.media.art_type,
                            headers={"Cache-Control": "private, max-age=3600"})

    async def h_get_profile(self, _r: web.Request) -> web.Response:
        return web.json_response(self.profiles.get())

    async def h_put_profile(self, request: web.Request) -> web.Response:
        try:
            body = await request.json()
            profile = self.profiles.replace(body)
        except (ValueError, UnicodeDecodeError, ProfileError) as e:
            return web.json_response({"error": str(e)}, status=400)
        await self._broadcast({"t": "profile", "profile": profile})
        return web.json_response(profile)

    async def h_action(self, request: web.Request) -> web.Response:
        """Stateless action endpoint used by the home-screen widget and quick-settings tiles."""
        try:
            action = await request.json()
            await self.actions.run(action)
        except (ValueError, UnicodeDecodeError) as e:
            return web.json_response({"ok": False, "error": str(e)}, status=400)
        except ActionError as e:
            return web.json_response({"ok": False, "error": str(e)}, status=422)
        return web.json_response({"ok": True})

    async def h_ws(self, request: web.Request) -> web.WebSocketResponse:
        ws = web.WebSocketResponse(heartbeat=15, max_msg_size=1 << 20)
        await ws.prepare(request)
        client = Client(ws, request["device"].get("name", "?"))
        log.info("Connected: %s (%s)", client.device, request.remote)
        try:
            volume = await self.audio.get()
            mic = await self.audio.get_mic()
            await client.send({
                "t": "welcome",
                "server": self.info(),
                "features": self.features(),
                "volume": volume,
                "mic": mic,
                "media": self._last.get("media"),
                "wol": wol_info(self._local_ip(request)),
            })
            # Register only after the welcome, so pushes (volume, media, ...) never arrive before it.
            self.clients[ws] = client
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                    except ValueError:
                        continue
                    if isinstance(data, dict):
                        await self._on_message(client, data)
                elif msg.type == WSMsgType.ERROR:
                    break
        finally:
            self.clients.pop(ws, None)
            log.info("Disconnected: %s", client.device)
        return ws

    async def _on_message(self, client: Client, data: dict[str, Any]) -> None:
        t = data.get("t")
        try:
            if t == "sub":
                client.stats = bool(data.get("stats", client.stats))
                was_mixer, client.mixer = client.mixer, bool(data.get("mixer", client.mixer))
                if client.mixer and not was_mixer:
                    await client.send({"t": "mixer", **await self._mixer_state()})
            elif t == "action":
                action = data.get("action")
                if isinstance(action, dict):
                    asyncio.get_running_loop().create_task(self._run_and_ack(client, data.get("id"), action))
            # Trackpad / live keyboard: fire-and-forget, high frequency.
            elif t == "mouse":
                self.pointer.move(float(data.get("dx", 0)), float(data.get("dy", 0)))
            elif t == "scroll":
                self.pointer.scroll(float(data.get("dx", 0)), float(data.get("dy", 0)))
            elif t == "click":
                self.pointer.click(str(data.get("button", "left")), int(data.get("count", 1)))
            elif t == "button":
                self.pointer.button(str(data.get("button", "left")), bool(data.get("down")))
            elif t == "type":
                self.pointer.type_text(str(data.get("text", "")))
            elif t == "key":
                keys = data.get("keys") or []
                if isinstance(keys, list):
                    normalize_combo([str(k) for k in keys])
                    self.pointer.combo([str(k) for k in keys])
            elif t == "ping":
                await client.send({"t": "pong"})
        except (TypeError, ValueError) as e:
            log.debug("bad %s message: %s", t, e)

    async def _run_and_ack(self, client: Client, rid: Any, action: dict[str, Any]) -> None:
        try:
            await self.actions.run(action)
            await client.send({"t": "ack", "id": rid, "ok": True})
        except ActionError as e:
            await client.send({"t": "ack", "id": rid, "ok": False, "error": str(e)})
        if action.get("type") in ("volume", "mic", "mixer", "multi") and action.get("op") != "set":
            await self._push_audio(include_mixer=action.get("type") == "mixer")
        if action.get("type") == "media":
            await asyncio.sleep(0.4)  # players update their state asynchronously
            await self._push_media()

    # ---- push loops ---------------------------------------------------------------------------

    async def _broadcast(self, msg: dict[str, Any], only: Callable[[Client], bool] | None = None) -> None:
        clients = [c for c in self.clients.values() if only is None or only(c)]
        if clients:
            await asyncio.gather(*(c.send(msg) for c in clients))

    async def _stats_loop(self) -> None:
        loop = asyncio.get_running_loop()
        while True:
            await asyncio.sleep(1.0)
            subs = [c for c in self.clients.values() if c.stats]
            if not subs:
                continue
            try:
                stats = await loop.run_in_executor(None, self.monitor.sample)
            except Exception:  # noqa: BLE001
                log.exception("stats sample failed")
                continue
            await asyncio.gather(*(c.send(stats) for c in subs))

    async def _mixer_state(self) -> dict[str, Any]:
        state = await self.audio.mixer_sessions()
        for s in state["sessions"]:
            s["appId"] = self.apps.match(s.pop("exe", None), s.get("name"))
        return state

    async def _push_if_changed(self, key: str, msg: dict[str, Any], only=None) -> None:
        if self._last.get(key) != msg:
            self._last[key] = msg
            await self._broadcast({"t": key, **msg}, only)

    async def _push_audio(self, include_mixer: bool = False) -> None:
        await self._push_if_changed("volume", await self.audio.get())
        await self._push_if_changed("mic", await self.audio.get_mic())
        if include_mixer or any(c.mixer for c in self.clients.values()):
            await self._push_if_changed("mixer", await self._mixer_state(), only=lambda c: c.mixer)

    async def _audio_loop(self) -> None:
        while True:
            await asyncio.sleep(1.0)
            if self.clients:
                await self._push_audio()

    async def _push_media(self) -> None:
        info = await asyncio.get_running_loop().run_in_executor(None, self.media.poll)
        await self._push_if_changed("media", info)

    async def _media_loop(self) -> None:
        while True:
            await asyncio.sleep(2.0)
            if self.clients and self.media.supported:
                await self._push_media()


def serve_in_thread(server: DeckServer, host: str, port: int) -> tuple[threading.Thread, Callable[[], None]]:
    """Runs the server on a background thread (tray mode). Returns (thread, stop function)."""
    stop = threading.Event()
    started = threading.Event()
    errors: list[BaseException] = []

    async def main() -> None:
        runner = web.AppRunner(server.build_app(), access_log=None)
        await runner.setup()
        site = web.TCPSite(runner, host, port, ssl_context=server.ssl_context())
        try:
            await site.start()
        except OSError as e:
            errors.append(e)
            started.set()
            await runner.cleanup()
            return
        started.set()
        while not stop.is_set():
            await asyncio.sleep(0.3)
        await runner.cleanup()

    thread = threading.Thread(target=lambda: asyncio.run(main()), name="server", daemon=True)
    thread.start()
    started.wait(30)
    if errors:
        raise errors[0]
    return thread, stop.set
