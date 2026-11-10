"""System tray front-end. The server runs on a background thread; the tray owns the main thread
(required by macOS)."""

from __future__ import annotations

import logging
import os
import subprocess
import sys
import threading

from . import autostart
from .branding import icon_image
from .config import Config, config_dir
from .server import DeckServer, serve_in_thread

log = logging.getLogger(__name__)


def _open_folder(path: str) -> None:
    if sys.platform == "win32":
        os.startfile(path)  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


def run_tray(server: DeckServer, cfg: Config, host: str, port: int, address: str) -> int:
    try:
        import pystray
    except Exception as e:  # noqa: BLE001
        log.error("Tray mode needs pystray (%s). Run without --tray to use the console.", e)
        return 2

    _thread, stop = serve_in_thread(server, host, port)
    log.info("Server running on %s (tray mode)", address)

    holder: dict[str, pystray.Icon] = {}

    def notify(message: str, title: str = "AndroidDeck") -> None:
        icon = holder.get("icon")
        if icon is not None and getattr(icon, "HAS_NOTIFICATION", False):
            try:
                icon.notify(message, title)
            except Exception:  # noqa: BLE001
                pass

    server.on_paired = lambda device: notify(f"Paired with {device}")

    def new_pin(icon, _item) -> None:
        cfg.regenerate_pin()
        icon.update_menu()
        notify(f"New PIN: {cfg.pin[:3]} {cfg.pin[3:]}")

    def revoke(icon, _item) -> None:
        cfg.revoke_all()
        icon.update_menu()
        notify("All phones were unpaired.")

    def toggle_autostart(icon, _item) -> None:
        try:
            autostart.set_enabled(not autostart.is_enabled())
        except Exception as e:  # noqa: BLE001
            notify(f"Could not change start-at-login: {e}")
        icon.update_menu()

    def quit_app(icon, _item) -> None:
        stop()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem(f"AndroidDeck · {cfg.name}", None, enabled=False),
        pystray.MenuItem(lambda _i: f"PIN  {cfg.pin[:3]} {cfg.pin[3:]}", None, enabled=False),
        pystray.MenuItem(f"Address  {address}", None, enabled=False),
        pystray.MenuItem(f"Fingerprint  {server.short_fingerprint}", None, enabled=False),
        pystray.MenuItem(lambda _i: f"Paired phones  {len(cfg.devices)}", None, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("New PIN", new_pin),
        pystray.MenuItem("Unpair all phones", revoke),
        pystray.MenuItem("Start at login", toggle_autostart, checked=lambda _i: autostart.is_enabled()),
        pystray.MenuItem("Open settings folder", lambda _i, _j: _open_folder(str(config_dir()))),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Quit", quit_app),
    )
    icon = pystray.Icon("androiddeck", icon_image(64), f"AndroidDeck · PIN {cfg.pin}", menu)
    holder["icon"] = icon

    def setup(i) -> None:
        i.visible = True
        threading.Timer(1.0, lambda: notify(f"Running. PIN {cfg.pin[:3]} {cfg.pin[3:]}")).start()

    icon.run(setup=setup)
    stop()
    return 0
