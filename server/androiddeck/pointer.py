"""Remote trackpad: mouse injection with move coalescing on a dedicated thread."""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
import threading
from collections import deque
from typing import Any

from .keyboard import KeyBackend

log = logging.getLogger(__name__)

BUTTONS = ("left", "right", "middle")


class MouseBackend:
    name = "none"

    def move(self, dx: int, dy: int) -> None:
        raise NotImplementedError

    def button(self, button: str, down: bool) -> None:
        raise NotImplementedError

    def click(self, button: str, count: int) -> None:
        for _ in range(count):
            self.button(button, True)
            self.button(button, False)

    def scroll(self, dx: int, dy: int) -> None:
        raise NotImplementedError


class PynputMouse(MouseBackend):
    name = "pynput"

    def __init__(self) -> None:
        from pynput import mouse  # lazy: needs a display on Linux

        self._m = mouse
        self._ctl = mouse.Controller()

    def _btn(self, b: str):
        return getattr(self._m.Button, b)

    def move(self, dx: int, dy: int) -> None:
        self._ctl.move(dx, dy)

    def button(self, button: str, down: bool) -> None:
        (self._ctl.press if down else self._ctl.release)(self._btn(button))

    def click(self, button: str, count: int) -> None:
        self._ctl.click(self._btn(button), count)

    def scroll(self, dx: int, dy: int) -> None:
        self._ctl.scroll(dx, dy)


class YdotoolMouse(MouseBackend):
    """ydotool v1 syntax. Button codes: 0 left, 1 right, 2 middle; 0x40 = down, 0x80 = up."""

    name = "ydotool"
    CODES = {"left": 0, "right": 1, "middle": 2}

    def __init__(self) -> None:
        exe = shutil.which("ydotool")
        if not exe:
            raise RuntimeError("ydotool not found")
        self._exe = exe

    def _run(self, *args: str) -> None:
        subprocess.run([self._exe, *args], capture_output=True, timeout=3)

    def move(self, dx: int, dy: int) -> None:
        self._run("mousemove", "-x", str(dx), "-y", str(dy))

    def button(self, button: str, down: bool) -> None:
        self._run("click", hex(self.CODES[button] | (0x40 if down else 0x80)))

    def click(self, button: str, count: int) -> None:
        self._run("click", "-r", str(count), hex(self.CODES[button] | 0xC0))

    def scroll(self, dx: int, dy: int) -> None:
        self._run("mousemove", "-w", "-x", str(dx), "-y", str(-dy))


def make_mouse_backend(preference: str = "auto") -> MouseBackend | None:
    wayland = sys.platform.startswith("linux") and (
        os.environ.get("XDG_SESSION_TYPE") == "wayland" or bool(os.environ.get("WAYLAND_DISPLAY"))
    )
    order: list[type[MouseBackend]] = [YdotoolMouse, PynputMouse] if (wayland or preference == "ydotool") else [PynputMouse]
    for cls in order:
        try:
            backend = cls()
            log.info("Mouse backend: %s", backend.name)
            return backend
        except Exception as e:  # noqa: BLE001
            log.debug("Mouse backend %s unavailable: %s", cls.name, e)
    log.warning("No mouse injection backend available; trackpad disabled.")
    return None


class PointerService:
    """Queue of input events. Consecutive moves (and scrolls) merge, so a slow injector never lags
    behind the finger; discrete events (clicks, keys) keep their order relative to movement."""

    def __init__(self, mouse: MouseBackend | None, keys: KeyBackend | None) -> None:
        self.mouse = mouse
        self.keys = keys
        self._q: deque[list[Any]] = deque()
        self._cv = threading.Condition()
        self._frac = [0.0, 0.0, 0.0, 0.0]  # sub-pixel remainders: move x/y, scroll x/y
        threading.Thread(target=self._loop, name="pointer", daemon=True).start()

    def _push(self, kind: str, *args: Any) -> None:
        with self._cv:
            last = self._q[-1] if self._q else None
            if kind in ("move", "scroll") and last is not None and last[0] == kind:
                last[1] += args[0]
                last[2] += args[1]
            else:
                if len(self._q) > 256:  # drop stale input rather than replaying it late
                    self._q.clear()
                self._q.append([kind, *args])
            self._cv.notify()

    def move(self, dx: float, dy: float) -> None:
        self._push("move", float(dx), float(dy))

    def scroll(self, dx: float, dy: float) -> None:
        self._push("scroll", float(dx), float(dy))

    def click(self, button: str, count: int = 1) -> None:
        if button in BUTTONS:
            self._push("click", button, max(1, min(int(count), 3)))

    def button(self, button: str, down: bool) -> None:
        if button in BUTTONS:
            self._push("button", button, bool(down))

    def type_text(self, text: str) -> None:
        if text:
            self._push("type", text[:2000])

    def combo(self, keys: list[str]) -> None:
        if keys:
            self._push("combo", list(keys)[:8])

    def hold(self, keys: list[str], down: bool) -> None:
        if keys:
            self._push("hold", list(keys)[:8], bool(down))

    def _take(self, idx_x: int, value_x: float, value_y: float) -> tuple[int, int]:
        x = value_x + self._frac[idx_x]
        y = value_y + self._frac[idx_x + 1]
        ix, iy = int(x), int(y)
        self._frac[idx_x], self._frac[idx_x + 1] = x - ix, y - iy
        return ix, iy

    def _loop(self) -> None:
        while True:
            with self._cv:
                while not self._q:
                    self._cv.wait()
                ev = self._q.popleft()
            try:
                self._apply(ev)
            except Exception as e:  # noqa: BLE001
                log.debug("input event %s failed: %s", ev[0], e)

    def _apply(self, ev: list[Any]) -> None:
        kind = ev[0]
        if kind in ("type", "combo", "hold"):
            if self.keys is None:
                return
            if kind == "type":
                self.keys.type_text(ev[1])
            elif kind == "hold":
                self.keys.hold(ev[1], ev[2])
            else:
                self.keys.combo(ev[1])
            return
        if self.mouse is None:
            return
        if kind == "move":
            dx, dy = self._take(0, ev[1], ev[2])
            if dx or dy:
                self.mouse.move(dx, dy)
        elif kind == "scroll":
            sx, sy = self._take(2, ev[1], ev[2])
            if sx or sy:
                self.mouse.scroll(sx, sy)
        elif kind == "click":
            self.mouse.click(ev[1], ev[2])
        elif kind == "button":
            self.mouse.button(ev[1], ev[2])
