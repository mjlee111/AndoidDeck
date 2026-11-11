"""End-to-end smoke test for the PC server.

Starts `python -m androiddeck` in an isolated ANDROIDDECK_HOME, then exercises TLS pinning,
discovery, pairing, the app index + icons, the profile API, the WebSocket channel and the local
control panel (PIN, new PIN, unpairing). It never
sends actions with side effects (no app launches, key presses, clicks or volume changes).

Usage: python tests/smoke_test.py [--min-apps N] [--min-icons N] [--exe path/to/AndroidDeckServer]
Exit code 0 = all checks passed.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import aiohttp

SERVER_DIR = Path(__file__).resolve().parent.parent
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


class Check:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def __call__(self, cond: bool, label: str, detail: object = "") -> None:
        status = "PASS" if cond else "FAIL"
        print(f"[{status}] {label}" + (f" — {detail}" if detail != "" else ""), flush=True)
        if not cond:
            self.failures.append(label)


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def discover(timeout: float = 3.0) -> dict | None:
    """Unicast probe to the local discovery responder (broadcast is unreliable on CI)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.settimeout(0.5)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            s.sendto(json.dumps({"t": "androiddeck.discover"}).encode(), ("127.0.0.1", 47778))
            try:
                data, _ = s.recvfrom(4096)
                return json.loads(data)
            except (socket.timeout, ConnectionResetError, OSError):
                continue
    return None


async def wait_ready(base: str, proc: subprocess.Popen, timeout: float = 60) -> bool:
    deadline = time.monotonic() + timeout
    async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=False)) as s:
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                return False
            try:
                async with s.get(base + "/api/info", timeout=aiohttp.ClientTimeout(total=2)) as r:
                    if r.status == 200:
                        return True
            except (aiohttp.ClientError, asyncio.TimeoutError):
                pass
            await asyncio.sleep(0.5)
    return False


async def run_checks(base: str, home: Path, args: argparse.Namespace, check: Check) -> None:
    pin = json.loads((home / "config.json").read_text(encoding="utf-8"))["pin"]
    async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=False)) as s:
        async with s.get(base + "/api/info") as r:
            info = await r.json()
    fp = info.get("fp", "")
    check(info.get("protocol") == 2 and info.get("tls") is True and len(fp) == 64, "GET /api/info over TLS", info)

    # Pinning: a wrong fingerprint must fail, the advertised one must succeed.
    wrong = aiohttp.Fingerprint(bytes(32))
    try:
        async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=wrong)) as bad:
            async with bad.get(base + "/api/info") as r:
                check(False, "wrong certificate fingerprint is rejected", r.status)
    except aiohttp.ServerFingerprintMismatch:
        check(True, "wrong certificate fingerprint is rejected")
    pinned = aiohttp.TCPConnector(ssl=aiohttp.Fingerprint(bytes.fromhex(fp)))
    async with aiohttp.ClientSession(connector=pinned) as s:

        found = await asyncio.get_running_loop().run_in_executor(None, discover)
        check(found is not None and found.get("id") == info.get("id"), "UDP discovery reply", found)

        async with s.get(base + "/api/profile") as r:
            check(r.status == 401, "profile without token is rejected", r.status)

        async with s.post(base + "/api/pair", json={"pin": "000000" if pin != "000000" else "111111", "device": "ci"}) as r:
            check(r.status == 403, "wrong PIN is rejected", r.status)

        async with s.post(base + "/api/pair", json={"pin": pin, "device": "ci-smoke"}) as r:
            body = await r.json()
            check(r.status == 200 and "token" in body, "pairing with correct PIN", r.status)
            print(f"       wol={body.get('wol')}")
        headers = {"Authorization": "Bearer " + body.get("token", "")}

        async with s.post(base + "/api/action", headers=headers, json={"type": "none"}) as r:
            check(r.status == 200, "HTTP action endpoint (widget/tiles)", r.status)
        async with s.post(base + "/api/action", headers=headers, json={"type": "bogus"}) as r:
            check(r.status == 422, "HTTP action endpoint reports errors", r.status)
        async with s.get(base + "/api/media/art", headers=headers) as r:
            check(r.status in (200, 404), "artwork endpoint answers", r.status)
        async with s.get(base + "/api/state", headers=headers) as r:
            st = await r.json() if r.status == 200 else {}
            check(r.status == 200 and "volume" in st and "media" in st and "usage" in st,
                  "live state for home-screen widgets", {k: st.get(k) for k in ("volume", "usage")})
        async with s.get(base + "/api/state") as r:
            check(r.status == 401, "live state needs a token", r.status)

        # App index: the first scan may still be running (Windows extracts icons via PowerShell).
        apps: list = []
        deadline = time.monotonic() + args.scan_timeout
        while True:
            async with s.get(base + "/api/apps", headers=headers, timeout=aiohttp.ClientTimeout(total=200)) as r:
                data = await r.json()
            apps = data.get("apps", [])
            if not data.get("scanning") or time.monotonic() > deadline:
                break
            await asyncio.sleep(3)
        with_icons = [a for a in apps if a.get("hasIcon")]
        print(f"       apps={len(apps)} icons={len(with_icons)} sample={[a['name'] for a in apps[:8]]}")
        check(len(apps) >= args.min_apps, f"app index has >= {args.min_apps} apps", len(apps))
        check(len(with_icons) >= args.min_icons, f"app index has >= {args.min_icons} icons", len(with_icons))
        if with_icons:
            async with s.get(f"{base}/api/icon/{with_icons[0]['id']}", headers=headers) as r:
                blob = await r.read()
                ctype = r.headers.get("Content-Type", "")
                ok = r.status == 200 and (blob.startswith(PNG_MAGIC) or "svg" in ctype)
                check(ok, "icon download is a PNG/SVG", f"{r.status} {ctype} {len(blob)}B")

        async with s.get(base + "/api/profile", headers=headers) as r:
            profile = await r.json()
            check(r.status == 200 and len(profile.get("pages", [])) >= 1, "default profile has pages")
        profile["pages"][0]["name"] = "CI"
        async with s.put(base + "/api/profile", headers=headers, json=profile) as r:
            saved = await r.json()
            check(r.status == 200 and saved["pages"][0]["name"] == "CI", "profile round-trip")
        async with s.put(base + "/api/profile", headers=headers, json={"pages": "nope"}) as r:
            check(r.status == 400, "invalid profile is rejected", r.status)

        # Sized buttons and live widgets survive a round trip; bad sizes are refused.
        sized = json.loads(json.dumps(saved))
        sized["pages"][0]["buttons"].append({"label": "Vol", "cols": 3, "rows": 1, "widget": {"type": "volume"},
                                             "action": {"type": "none"}})
        sized["pages"][0]["buttons"].append({"label": "Stream", "action": {"type": "hotkey", "keys": ["f13"], "hold": True},
                                             "toggle": {"label": "Live", "action": {"type": "hotkey", "keys": ["f14"]}},
                                             "longAction": {"type": "media", "key": "next"}})
        sized["pages"][0]["buttons"].append({"label": "Discord", "cols": 1, "rows": 2,
                                             "widget": {"type": "mixer", "target": "Discord.exe", "name": "Discord"},
                                             "action": {"type": "none"}})
        async with s.put(base + "/api/profile", headers=headers, json=sized) as r:
            body3 = await r.json() if r.status == 200 else {}
            got = body3.get("pages", [{}])[0].get("buttons", [])[-3:]
            extra = got.pop(1) if len(got) == 3 else {}
            check(extra.get("action", {}).get("hold") is True and extra.get("toggle", {}).get("label") == "Live"
                  and extra.get("longAction", {}).get("key") == "next", "toggle, hold and long-press survive a round trip", extra)
            check(r.status == 200 and got[0].get("cols") == 3 and got[0].get("widget") == {"type": "volume"}
                  and got[1].get("rows") == 2 and got[1]["widget"].get("target") == "Discord.exe",
                  "sized buttons and widgets round-trip", got)
        bad = json.loads(json.dumps(saved))
        bad["pages"][0]["buttons"][0]["cols"] = 9
        async with s.put(base + "/api/profile", headers=headers, json=bad) as r:
            check(r.status == 400, "out-of-range button size is rejected", r.status)
        bad["pages"][0]["buttons"][0]["cols"] = 1
        bad["pages"][0]["buttons"][0]["widget"] = {"type": "toaster"}
        async with s.put(base + "/api/profile", headers=headers, json=bad) as r:
            check(r.status == 400, "unknown widget type is rejected", r.status)

        async with s.ws_connect(base + "/ws", headers=headers) as ws:
            first = await asyncio.wait_for(ws.receive_json(), 10)
            check(first.get("t") == "welcome" and "mic" in first and "wol" in first,
                  "WebSocket welcome is the first message", first.get("features") or first.get("t"))
            await ws.send_json({"t": "sub", "stats": True, "mixer": True})
            # Zero-length pointer input exercises the trackpad path without moving anything.
            await ws.send_json({"t": "mouse", "dx": 0, "dy": 0})
            await ws.send_json({"t": "scroll", "dx": 0, "dy": 0})
            await ws.send_json({"t": "mouse", "dx": "garbage"})
            for rid, action in (
                ("none", {"type": "none"}),
                ("badkey", {"type": "hotkey", "keys": ["ctrl", "no_such_key"]}),
                ("badtype", {"type": "bogus"}),
                ("multi", {"type": "multi", "steps": [{"type": "none"}, {"type": "none", "delay": 300}]}),
                ("multibad", {"type": "multi", "steps": [{"type": "none"}, {"type": "bogus"}]}),
                ("folder", {"type": "folder"}),
            ):
                await ws.send_json({"t": "action", "id": rid, "action": action})
            acks: dict[str, dict] = {}
            stats = mixer = None
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and (stats is None or mixer is None or len(acks) < 6):
                try:
                    msg = await asyncio.wait_for(ws.receive_json(), 2)
                except asyncio.TimeoutError:
                    continue
                if msg.get("t") == "ack":
                    acks[msg["id"]] = msg
                elif msg.get("t") == "stats":
                    stats = msg
                elif msg.get("t") == "mixer":
                    mixer = msg
            check(acks.get("none", {}).get("ok") is True, "no-op action is acknowledged")
            check(acks.get("badkey", {}).get("ok") is False, "unknown key is reported", acks.get("badkey", {}).get("error"))
            check(acks.get("badtype", {}).get("ok") is False, "unknown action type is reported")
            check(acks.get("multi", {}).get("ok") is True, "multi-action with delays runs")
            check(acks.get("multibad", {}).get("ok") is False and "step 2" in (acks.get("multibad", {}).get("error") or ""),
                  "multi-action reports the failing step", acks.get("multibad", {}).get("error"))
            check(acks.get("folder", {}).get("ok") is True, "phone-side action types are accepted")
            check(mixer is not None and "sessions" in mixer, "mixer state is pushed on subscribe",
                  None if mixer is None else f"available={mixer.get('available')} sessions={len(mixer.get('sessions', []))}")
            check(stats is not None and 0 <= stats["cpu"]["percent"] <= 100 and stats["mem"]["total"] > 0,
                  "stats are pushed", None if stats is None else
                  f"cpu={stats['cpu']['percent']} mem={stats['mem']['percent']} gpus={len(stats['gpus'])} disks={len(stats['disks'])}")

        await panel_checks(s, base, home, headers, check)


async def panel_checks(s: aiohttp.ClientSession, base: str, home: Path, headers: dict, check: Check) -> None:
    """Local control panel on 127.0.0.1: shows the PIN, makes a new one, unpairs phones."""
    panel_json = home / "panel.json"
    url = json.loads(panel_json.read_text(encoding="utf-8")).get("url", "") if panel_json.exists() else ""
    check(url.startswith("http://127.0.0.1:"), "control panel URL is published", url or None)
    if not url.startswith("http://127.0.0.1:"):
        return
    pin = json.loads((home / "config.json").read_text(encoding="utf-8"))["pin"]
    async with aiohttp.ClientSession() as p:
        async with p.get(url) as r:
            page = await r.text()
            check(r.status == 200 and "Pairing PIN" in page, "control panel page loads", r.status)
        async with p.get(url.rsplit("/", 2)[0] + "/not-the-token/api/state") as r:
            check(r.status == 404, "control panel needs its secret path", r.status)
        async with p.get(url + "api/state", headers={"Host": "attacker.example"}) as r:
            check(r.status == 403, "control panel rejects foreign Host headers", r.status)
        async with p.post(url + "api/new-pin", json={}, headers={"Origin": "http://attacker.example"}) as r:
            check(r.status == 403, "control panel rejects cross-site requests", r.status)
        async with p.post(url + "api/new-pin", json={}) as r:
            st = await r.json()
        saved = json.loads((home / "config.json").read_text(encoding="utf-8"))["pin"]
        check(r.status == 200 and st.get("pin") != pin and st.get("pin") == saved, "New PIN from the control panel")

        async with s.ws_connect(base + "/ws", headers=headers) as ws:
            await asyncio.wait_for(ws.receive_json(), 10)  # welcome
            async with p.get(url + "api/state") as r:
                st = await r.json()
            dev = next((d for d in st.get("devices", []) if d.get("name") == "ci-smoke"), None)
            check(dev is not None and dev.get("online") is True, "control panel lists the connected phone", dev)
            if dev is None:
                return
            async with p.post(url + "api/unpair", json={"id": dev["id"]}) as r:
                st = await r.json()
                check(r.status == 200 and not st.get("devices"), "unpair from the control panel", r.status)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                msg = await ws.receive(timeout=10)
                if msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSING, aiohttp.WSMsgType.CLOSED):
                    break
            check(ws.close_code == 4401, "unpairing closes the phone's session", ws.close_code)
        async with s.get(base + "/api/profile", headers=headers) as r:
            check(r.status == 401, "unpaired phone's token is rejected", r.status)


def mute_semantics_checks(check: Check) -> None:
    """Mic Mute is an explicit mute; volume Mute without a value stays a toggle. Uses a fake audio service."""
    sys.path.insert(0, str(SERVER_DIR))
    from androiddeck.actions import ActionRunner
    from androiddeck.audio import INPUT, OUTPUT

    calls: list[tuple[str, object]] = []

    class FakeAudio:
        async def set_mute(self, which, muted):
            calls.append((which, muted))

    runner = ActionRunner(apps=None, audio=FakeAudio(), media=None, keys=None, allow_commands=False)  # type: ignore[arg-type]
    cases = [
        ({"type": "mic", "op": "mute"}, (INPUT, True)),
        ({"type": "mic", "op": "unmute"}, (INPUT, False)),
        ({"type": "mic", "op": "toggle"}, (INPUT, None)),
        ({"type": "mic", "op": "mute", "value": 1.0}, (INPUT, True)),
        ({"type": "volume", "op": "mute"}, (OUTPUT, None)),
        ({"type": "volume", "op": "mute", "value": 1.0}, (OUTPUT, True)),
    ]
    for action, expected in cases:
        calls.clear()
        asyncio.run(runner.run(action))
        check(calls == [expected], f"{action['type']} {action['op']}{' value' if 'value' in action else ''} → set_mute{expected}", calls)


def hold_sequence_checks(check: Check) -> None:
    """Push-to-talk presses keys in order and releases them in reverse (ydotool, command captured)."""
    sys.path.insert(0, str(SERVER_DIR))
    from androiddeck.keyboard import YdotoolBackend

    sent: list[list[str]] = []
    kb = YdotoolBackend.__new__(YdotoolBackend)
    kb._run = lambda args: sent.append(args)  # type: ignore[method-assign]
    kb.hold(["ctrl", "shift", "m"], True)
    kb.hold(["ctrl", "shift", "m"], False)
    check(sent == [["key", "29:1", "42:1", "50:1"], ["key", "50:0", "42:0", "29:0"]], "hold presses in order, releases in reverse", sent)


def main() -> int:
    # CI consoles (Windows: cp1252) cannot print every character in server logs.
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-apps", type=int, default=0)
    ap.add_argument("--min-icons", type=int, default=0)
    ap.add_argument("--scan-timeout", type=float, default=240)
    ap.add_argument("--exe", help="test this packaged server executable instead of `python -m androiddeck`")
    args = ap.parse_args()

    home = Path(tempfile.mkdtemp(prefix="androiddeck-smoke-"))
    port = free_port()
    base = f"https://127.0.0.1:{port}"
    log_path = home / "server.log"
    env = {**os.environ, "ANDROIDDECK_HOME": str(home), "PYTHONIOENCODING": "utf-8"}
    check = Check()
    if not args.exe:
        mute_semantics_checks(check)
        hold_sequence_checks(check)
    with open(log_path, "w", encoding="utf-8") as log:
        server_cmd = [args.exe, "--console"] if args.exe else [sys.executable, "-m", "androiddeck"]
        proc = subprocess.Popen(
            [*server_cmd, "--host", "127.0.0.1", "--port", str(port), "-v"],
            cwd=SERVER_DIR, env=env, stdout=log, stderr=subprocess.STDOUT,
        )
        try:
            ready = asyncio.run(wait_ready(base, proc))
            check(ready, "server starts and answers HTTP")
            if ready:
                asyncio.run(run_checks(base, home, args, check))
        finally:
            proc.terminate()
            try:
                proc.wait(10)
            except subprocess.TimeoutExpired:
                proc.kill()
    print("\n----- server log -----")
    print(log_path.read_text(encoding="utf-8", errors="replace")[-6000:])
    if check.failures:
        print(f"\n{len(check.failures)} check(s) failed: {check.failures}")
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
