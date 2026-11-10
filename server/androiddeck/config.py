"""Persistent server configuration: identity, pairing PIN and paired device tokens."""

from __future__ import annotations

import hashlib
import json
import logging
import os
import secrets
import socket
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)


def _home_override() -> Path | None:
    """ANDROIDDECK_HOME puts config and cache in one folder (portable installs, tests, CI)."""
    home = os.environ.get("ANDROIDDECK_HOME")
    return Path(home) if home else None


def config_dir() -> Path:
    override = _home_override()
    if override is not None:
        return override
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming")
        return base / "AndroidDeck"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "AndroidDeck"
    base = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    return base / "androiddeck"


def cache_dir() -> Path:
    override = _home_override()
    if override is not None:
        return override / "cache"
    if sys.platform == "win32":
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
        return base / "AndroidDeck" / "cache"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Caches" / "AndroidDeck"
    base = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache")
    return base / "androiddeck"


def write_json_atomic(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


def read_json(path: Path) -> Any | None:
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as e:
        log.warning("Failed to read %s: %s", path, e)
        return None


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _new_pin() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


class Config:
    """Server settings stored in config.json. Only token hashes are persisted."""

    def __init__(self, path: Path | None = None):
        self.path = path or (config_dir() / "config.json")
        self._lock = threading.Lock()
        data = read_json(self.path) or {}
        self.server_id: str = data.get("server_id") or uuid.uuid4().hex
        self.name: str = data.get("name") or socket.gethostname()
        self.pin: str = data.get("pin") or _new_pin()
        self.port: int | None = data.get("port")
        self.key_backend: str = data.get("key_backend") or "auto"
        self.allow_commands: bool = bool(data.get("allow_commands", True))
        self.devices: dict[str, dict[str, Any]] = data.get("devices") or {}
        # Set once start-at-login has been turned on by default, so a later opt-out is respected.
        self.autostart_offered: bool = bool(data.get("autostart_offered", False))
        self.save()

    def to_dict(self) -> dict[str, Any]:
        return {
            "server_id": self.server_id,
            "name": self.name,
            "pin": self.pin,
            "port": self.port,
            "key_backend": self.key_backend,
            "allow_commands": self.allow_commands,
            "devices": self.devices,
            "autostart_offered": self.autostart_offered,
        }

    def save(self) -> None:
        with self._lock:
            write_json_atomic(self.path, self.to_dict())
            if sys.platform != "win32":
                try:
                    os.chmod(self.path, 0o600)
                except OSError:
                    pass

    def regenerate_pin(self) -> str:
        self.pin = _new_pin()
        self.save()
        return self.pin

    def add_device(self, device_name: str) -> str:
        token = secrets.token_urlsafe(32)
        self.devices[_hash_token(token)] = {
            "name": device_name[:64] or "Android",
            "created": int(time.time()),
            "last_seen": int(time.time()),
        }
        self.save()
        return token

    def check_token(self, token: str | None) -> dict[str, Any] | None:
        if not token:
            return None
        h = _hash_token(token)
        dev = self.devices.get(h)
        if dev is None:
            return None
        now = int(time.time())
        # Persist last_seen at most once per minute to limit disk writes.
        if now - int(dev.get("last_seen", 0)) > 60:
            dev["last_seen"] = now
            self.save()
        return dev

    def revoke(self, key: str) -> bool:
        if self.devices.pop(key, None) is None:
            return False
        self.save()
        return True

    def revoke_all(self) -> None:
        self.devices = {}
        self.save()
