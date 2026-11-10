"""UDP discovery responder so phones can find the server on the LAN."""

from __future__ import annotations

import asyncio
import json
import logging
import socket
from typing import Callable

log = logging.getLogger(__name__)

DISCOVER = "androiddeck.discover"
ANNOUNCE = "androiddeck.here"


class _Protocol(asyncio.DatagramProtocol):
    def __init__(self, info: Callable[[], dict]):
        self.info = info
        self.transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport) -> None:  # type: ignore[override]
        self.transport = transport

    def datagram_received(self, data: bytes, addr) -> None:  # type: ignore[override]
        if len(data) > 512:
            return
        try:
            msg = json.loads(data.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return
        if not isinstance(msg, dict) or msg.get("t") != DISCOVER:
            return
        reply = {"t": ANNOUNCE, **self.info()}
        if self.transport is not None:
            self.transport.sendto(json.dumps(reply).encode("utf-8"), addr)


async def start_discovery(port: int, info: Callable[[], dict]) -> asyncio.DatagramTransport | None:
    loop = asyncio.get_running_loop()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    try:
        sock.bind(("0.0.0.0", port))
    except OSError as e:
        log.warning("Discovery disabled (UDP %d unavailable: %s). Add the PC manually by IP.", port, e)
        sock.close()
        return None
    transport, _ = await loop.create_datagram_endpoint(lambda: _Protocol(info), sock=sock)
    log.info("Discovery listening on UDP %d", port)
    return transport
