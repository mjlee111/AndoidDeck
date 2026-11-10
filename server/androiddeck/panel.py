"""Local control panel: a page on 127.0.0.1 with the pairing PIN, paired phones and start-at-login.

Some desktops show no tray menu (pystray's xorg backend has none), so the PIN must also be reachable
from a normal window. The page is plain HTTP on loopback only, under a random secret path. Its URL is
written to panel.json so that launching the app a second time reopens the panel of the running one.
"""

from __future__ import annotations

import logging
import os
import secrets
import sys
import time
import urllib.request
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable

from aiohttp import web

from . import __version__, autostart
from .actions import open_uri
from .config import config_dir, read_json, write_json_atomic

if TYPE_CHECKING:
    from .server import DeckServer

log = logging.getLogger(__name__)


def panel_file() -> Path:
    return config_dir() / "panel.json"


def running_panel_url(timeout: float = 1.5) -> str | None:
    """URL of the panel of an AndroidDeck server already running for this user, if any."""
    data = read_json(panel_file())
    if not isinstance(data, dict) or not data.get("url"):
        return None
    url = str(data["url"])
    if not url.startswith("http://127.0.0.1:"):
        return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # never via a proxy
    try:
        with opener.open(url + "api/state", timeout=timeout) as r:
            return url if r.status == 200 else None
    except (OSError, ValueError):
        return None


class ControlPanel:
    """Serves the panel on the server's event loop. start()/stop() are called by DeckServer."""

    def __init__(self, server: DeckServer, addresses: Callable[[], list[str]]):
        self.server = server
        self.cfg = server.cfg
        self.addresses = addresses
        self.token = secrets.token_urlsafe(18)
        self.port: int | None = None
        self.on_change: Callable[[], None] | None = None  # e.g. refresh the tray menu
        self._runner: web.AppRunner | None = None

    @property
    def url(self) -> str | None:
        return f"http://127.0.0.1:{self.port}/{self.token}/" if self.port else None

    def open(self) -> None:
        if self.url:
            open_uri(self.url)

    async def start(self) -> None:
        @web.middleware
        async def guard(request: web.Request, handler):
            # Loopback Host only (blocks DNS rebinding); the secret path keeps other sites out.
            host = request.headers.get("Host", "")
            if host not in (f"127.0.0.1:{self.port}", f"localhost:{self.port}"):
                raise web.HTTPForbidden()
            if request.method != "GET":
                origin = request.headers.get("Origin")
                if origin is not None and origin not in (f"http://127.0.0.1:{self.port}", f"http://localhost:{self.port}"):
                    raise web.HTTPForbidden()
            resp = await handler(request)
            resp.headers["Cache-Control"] = "no-store"
            resp.headers["Referrer-Policy"] = "no-referrer"
            return resp

        app = web.Application(middlewares=[guard], client_max_size=16 * 1024)
        base = f"/{self.token}/"
        app.router.add_get(base, self.h_page)
        app.router.add_get(base + "api/state", self.h_state)
        app.router.add_post(base + "api/new-pin", self.h_new_pin)
        app.router.add_post(base + "api/unpair", self.h_unpair)
        app.router.add_post(base + "api/autostart", self.h_autostart)
        app.router.add_post(base + "api/open-folder", self.h_open_folder)
        self._runner = web.AppRunner(app, access_log=None)
        await self._runner.setup()
        site = web.TCPSite(self._runner, "127.0.0.1", 0)
        await site.start()
        self.port = self._runner.addresses[0][1]
        try:
            write_json_atomic(panel_file(), {"url": self.url, "pid": os.getpid()})
            _private(panel_file())
        except OSError as e:
            log.warning("Could not write %s: %s", panel_file(), e)
        log.info("Control panel: %s", self.url)

    async def stop(self) -> None:
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
        data = read_json(panel_file())
        if isinstance(data, dict) and data.get("url") == self.url:
            panel_file().unlink(missing_ok=True)

    def _changed(self) -> None:
        if self.on_change is not None:
            try:
                self.on_change()
            except Exception:  # noqa: BLE001
                log.debug("panel change hook failed", exc_info=True)

    # ---- handlers -----------------------------------------------------------------------------

    def state(self) -> dict[str, Any]:
        devices = sorted(
            ({"id": k, "name": d.get("name") or "Android", "created": d.get("created"),
              "lastSeen": d.get("last_seen")} for k, d in self.cfg.devices.items()),
            key=lambda d: -(d["lastSeen"] or 0),
        )
        online = {id(c.record) for c in self.server.clients.values()}
        for d in devices:
            d["online"] = id(self.cfg.devices.get(d["id"])) in online
        try:
            start_at_login: bool | None = autostart.is_enabled()
        except Exception:  # noqa: BLE001
            start_at_login = None
        return {
            "name": self.cfg.name,
            "pin": self.cfg.pin,
            "port": self.server.port,
            "addresses": self.addresses(),
            "fingerprint": self.server.short_fingerprint,
            "devices": devices,
            "startAtLogin": start_at_login,
            "configDir": str(config_dir()),
            "version": __version__,
            "os": sys.platform,
            "now": int(time.time()),
        }

    async def h_page(self, _r: web.Request) -> web.Response:
        return web.Response(text=PAGE, content_type="text/html", charset="utf-8", headers={
            "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
                                       "connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'",
            "X-Frame-Options": "DENY",
        })

    async def h_state(self, _r: web.Request) -> web.Response:
        return web.json_response(self.state())

    async def h_new_pin(self, _r: web.Request) -> web.Response:
        self.cfg.regenerate_pin()
        log.info("New pairing PIN generated from the control panel")
        self._changed()
        return web.json_response(self.state())

    async def h_unpair(self, request: web.Request) -> web.Response:
        body = await _json(request)
        key = body.get("id")
        if key == "*":
            self.cfg.revoke_all()
        elif not isinstance(key, str) or not self.cfg.revoke(key):
            return web.json_response({"error": "unknown phone"}, status=404)
        self.server.drop_unpaired()
        self._changed()
        return web.json_response(self.state())

    async def h_autostart(self, request: web.Request) -> web.Response:
        body = await _json(request)
        try:
            autostart.set_enabled(bool(body.get("on")))
        except Exception as e:  # noqa: BLE001
            return web.json_response({"error": f"Could not change start-at-login: {e}"}, status=500)
        self._changed()
        return web.json_response(self.state())

    async def h_open_folder(self, _r: web.Request) -> web.Response:
        config_dir().mkdir(parents=True, exist_ok=True)
        try:
            open_uri(str(config_dir()))
        except OSError as e:
            return web.json_response({"error": str(e)}, status=500)
        return web.json_response({"ok": True})


async def _json(request: web.Request) -> dict[str, Any]:
    try:
        body = await request.json()
    except (ValueError, UnicodeDecodeError):
        raise web.HTTPBadRequest() from None
    if not isinstance(body, dict):
        raise web.HTTPBadRequest()
    return body


def _private(path: Path) -> None:
    if sys.platform != "win32":
        try:
            path.chmod(0o600)
        except OSError:
            pass


PAGE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AndroidDeck Server</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='7' fill='%23101114'/%3E%3Cg fill='%234a505e'%3E%3Crect x='5' y='5' width='6' height='6' rx='1.7'/%3E%3Crect x='13' y='5' width='6' height='6' rx='1.7'/%3E%3Crect x='21' y='5' width='6' height='6' rx='1.7'/%3E%3Crect x='5' y='13' width='6' height='6' rx='1.7'/%3E%3Crect x='21' y='13' width='6' height='6' rx='1.7'/%3E%3Crect x='5' y='21' width='6' height='6' rx='1.7'/%3E%3Crect x='13' y='21' width='6' height='6' rx='1.7'/%3E%3Crect x='21' y='21' width='6' height='6' rx='1.7'/%3E%3C/g%3E%3Crect x='13' y='13' width='6' height='6' rx='1.7' fill='%23c6f36b'/%3E%3C/svg%3E">
<style>
:root {
  --bg: #0b0c0e; --surface: #16181c; --surface-high: #1f2228; --outline: #2b2f37;
  --text: #eef0f3; --dim: #9aa1ad; --accent: #c6f36b; --on-accent: #111408;
  --ok: #5fd68a; --danger: #ff6b6b;
}
@media (prefers-color-scheme: light) {
  :root {
    --bg: #f3f4f6; --surface: #ffffff; --surface-high: #eceef2; --outline: #d9dce2;
    --text: #15171b; --dim: #5d6470; --accent: #4f7a00; --on-accent: #ffffff;
    --ok: #198754; --danger: #d63031;
  }
}
* { box-sizing: border-box; }
html, body { margin: 0; background: var(--bg); color: var(--text); }
body {
  font: 15px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans", sans-serif;
  padding: 32px 16px 48px;
}
main { max-width: 560px; margin: 0 auto; display: grid; gap: 14px; }
header { display: flex; align-items: center; gap: 12px; margin-bottom: 6px; }
header .logo { width: 40px; height: 40px; flex: none; }
header h1 { font-size: 20px; margin: 0; font-weight: 650; letter-spacing: -0.01em; }
header .sub { color: var(--dim); font-size: 13px; }
.card { background: var(--surface); border-radius: 22px; padding: 20px 22px; }
.label { color: var(--dim); font-size: 12px; text-transform: uppercase; letter-spacing: 0.08em; font-weight: 600; }
.pin-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-top: 6px; }
.pin { font: 600 44px/1.1 ui-monospace, "SF Mono", "Cascadia Mono", "Roboto Mono", monospace; letter-spacing: 0.08em; }
.pin span + span { margin-left: 0.35em; }
ol { margin: 14px 0 0; padding-left: 20px; color: var(--dim); }
ol li { margin: 4px 0; }
ol b { color: var(--text); font-weight: 600; }
.kv { display: grid; grid-template-columns: max-content 1fr; gap: 8px 16px; margin-top: 10px; }
.kv dt { color: var(--dim); }
.kv dd { margin: 0; font-family: ui-monospace, "SF Mono", "Cascadia Mono", "Roboto Mono", monospace; font-size: 13.5px; overflow-wrap: anywhere; }
button {
  font: inherit; font-weight: 600; border: 0; border-radius: 999px; padding: 9px 16px; cursor: pointer;
  background: var(--surface-high); color: var(--text);
}
button:hover { filter: brightness(1.12); }
button.primary { background: var(--accent); color: var(--on-accent); }
button.danger { background: transparent; color: var(--danger); padding-left: 10px; padding-right: 10px; }
button:disabled { opacity: 0.5; cursor: default; }
.phones { list-style: none; margin: 10px 0 0; padding: 0; }
.phones li { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-top: 1px solid var(--outline); }
.phones li:first-child { border-top: 0; }
.dot { width: 9px; height: 9px; border-radius: 50%; background: var(--outline); flex: none; }
.dot.on { background: var(--ok); }
.phones .grow { flex: 1; min-width: 0; }
.phones .meta { color: var(--dim); font-size: 13px; }
.empty { color: var(--dim); margin-top: 10px; }
.row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.switch { position: relative; width: 46px; height: 28px; flex: none; }
.switch input { opacity: 0; width: 100%; height: 100%; margin: 0; cursor: pointer; position: absolute; z-index: 1; }
.switch span { position: absolute; inset: 0; border-radius: 999px; background: var(--surface-high); border: 1px solid var(--outline); transition: background .15s; }
.switch span::after { content: ""; position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 50%; background: var(--dim); transition: transform .15s, background .15s; }
.switch input:checked + span { background: var(--accent); border-color: var(--accent); }
.switch input:checked + span::after { transform: translateX(18px); background: var(--on-accent); }
.switch input:focus-visible + span { outline: 2px solid var(--accent); outline-offset: 2px; }
.hint { color: var(--dim); font-size: 13px; margin-top: 4px; }
.toast { position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%); background: var(--surface-high); color: var(--text);
  padding: 10px 16px; border-radius: 12px; font-size: 14px; opacity: 0; transition: opacity .2s; pointer-events: none; max-width: calc(100% - 32px); }
.toast.show { opacity: 1; }
.offline { color: var(--danger); }
footer { color: var(--dim); font-size: 12.5px; text-align: center; margin-top: 6px; }
</style>
</head>
<body>
<main>
  <header>
    <svg class="logo" viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="7" fill="#101114"/>
      <g fill="#4a505e"><rect x="5" y="5" width="6" height="6" rx="1.7"/><rect x="13" y="5" width="6" height="6" rx="1.7"/><rect x="21" y="5" width="6" height="6" rx="1.7"/><rect x="5" y="13" width="6" height="6" rx="1.7"/><rect x="21" y="13" width="6" height="6" rx="1.7"/><rect x="5" y="21" width="6" height="6" rx="1.7"/><rect x="13" y="21" width="6" height="6" rx="1.7"/><rect x="21" y="21" width="6" height="6" rx="1.7"/></g>
      <rect x="13" y="13" width="6" height="6" rx="1.7" fill="#c6f36b"/>
    </svg>
    <div>
      <h1 id="name">AndroidDeck</h1>
      <div class="sub" id="status">AndroidDeck server is running</div>
    </div>
  </header>

  <section class="card">
    <div class="label">Pairing PIN</div>
    <div class="pin-row">
      <div class="pin" id="pin">&middot;&middot;&middot; &middot;&middot;&middot;</div>
      <button id="newPin">New PIN</button>
    </div>
    <ol>
      <li>Connect your phone to the <b>same network</b> as this PC.</li>
      <li>Open <b>AndroidDeck</b> on the phone. This PC shows up automatically; tap it.</li>
      <li>Enter the PIN above and check that the phone shows fingerprint <b id="fpInline">&hellip;</b>.</li>
    </ol>
  </section>

  <section class="card">
    <div class="row">
      <div class="label" id="phonesLabel">Paired phones</div>
      <button class="danger" id="unpairAll" hidden>Unpair all</button>
    </div>
    <ul class="phones" id="phones"></ul>
    <div class="empty" id="noPhones" hidden>No phones yet. Pair one with the PIN above.</div>
  </section>

  <section class="card">
    <div class="row">
      <div>
        <div>Start when I sign in</div>
        <div class="hint" id="autostartHint">Keeps the deck available after a restart.</div>
      </div>
      <label class="switch"><input type="checkbox" id="autostart" aria-label="Start when I sign in"><span></span></label>
    </div>
  </section>

  <section class="card">
    <div class="label">This PC</div>
    <dl class="kv">
      <dt>Address</dt><dd id="addr">&hellip;</dd>
      <dt>Fingerprint</dt><dd id="fp">&hellip;</dd>
      <dt>Settings</dt><dd id="dir">&hellip;</dd>
    </dl>
    <div style="margin-top:14px"><button id="openFolder">Open settings folder</button></div>
  </section>

  <footer id="footer">Reopen this page any time from the tray icon or by starting AndroidDeck Server again.</footer>
</main>
<div class="toast" id="toast" role="status" aria-live="polite"></div>
<script>
"use strict";
const $ = (id) => document.getElementById(id);
const api = (path) => location.pathname.replace(/\/?$/, "/") + "api/" + path;
let last = null, knownPhones = null, offline = false;

function toast(msg) {
  const t = $("toast");
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove("show"), 2600);
}

function ago(ts, now) {
  if (!ts) return "never";
  const s = Math.max(0, now - ts);
  if (s < 90) return "just now";
  if (s < 3600) return Math.round(s / 60) + " min ago";
  if (s < 86400) return Math.round(s / 3600) + " h ago";
  return Math.round(s / 86400) + " d ago";
}

function render(st) {
  last = st;
  document.title = st.name + " · AndroidDeck";
  $("name").textContent = st.name;
  $("status").textContent = "AndroidDeck server " + st.version + " is running";
  $("status").classList.remove("offline");
  const pin = $("pin");
  pin.replaceChildren();
  for (const part of [st.pin.slice(0, 3), st.pin.slice(3)]) {
    const s = document.createElement("span");
    s.textContent = part;
    pin.append(s);
  }
  $("fp").textContent = st.fingerprint;
  $("fpInline").textContent = st.fingerprint;
  $("addr").textContent = st.addresses.length ? st.addresses.map((a) => a + ":" + st.port).join("\n") : "port " + st.port;
  $("addr").style.whiteSpace = "pre-line";
  $("dir").textContent = st.configDir;

  const list = $("phones");
  list.replaceChildren();
  for (const d of st.devices) {
    const li = document.createElement("li");
    const dot = document.createElement("span");
    dot.className = "dot" + (d.online ? " on" : "");
    dot.title = d.online ? "Connected" : "Not connected";
    const grow = document.createElement("div");
    grow.className = "grow";
    const nm = document.createElement("div");
    nm.textContent = d.name;
    const meta = document.createElement("div");
    meta.className = "meta";
    meta.textContent = d.online ? "Connected now" : "Last seen " + ago(d.lastSeen, st.now);
    grow.append(nm, meta);
    const btn = document.createElement("button");
    btn.className = "danger";
    btn.textContent = "Unpair";
    btn.onclick = () => unpair(d.id, d.name);
    li.append(dot, grow, btn);
    list.append(li);
  }
  $("phonesLabel").textContent = "Paired phones" + (st.devices.length ? " · " + st.devices.length : "");
  $("noPhones").hidden = st.devices.length > 0;
  $("unpairAll").hidden = st.devices.length < 2;

  if (knownPhones !== null) {
    const fresh = st.devices.filter((d) => !knownPhones.has(d.id));
    if (fresh.length) toast("Paired with " + fresh.map((d) => d.name).join(", "));
  }
  knownPhones = new Set(st.devices.map((d) => d.id));

  const sw = $("autostart");
  sw.disabled = st.startAtLogin === null;
  sw.checked = !!st.startAtLogin;
  $("autostartHint").textContent = st.startAtLogin === null
    ? "Not available on this system."
    : "Keeps the deck available after a restart.";
}

async function call(path, body) {
  const r = await fetch(api(path), {
    method: body === undefined ? "GET" : "POST",
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || "Request failed (" + r.status + ")");
  return data;
}

async function refresh() {
  try {
    render(await call("state"));
    offline = false;
  } catch (e) {
    if (!offline) {
      $("status").textContent = "The server is not running. Start AndroidDeck Server again to reopen this page.";
      $("status").classList.add("offline");
    }
    offline = true;
  }
}

async function unpair(id, name) {
  const all = id === "*";
  if (!confirm(all ? "Unpair all phones? They will need the PIN to connect again." : "Unpair " + name + "?")) return;
  try {
    render(await call("unpair", { id }));
    toast(all ? "All phones were unpaired" : name + " was unpaired");
  } catch (e) { toast(e.message); }
}

$("newPin").onclick = async () => {
  try { render(await call("new-pin", {})); toast("New PIN created"); } catch (e) { toast(e.message); }
};
$("unpairAll").onclick = () => unpair("*", "");
$("autostart").onchange = async (ev) => {
  const on = ev.target.checked;
  try {
    render(await call("autostart", { on }));
    toast(on ? "AndroidDeck will start when you sign in" : "Start at sign-in turned off");
  } catch (e) { ev.target.checked = !on; toast(e.message); }
};
$("openFolder").onclick = async () => {
  try { await call("open-folder", {}); } catch (e) { toast(e.message); }
};

refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>
"""
