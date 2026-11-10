"""End-to-end smoke test for the PC server.

Starts `python -m androiddeck` in an isolated ANDROIDDECK_HOME, then exercises TLS pinning,
discovery, pairing, the app index + icons, the profile API and the WebSocket channel. It never
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

        async with s.ws_connect(base + "/ws", headers=headers) as ws:
            welcome = await asyncio.wait_for(ws.receive_json(), 10)
            check(welcome.get("t") == "welcome" and "mic" in welcome and "wol" in welcome,
                  "WebSocket welcome", welcome.get("features"))
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
