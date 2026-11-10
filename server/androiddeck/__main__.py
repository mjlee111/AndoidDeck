"""Entry point: python -m androiddeck"""

from __future__ import annotations

import argparse
import logging
import logging.handlers
import os
import socket
import sys

from aiohttp import web

from . import HTTP_PORT, __version__
from .config import Config, cache_dir, config_dir


def lan_ips() -> list[str]:
    ips: list[str] = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no packet is sent; selects the default route
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        import psutil

        for nic, addrs in psutil.net_if_addrs().items():
            if nic.startswith(("lo", "docker", "veth", "br-", "virbr", "vmnet", "vboxnet")):
                continue
            for a in addrs:
                if a.family == socket.AF_INET and not a.address.startswith(("127.", "169.254.")):
                    if a.address not in ips:
                        ips.append(a.address)
    except Exception:  # noqa: BLE001
        pass
    return ips


def banner(cfg: Config, port: int, fingerprint: str) -> str:
    ips = lan_ips() or ["<this PC's IP>"]
    lines = [
        "",
        f"  AndroidDeck server {__version__}",
        "  " + "─" * 48,
        f"  Name         {cfg.name}",
        f"  Address      {ips[0]}:{port}",
    ]
    for ip in ips[1:4]:
        lines.append(f"               {ip}:{port}")
    lines += [
        f"  PIN          {cfg.pin[:3]} {cfg.pin[3:]}",
        f"  Fingerprint  {fingerprint}",
        f"  Paired       {len(cfg.devices)} device(s)",
        "  " + "─" * 48,
        "  Open AndroidDeck on your phone (same network) and pair with the PIN.",
        "  The phone shows the same fingerprint; they must match.",
        "  Press Ctrl+C to stop.",
        "",
    ]
    return "\n".join(lines)


def _setup_logging(verbose: bool, to_file: bool) -> None:
    handlers: list[logging.Handler] = []
    if sys.stderr is not None:
        handlers.append(logging.StreamHandler())
    if to_file:
        config_dir().mkdir(parents=True, exist_ok=True)
        handlers.append(logging.handlers.RotatingFileHandler(
            config_dir() / "server.log", maxBytes=1_000_000, backupCount=2, encoding="utf-8"))
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname).1s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
        handlers=handlers,
    )
    for noisy in ("aiohttp.access", "PIL"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def main(argv: list[str] | None = None) -> int:
    # Windowed builds (PyInstaller --windowed, pythonw) have no console streams.
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w")  # noqa: SIM115
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w")  # noqa: SIM115
    try:
        sys.stdout.reconfigure(errors="replace")  # type: ignore[attr-defined]
    except (AttributeError, ValueError):
        pass

    frozen = getattr(sys, "frozen", False)
    p = argparse.ArgumentParser(prog="androiddeck", description="AndroidDeck PC server")
    p.add_argument("--port", type=int, help=f"HTTPS/WebSocket port (default {HTTP_PORT})")
    p.add_argument("--host", default="0.0.0.0", help="bind address (default 0.0.0.0)")
    p.add_argument("--name", help="name shown on the phone (saved)")
    p.add_argument("--new-pin", action="store_true", help="generate a new pairing PIN")
    p.add_argument("--revoke-all", action="store_true", help="forget all paired phones")
    p.add_argument("--no-discovery", action="store_true", help="disable UDP LAN discovery")
    p.add_argument("--tray", action="store_true", help="run with a system tray icon instead of a console")
    p.add_argument("--console", action="store_true", help="force console mode (packaged builds default to tray)")
    p.add_argument("--autostart", choices=["on", "off", "status"], help="start the tray app at login, then exit")
    p.add_argument("--paths", action="store_true", help="print config/cache locations and exit")
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)
    tray = (args.tray or frozen) and not args.console

    _setup_logging(args.verbose, to_file=tray)

    if args.paths:
        print(f"config: {config_dir()}\ncache:  {cache_dir()}")
        return 0
    if args.autostart:
        from . import autostart

        if args.autostart != "status":
            autostart.set_enabled(args.autostart == "on")
        print(f"Start at login: {'on' if autostart.is_enabled() else 'off'}")
        return 0

    cfg = Config()
    if args.name:
        cfg.name = args.name
        cfg.save()
    if args.new_pin:
        cfg.regenerate_pin()
    if args.revoke_all:
        cfg.revoke_all()
        print("All paired devices were revoked.")
    port = args.port or cfg.port or HTTP_PORT

    from .server import DeckServer

    server = DeckServer(cfg, port, discovery=not args.no_discovery)
    print(banner(cfg, port, server.short_fingerprint), flush=True)

    if tray:
        from .tray import run_tray

        ips = lan_ips()
        try:
            return run_tray(server, cfg, args.host, port, f"{ips[0] if ips else args.host}:{port}")
        except OSError as e:
            logging.getLogger(__name__).error("Cannot listen on %s:%d: %s", args.host, port, e)
            return 1

    try:
        web.run_app(server.build_app(), host=args.host, port=port, ssl_context=server.ssl_context(),
                    print=None, access_log=None, handle_signals=True)
    except OSError as e:
        print(f"Cannot listen on {args.host}:{port}: {e}\nTry another port with --port.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
