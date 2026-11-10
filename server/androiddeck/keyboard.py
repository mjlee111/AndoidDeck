"""Key injection backends: pynput (Windows/macOS/X11) and ydotool (Linux Wayland)."""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
import time

log = logging.getLogger(__name__)

MODIFIERS = ("ctrl", "alt", "shift", "meta")

ALIASES = {
    "control": "ctrl", "ctl": "ctrl", "lctrl": "ctrl",
    "option": "alt", "opt": "alt",
    "cmd": "meta", "command": "meta", "win": "meta", "windows": "meta", "super": "meta",
    "return": "enter", "escape": "esc", "del": "delete", "ins": "insert",
    "pgup": "page_up", "pageup": "page_up", "pgdn": "page_down", "pagedown": "page_down",
    "prtsc": "print_screen", "printscreen": "print_screen", "print": "print_screen",
    "arrowup": "up", "arrowdown": "down", "arrowleft": "left", "arrowright": "right",
    "capslock": "caps_lock", "numlock": "num_lock", "scrolllock": "scroll_lock",
    "playpause": "media_play_pause", "play_pause": "media_play_pause",
    "next": "media_next", "previous": "media_previous", "prev": "media_previous",
    "stop": "media_stop", "mute": "volume_mute",
    "plus": "+", "minus": "-",
}

SPECIAL_KEYS = {
    "enter", "esc", "tab", "space", "backspace", "delete", "insert", "home", "end",
    "page_up", "page_down", "up", "down", "left", "right", "caps_lock", "print_screen",
    "pause", "menu", "num_lock", "scroll_lock",
    "media_play_pause", "media_next", "media_previous", "media_stop",
    "volume_up", "volume_down", "volume_mute",
    *[f"f{i}" for i in range(1, 25)],
}


class KeyError_(ValueError):
    pass


def normalize_key(name: str) -> str:
    k = name.strip()
    if len(k) == 1:
        return k.lower()
    k = k.lower().replace(" ", "_").replace("-", "_")
    k = ALIASES.get(k.replace("_", ""), ALIASES.get(k, k))
    if k in MODIFIERS or k in SPECIAL_KEYS:
        return k
    raise KeyError_(f"unknown key: {name}")


def normalize_combo(keys: list[str]) -> tuple[list[str], list[str]]:
    """Split a combo into (modifiers, keys) with modifiers in canonical order."""
    if not keys:
        raise KeyError_("empty key combination")
    norm = [normalize_key(k) for k in keys]
    mods = [m for m in MODIFIERS if m in norm]
    rest = [k for k in norm if k not in MODIFIERS]
    return mods, rest


class KeyBackend:
    name = "none"

    def combo(self, keys: list[str]) -> None:
        raise NotImplementedError

    def type_text(self, text: str) -> None:
        raise NotImplementedError


class PynputBackend(KeyBackend):
    name = "pynput"

    def __init__(self) -> None:
        from pynput import keyboard  # imported lazily: fails without a display on Linux

        self._kb = keyboard
        self._ctl = keyboard.Controller()

    def _resolve(self, k: str):
        Key = self._kb.Key
        KeyCode = self._kb.KeyCode
        if len(k) == 1:
            return KeyCode.from_char(k)
        mapping = {
            "ctrl": "ctrl", "alt": "alt", "shift": "shift", "meta": "cmd",
            "volume_up": "media_volume_up", "volume_down": "media_volume_down",
            "volume_mute": "media_volume_mute",
        }
        attr = mapping.get(k, k)
        key = getattr(Key, attr, None)
        if key is not None:
            return key
        if k == "media_stop" and sys.platform == "win32":
            return KeyCode.from_vk(0xB2)  # VK_MEDIA_STOP
        raise KeyError_(f"key not supported on this platform: {k}")

    def combo(self, keys: list[str]) -> None:
        mods, rest = normalize_combo(keys)
        mod_keys = [self._resolve(m) for m in mods]
        main_keys = [self._resolve(k) for k in rest]
        pressed = []
        try:
            for mk in mod_keys:
                self._ctl.press(mk)
                pressed.append(mk)
                time.sleep(0.012)
            for k in main_keys:
                self._ctl.press(k)
                time.sleep(0.012)
                self._ctl.release(k)
                time.sleep(0.012)
        finally:
            for mk in reversed(pressed):
                self._ctl.release(mk)
                time.sleep(0.005)

    def type_text(self, text: str) -> None:
        self._ctl.type(text)


# Linux input-event-codes.h key codes used by ydotool.
_EVDEV = {
    "esc": 1, "1": 2, "2": 3, "3": 4, "4": 5, "5": 6, "6": 7, "7": 8, "8": 9, "9": 10, "0": 11,
    "-": 12, "=": 13, "backspace": 14, "tab": 15,
    "q": 16, "w": 17, "e": 18, "r": 19, "t": 20, "y": 21, "u": 22, "i": 23, "o": 24, "p": 25,
    "[": 26, "]": 27, "enter": 28, "ctrl": 29,
    "a": 30, "s": 31, "d": 32, "f": 33, "g": 34, "h": 35, "j": 36, "k": 37, "l": 38,
    ";": 39, "'": 40, "`": 41, "shift": 42, "\\": 43,
    "z": 44, "x": 45, "c": 46, "v": 47, "b": 48, "n": 49, "m": 50, ",": 51, ".": 52, "/": 53,
    "alt": 56, "space": 57, "caps_lock": 58,
    "f1": 59, "f2": 60, "f3": 61, "f4": 62, "f5": 63, "f6": 64, "f7": 65, "f8": 66, "f9": 67, "f10": 68,
    "num_lock": 69, "scroll_lock": 70, "f11": 87, "f12": 88,
    "print_screen": 99, "home": 102, "up": 103, "page_up": 104, "left": 105, "right": 106,
    "end": 107, "down": 108, "page_down": 109, "insert": 110, "delete": 111,
    "volume_mute": 113, "volume_down": 114, "volume_up": 115, "pause": 119,
    "meta": 125, "menu": 127,
    "media_next": 163, "media_play_pause": 164, "media_previous": 165, "media_stop": 166,
    **{f"f{i}": 183 + (i - 13) for i in range(13, 25)},
}
# Characters that need Shift on a US layout, mapped to their base key.
_SHIFTED = {
    "!": "1", "@": "2", "#": "3", "$": "4", "%": "5", "^": "6", "&": "7", "*": "8", "(": "9", ")": "0",
    "_": "-", "+": "=", "{": "[", "}": "]", ":": ";", '"': "'", "~": "`", "|": "\\",
    "<": ",", ">": ".", "?": "/",
}


class YdotoolBackend(KeyBackend):
    name = "ydotool"

    def __init__(self) -> None:
        exe = shutil.which("ydotool")
        if not exe:
            raise RuntimeError("ydotool not found")
        self._exe = exe

    def _run(self, args: list[str]) -> None:
        r = subprocess.run([self._exe, *args], capture_output=True, text=True, timeout=5)
        if r.returncode != 0:
            raise RuntimeError(f"ydotool failed: {r.stderr.strip() or r.returncode}")

    def combo(self, keys: list[str]) -> None:
        mods, rest = normalize_combo(keys)
        codes_down: list[int] = [_EVDEV[m] for m in mods]
        seq: list[str] = [f"{c}:1" for c in codes_down]
        for k in rest:
            base = _SHIFTED.get(k)
            if base is not None and "shift" not in mods:
                seq += [f"{_EVDEV['shift']}:1", f"{_EVDEV[base]}:1", f"{_EVDEV[base]}:0", f"{_EVDEV['shift']}:0"]
                continue
            code = _EVDEV.get(k)
            if code is None:
                raise KeyError_(f"key not supported by ydotool: {k}")
            seq += [f"{code}:1", f"{code}:0"]
        seq += [f"{c}:0" for c in reversed(codes_down)]
        self._run(["key", *seq])

    def type_text(self, text: str) -> None:
        self._run(["type", "--", text])


def make_backend(preference: str = "auto") -> KeyBackend | None:
    wayland = sys.platform.startswith("linux") and (
        os.environ.get("XDG_SESSION_TYPE") == "wayland" or bool(os.environ.get("WAYLAND_DISPLAY"))
    )
    order: list[type[KeyBackend]]
    if preference == "ydotool":
        order = [YdotoolBackend, PynputBackend]
    elif preference == "pynput":
        order = [PynputBackend]
    elif wayland:
        order = [YdotoolBackend, PynputBackend]
    else:
        order = [PynputBackend]
    for cls in order:
        try:
            backend = cls()
            if wayland and cls is PynputBackend:
                log.warning(
                    "Wayland session: pynput can only send keys to XWayland apps. "
                    "Install ydotool (and run ydotoold) for full hotkey support."
                )
            log.info("Key backend: %s", backend.name)
            return backend
        except Exception as e:  # noqa: BLE001 - backends fail in many platform-specific ways
            log.debug("Key backend %s unavailable: %s", cls.name, e)
    log.warning("No key injection backend available; hotkey/text actions disabled.")
    return None
