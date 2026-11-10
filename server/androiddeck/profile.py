"""Deck layout (pages of buttons) stored on the PC so every paired phone shares it."""

from __future__ import annotations

import sys
import threading
import uuid
from pathlib import Path
from typing import Any

from .config import config_dir, read_json, write_json_atomic

MAX_PAGES = 32
MAX_BUTTONS_PER_PAGE = 120
MAX_FOLDER_BUTTONS = 60
MAX_STEPS = 16
MAX_STEP_DELAY_MS = 60_000
MAX_STRING = 4096

ACTION_TYPES = {
    "none", "app", "hotkey", "text", "media", "volume", "url", "command", "system",
    "mic", "mixer", "multi",
    # Handled by the phone: open a folder, send Wake-on-LAN.
    "folder", "wake",
}


class ProfileError(ValueError):
    pass


def _id() -> str:
    return uuid.uuid4().hex[:12]


def _btn(label: str, symbol: str, action: dict[str, Any], color: str | None = None) -> dict[str, Any]:
    return {
        "id": _id(),
        "label": label,
        "icon": {"kind": "symbol", "value": symbol},
        "color": color,
        "action": action,
    }


def default_profile() -> dict[str, Any]:
    if sys.platform == "darwin":
        screenshot = ["meta", "shift", "4"]
        task_mgr = ["meta", "alt", "esc"]
        show_desktop = ["f11"]
        copy, paste = ["meta", "c"], ["meta", "v"]
    elif sys.platform == "win32":
        screenshot = ["meta", "shift", "s"]
        task_mgr = ["ctrl", "shift", "esc"]
        show_desktop = ["meta", "d"]
        copy, paste = ["ctrl", "c"], ["ctrl", "v"]
    else:
        screenshot = ["print_screen"]
        task_mgr = ["ctrl", "alt", "delete"]
        show_desktop = ["meta", "d"]
        copy, paste = ["ctrl", "c"], ["ctrl", "v"]

    main = [
        _btn("Prev", "skip_previous", {"type": "media", "key": "previous"}),
        _btn("Play", "play_pause", {"type": "media", "key": "play_pause"}, "#C6F36B"),
        _btn("Next", "skip_next", {"type": "media", "key": "next"}),
        _btn("Mute", "volume_off", {"type": "volume", "op": "mute"}),
        _btn("Mic", "mic", {"type": "mic", "op": "toggle"}),
        _btn("Vol −", "volume_down", {"type": "volume", "op": "down", "value": 0.05}),
        _btn("Vol +", "volume_up", {"type": "volume", "op": "up", "value": 0.05}),
        _btn("Copy", "copy", {"type": "hotkey", "keys": copy}),
        _btn("Paste", "paste", {"type": "hotkey", "keys": paste}),
        _btn("Capture", "screenshot", {"type": "hotkey", "keys": screenshot}),
        _btn("Desktop", "desktop", {"type": "hotkey", "keys": show_desktop}),
        _btn("Tasks", "monitor", {"type": "hotkey", "keys": task_mgr}),
        _btn("Lock", "lock", {"type": "system", "op": "lock"}, "#FF6B6B"),
    ]
    return {
        "version": 1,
        "pages": [
            {"id": _id(), "name": "Main", "buttons": main},
            {"id": _id(), "name": "Apps", "buttons": []},
        ],
    }


def _str(v: Any, field: str, required: bool = False) -> str | None:
    if v is None:
        if required:
            raise ProfileError(f"{field} is required")
        return None
    if not isinstance(v, str):
        raise ProfileError(f"{field} must be a string")
    if len(v) > MAX_STRING:
        raise ProfileError(f"{field} is too long")
    return v


def validate_action(a: Any, nested: bool = False) -> dict[str, Any]:
    if not isinstance(a, dict):
        raise ProfileError("action must be an object")
    t = a.get("type", "none")
    if t not in ACTION_TYPES:
        raise ProfileError(f"unknown action type: {t}")
    if nested and t in ("multi", "folder"):
        raise ProfileError(f"a multi-action step cannot be a {t}")
    out: dict[str, Any] = {"type": t}
    # "device": run on another paired PC (routed by the phone).
    for k in ("appId", "target", "name", "text", "key", "op", "url", "command", "device"):
        s = _str(a.get(k), f"action.{k}")
        if s is not None:
            out[k] = s
    if "keys" in a and a["keys"] is not None:
        keys = a["keys"]
        if not isinstance(keys, list) or len(keys) > 8 or not all(isinstance(k, str) and len(k) <= 32 for k in keys):
            raise ProfileError("action.keys must be a list of up to 8 key names")
        out["keys"] = keys
    if "value" in a and a["value"] is not None:
        if not isinstance(a["value"], (int, float)):
            raise ProfileError("action.value must be a number")
        out["value"] = float(a["value"])
    if "delay" in a and a["delay"] is not None:
        if not isinstance(a["delay"], (int, float)) or not 0 <= a["delay"] <= MAX_STEP_DELAY_MS:
            raise ProfileError(f"action.delay must be 0..{MAX_STEP_DELAY_MS} ms")
        out["delay"] = int(a["delay"])
    if t == "multi":
        steps = a.get("steps") or []
        if not isinstance(steps, list) or len(steps) > MAX_STEPS:
            raise ProfileError(f"action.steps must be a list of up to {MAX_STEPS} actions")
        out["steps"] = [validate_action(step, nested=True) for step in steps]
    return out


def _validate_button(b: Any, in_folder: bool = False) -> dict[str, Any]:
    if not isinstance(b, dict):
        raise ProfileError("button must be an object")
    icon = b.get("icon") or {"kind": "none"}
    if not isinstance(icon, dict):
        raise ProfileError("button.icon must be an object")
    action = validate_action(b.get("action") or {"type": "none"})
    out: dict[str, Any] = {
        "id": _str(b.get("id"), "button.id") or _id(),
        "label": _str(b.get("label"), "button.label") or "",
        "icon": {
            "kind": _str(icon.get("kind"), "icon.kind") or "none",
            "value": _str(icon.get("value"), "icon.value"),
            "device": _str(icon.get("device"), "icon.device"),
        },
        "color": _str(b.get("color"), "button.color"),
        "action": action,
    }
    if action["type"] == "folder":
        if in_folder:
            raise ProfileError("folders cannot contain folders")
        children = b.get("children") or []
        if not isinstance(children, list) or len(children) > MAX_FOLDER_BUTTONS:
            raise ProfileError(f"a folder holds up to {MAX_FOLDER_BUTTONS} buttons")
        out["children"] = [_validate_button(c, in_folder=True) for c in children]
    return out


def validate_profile(p: Any) -> dict[str, Any]:
    if not isinstance(p, dict):
        raise ProfileError("profile must be an object")
    pages = p.get("pages")
    if not isinstance(pages, list) or not pages:
        raise ProfileError("profile.pages must be a non-empty list")
    if len(pages) > MAX_PAGES:
        raise ProfileError("too many pages")
    out_pages = []
    for page in pages:
        if not isinstance(page, dict):
            raise ProfileError("page must be an object")
        buttons = page.get("buttons") or []
        if not isinstance(buttons, list) or len(buttons) > MAX_BUTTONS_PER_PAGE:
            raise ProfileError("page.buttons must be a list (max %d)" % MAX_BUTTONS_PER_PAGE)
        out_pages.append({
            "id": _str(page.get("id"), "page.id") or _id(),
            "name": _str(page.get("name"), "page.name") or "Page",
            "buttons": [_validate_button(b) for b in buttons],
        })
    return {"version": 1, "pages": out_pages}


class ProfileStore:
    def __init__(self, path: Path | None = None):
        self.path = path or (config_dir() / "profile.json")
        self._lock = threading.Lock()
        self.revision = 0
        data = read_json(self.path)
        try:
            self.profile = validate_profile(data) if data is not None else default_profile()
        except ProfileError:
            backup = self.path.with_suffix(".invalid.json")
            write_json_atomic(backup, data)
            self.profile = default_profile()
        self._save()

    def _save(self) -> None:
        write_json_atomic(self.path, self.profile)

    def get(self) -> dict[str, Any]:
        return self.profile

    def replace(self, new_profile: Any) -> dict[str, Any]:
        valid = validate_profile(new_profile)
        with self._lock:
            self.profile = valid
            self.revision += 1
            self._save()
        return valid
