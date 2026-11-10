"""System usage sampling (CPU, memory, GPU, disks, network, processes, battery)."""

from __future__ import annotations

import glob
import logging
import os
import shutil
import subprocess
import sys
import threading
import time
from typing import Any

import psutil

log = logging.getLogger(__name__)

_NET_SKIP_PREFIX = ("lo", "veth", "docker", "br-", "virbr", "vmnet", "vboxnet", "Loopback")
_FS_SKIP = {"squashfs", "tmpfs", "devtmpfs", "overlay", "proc", "sysfs", "autofs", "efivarfs", "ramfs"}


class GpuProbe:
    """Polls GPU usage on a background thread (nvidia-smi or Linux amdgpu sysfs)."""

    def __init__(self, interval: float = 2.0):
        self.interval = interval
        self.data: list[dict[str, Any]] = []
        self._nvsmi = shutil.which("nvidia-smi")
        self._amd = sorted(glob.glob("/sys/class/drm/card*/device/gpu_busy_percent")) if sys.platform.startswith("linux") else []
        self.enabled = bool(self._nvsmi or self._amd)
        self._stop = threading.Event()
        if self.enabled:
            threading.Thread(target=self._loop, name="gpu-probe", daemon=True).start()

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.data = self._sample()
            except Exception as e:  # noqa: BLE001
                log.debug("gpu sample failed: %s", e)
                self.data = []
            self._stop.wait(self.interval)

    def _sample(self) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        if self._nvsmi:
            kwargs: dict[str, Any] = {}
            if sys.platform == "win32":
                kwargs["creationflags"] = 0x08000000  # CREATE_NO_WINDOW
            r = subprocess.run(
                [self._nvsmi, "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
                 "--format=csv,noheader,nounits"],
                capture_output=True, text=True, timeout=5, **kwargs,
            )
            if r.returncode == 0:
                for line in r.stdout.strip().splitlines():
                    parts = [p.strip() for p in line.split(",")]
                    if len(parts) < 5:
                        continue
                    out.append({
                        "name": parts[0],
                        "util": _num(parts[1]),
                        "memUsed": _mib(parts[2]),
                        "memTotal": _mib(parts[3]),
                        "temp": _num(parts[4]),
                    })
        for busy in self._amd:
            dev = os.path.dirname(busy)
            util = _num(_read(busy))
            used = _num(_read(os.path.join(dev, "mem_info_vram_used")))
            total = _num(_read(os.path.join(dev, "mem_info_vram_total")))
            temp = None
            for t in glob.glob(os.path.join(dev, "hwmon", "hwmon*", "temp1_input")):
                v = _num(_read(t))
                temp = v / 1000 if v is not None else None
                break
            out.append({"name": "AMD GPU", "util": util, "memUsed": used, "memTotal": total, "temp": temp})
        return out


def _read(path: str) -> str:
    try:
        with open(path, "r") as f:
            return f.read().strip()
    except OSError:
        return ""


def _num(s: str) -> float | None:
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def _mib(s: str) -> float | None:
    v = _num(s)
    return v * 1024 * 1024 if v is not None else None


class Monitor:
    def __init__(self) -> None:
        psutil.cpu_percent(None)
        psutil.cpu_percent(None, percpu=True)
        self._t = time.monotonic()
        self._net = self._net_totals()
        self._disk_io = self._disk_io_totals()
        self._gpu = GpuProbe()
        self._procs: dict[int, psutil.Process] = {}
        self._top: list[dict[str, Any]] = []
        self._top_t = 0.0
        self._disks: list[dict[str, Any]] = []
        self._disks_t = 0.0
        self._ncpu = psutil.cpu_count() or 1
        self._cpu_name = _cpu_name()

    def close(self) -> None:
        self._gpu.stop()

    @staticmethod
    def _net_totals() -> tuple[int, int]:
        sent = recv = 0
        try:
            for nic, c in psutil.net_io_counters(pernic=True).items():
                if nic.startswith(_NET_SKIP_PREFIX):
                    continue
                sent += c.bytes_sent
                recv += c.bytes_recv
        except Exception:  # noqa: BLE001
            pass
        return sent, recv

    @staticmethod
    def _disk_io_totals() -> tuple[int, int]:
        try:
            c = psutil.disk_io_counters()
            return (c.read_bytes, c.write_bytes) if c else (0, 0)
        except Exception:  # noqa: BLE001
            return 0, 0

    def _cpu_temp(self) -> float | None:
        if not hasattr(psutil, "sensors_temperatures"):
            return None
        try:
            temps = psutil.sensors_temperatures()
        except Exception:  # noqa: BLE001
            return None
        for chip in ("coretemp", "k10temp", "zenpower", "cpu_thermal", "cpu-thermal", "acpitz"):
            entries = temps.get(chip)
            if not entries:
                continue
            for e in entries:
                if e.label in ("Package id 0", "Tctl", "Tdie"):
                    return round(e.current, 1)
            return round(entries[0].current, 1)
        return None

    def _disk_list(self) -> list[dict[str, Any]]:
        now = time.monotonic()
        if now - self._disks_t < 10 and self._disks:
            return self._disks
        disks = []
        seen = set()
        try:
            parts = psutil.disk_partitions(all=False)
        except Exception:  # noqa: BLE001
            parts = []
        for p in parts:
            if p.fstype in _FS_SKIP or p.device in seen or "/snap/" in p.mountpoint:
                continue
            if sys.platform == "win32" and ("cdrom" in p.opts or not p.fstype):
                continue
            if sys.platform == "darwin" and p.mountpoint.startswith("/System/Volumes/") and p.mountpoint != "/System/Volumes/Data":
                continue
            if p.mountpoint.startswith(("/boot", "/var/snap", "/run")):
                continue
            try:
                u = psutil.disk_usage(p.mountpoint)
            except Exception:  # noqa: BLE001
                continue
            if u.total < 1 << 30:
                continue
            seen.add(p.device)
            disks.append({"mount": p.mountpoint, "percent": u.percent, "used": u.used, "total": u.total})
            if len(disks) >= 6:
                break
        self._disks, self._disks_t = disks, now
        return disks

    def _top_processes(self) -> list[dict[str, Any]]:
        now = time.monotonic()
        if now - self._top_t < 2.5 and self._top_t:
            return self._top
        rows = []
        alive = set()
        for p in psutil.process_iter(["name", "memory_info"]):
            pid = p.pid
            if pid == 0:
                continue
            alive.add(pid)
            proc = self._procs.setdefault(pid, p)
            try:
                cpu = proc.cpu_percent(None) / self._ncpu
            except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                continue
            name = p.info.get("name") or "?"
            if name in ("System Idle Process", "Idle"):
                continue
            mem = p.info.get("memory_info")
            rows.append({"name": name, "cpu": round(cpu, 1), "mem": mem.rss if mem else 0})
        for pid in list(self._procs):
            if pid not in alive:
                del self._procs[pid]
        rows.sort(key=lambda r: (r["cpu"], r["mem"]), reverse=True)
        self._top, self._top_t = rows[:6], now
        return self._top

    def sample(self) -> dict[str, Any]:
        now = time.monotonic()
        dt = max(now - self._t, 0.001)
        self._t = now

        sent, recv = self._net_totals()
        up = max(sent - self._net[0], 0) / dt
        down = max(recv - self._net[1], 0) / dt
        self._net = (sent, recv)

        rd, wr = self._disk_io_totals()
        read_rate = max(rd - self._disk_io[0], 0) / dt
        write_rate = max(wr - self._disk_io[1], 0) / dt
        self._disk_io = (rd, wr)

        vm = psutil.virtual_memory()
        try:
            sw = psutil.swap_memory()
            swap = {"percent": sw.percent, "used": sw.used, "total": sw.total}
        except Exception:  # noqa: BLE001
            swap = None
        try:
            freq = psutil.cpu_freq()
            freq_mhz = round(freq.current) if freq and freq.current else None
        except Exception:  # noqa: BLE001
            freq_mhz = None
        try:
            bat = psutil.sensors_battery()
            battery = {"percent": round(bat.percent), "plugged": bool(bat.power_plugged)} if bat else None
        except Exception:  # noqa: BLE001
            battery = None
        try:
            load = list(os.getloadavg()) if hasattr(os, "getloadavg") else None
        except OSError:
            load = None

        return {
            "t": "stats",
            "ts": time.time(),
            "cpu": {
                "name": self._cpu_name,
                "percent": psutil.cpu_percent(None),
                "cores": psutil.cpu_percent(None, percpu=True),
                "freq": freq_mhz,
                "temp": self._cpu_temp(),
                "load": load,
            },
            "mem": {"percent": vm.percent, "used": vm.total - vm.available, "total": vm.total},
            "swap": swap,
            "gpus": self._gpu.data,
            "net": {"up": round(up), "down": round(down)},
            "diskIo": {"read": round(read_rate), "write": round(write_rate)},
            "disks": self._disk_list(),
            "battery": battery,
            "uptime": round(time.time() - psutil.boot_time()),
            "procs": self._top_processes(),
        }


def _cpu_name() -> str | None:
    try:
        if sys.platform.startswith("linux"):
            with open("/proc/cpuinfo") as f:
                for line in f:
                    if line.startswith("model name"):
                        return line.split(":", 1)[1].strip()
        elif sys.platform == "darwin":
            r = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True, timeout=2)
            return r.stdout.strip() or None
        elif sys.platform == "win32":
            import winreg  # type: ignore

            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as k:
                return str(winreg.QueryValueEx(k, "ProcessorNameString")[0]).strip()
    except Exception:  # noqa: BLE001
        return None
    return None
