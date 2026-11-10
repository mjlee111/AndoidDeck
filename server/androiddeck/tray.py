"""System tray front-end. The server runs on a background thread; the tray owns the main thread
(required by macOS)."""

from __future__ import annotations

import logging
import sys
import threading
from typing import TYPE_CHECKING

from . import autostart
from .actions import open_uri
from .branding import icon_image
from .config import Config, config_dir
from .server import DeckServer, serve_in_thread

if TYPE_CHECKING:
    from .panel import ControlPanel

log = logging.getLogger(__name__)


def _on_main_thread(fn) -> None:
    """Runs fn on the tray's thread where the toolkit requires it (AppKit on macOS)."""
    if sys.platform == "darwin":
        try:
            from PyObjCTools import AppHelper

            AppHelper.callAfter(fn)
            return
        except Exception:  # noqa: BLE001
            pass
    fn()


def run_tray(server: DeckServer, cfg: Config, panel: ControlPanel, host: str, port: int, address: str,
             login: bool) -> int:
    try:
        import pystray
    except Exception as e:  # noqa: BLE001
        log.error("Tray mode needs pystray (%s). Run without --tray to use the console.", e)
        return 2

    _thread, stop = serve_in_thread(server, host, port)
    log.info("Server running on %s (tray mode)", address)

    try:
        autostart.ensure_default(cfg)
    except Exception as e:  # noqa: BLE001
        log.warning("Could not register start at login: %s", e)

    holder: dict[str, pystray.Icon] = {}

    def title() -> str:
        return f"AndroidDeck · {cfg.name} · PIN {cfg.pin[:3]} {cfg.pin[3:]}"

    def refresh() -> None:
        icon = holder.get("icon")
        if icon is not None:
            icon.title = title()
            icon.update_menu()

    def notify(message: str, title_: str = "AndroidDeck") -> None:
        icon = holder.get("icon")
        if icon is not None and getattr(icon, "HAS_NOTIFICATION", False):
            try:
                icon.notify(message, title_)
            except Exception:  # noqa: BLE001
                pass

    def on_paired(device: str) -> None:
        _on_main_thread(refresh)
        notify(f"Paired with {device}")

    server.on_paired = on_paired  # called on the server thread
    panel.on_change = lambda: _on_main_thread(refresh)

    def open_panel(_icon=None, _item=None) -> None:
        try:
            panel.open()
        except Exception as e:  # noqa: BLE001
            log.warning("Could not open the control panel: %s", e)
            notify(f"PIN {cfg.pin[:3]} {cfg.pin[3:]} · open {panel.url} in a browser")

    def new_pin(_icon, _item) -> None:
        cfg.regenerate_pin()
        refresh()
        notify(f"New PIN: {cfg.pin[:3]} {cfg.pin[3:]}")

    def toggle_autostart(_icon, _item) -> None:
        try:
            autostart.set_enabled(not autostart.is_enabled())
        except Exception as e:  # noqa: BLE001
            notify(f"Could not change start at login: {e}")
        refresh()

    def quit_app(icon, _item) -> None:
        stop()
        icon.stop()

    menu = pystray.Menu(
        # Default item: left click on Windows and X11 trays opens the panel.
        pystray.MenuItem("Pair a phone / show PIN…", open_panel, default=True),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem(lambda _i: f"PIN  {cfg.pin[:3]} {cfg.pin[3:]}", None, enabled=False),
        pystray.MenuItem(f"Address  {address}", None, enabled=False),
        pystray.MenuItem(lambda _i: f"Paired phones  {len(cfg.devices)}", None, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("New PIN", new_pin),
        pystray.MenuItem("Start at login", toggle_autostart, checked=lambda _i: autostart.is_enabled()),
        pystray.MenuItem("Open settings folder", lambda _i, _j: open_uri(str(config_dir()))),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Quit", quit_app),
    )
    icon = pystray.Icon("androiddeck", icon_image(64), title(), menu)
    holder["icon"] = icon

    def setup(i) -> None:
        i.visible = True
        if not login:
            # Started by hand: show the PIN right away instead of hiding in the tray.
            threading.Timer(0.5, open_panel).start()
        elif not cfg.devices:
            threading.Timer(1.0, lambda: notify(
                f"Running. Pair a phone with PIN {cfg.pin[:3]} {cfg.pin[3:]}")).start()

    icon.run(setup=setup)
    stop()
    return 0
