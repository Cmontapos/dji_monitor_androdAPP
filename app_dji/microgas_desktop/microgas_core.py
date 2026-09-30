"""Protocol, bounded history and atomic CSV export; no Qt dependency."""
from __future__ import annotations

import csv
import json
import math
import os
import tempfile
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

SERVICE_UUID = "bb239920-bdbc-4d51-8a12-a5351875d891"
CSV_FIELDS = (
    "fecha_hora_utc", "co2_ppm", "temperatura_c", "humedad_pct", "pm1_0",
    "pm2_5", "pm4_0", "pm10", "voc_index", "nox_index", "latitud",
    "longitud", "origin", "session", "sequence",
)
CHANNELS = (
    ("co2_ppm", "CO₂", "ppm"), ("temperature_c", "Temperatura", "°C"),
    ("humidity_pct", "Humedad", "% HR"), ("pm1_0", "PM1.0", "µg/m³"),
    ("pm2_5", "PM2.5", "µg/m³"), ("pm4_0", "PM4.0", "µg/m³"),
    ("pm10", "PM10", "µg/m³"), ("voc_index", "VOC", "índice"),
    ("nox_index", "NOx", "índice"),
)


@dataclass(frozen=True)
class Measurement:
    received_at_ms: int
    co2_ppm: float
    origin: str
    session: str
    sequence: int
    temperature_c: float | None = None
    humidity_pct: float | None = None
    pm1_0: float | None = None
    pm2_5: float | None = None
    pm4_0: float | None = None
    pm10: float | None = None
    voc_index: float | None = None
    nox_index: float | None = None
    latitude: float | None = None
    longitude: float | None = None
    sender_boot: int | None = None
    sender_sequence: int | None = None
    acquired_uptime_ms: int | None = None
    signal_level: int | None = None


def optional_number(value, low=-math.inf, high=math.inf):
    if type(value) not in (int, float):
        return None
    try:
        value = float(value)
    except OverflowError:
        return None
    return value if math.isfinite(value) and low <= value <= high else None


def parse_measurement(line: bytes | str) -> Measurement | None:
    try:
        obj = json.loads(line)
        if not isinstance(obj, dict) or type(obj.get("v")) is not int or obj["v"] != 1:
            return None
        timestamp, sequence = obj["received_at_ms"], obj["sequence"]
        if type(timestamp) is not int or not 0 <= timestamp <= 253402300799999:
            return None
        if type(sequence) is not int or not 0 <= sequence <= 2**63 - 1:
            return None
        co2 = optional_number(obj["co2_ppm"], 0)
        if co2 is None or not isinstance(obj["session"], str) or not obj["session"]:
            return None
        if obj["origin"] not in ("dji", "simulado"):
            return None
        optional = {key: optional_number(obj.get(key), 0) for key in ("pm1_0", "pm2_5", "pm4_0", "pm10")}
        optional.update(temperature_c=optional_number(obj.get("temperature_c")),
                        humidity_pct=optional_number(obj.get("humidity_pct"), 0, 100),
                        voc_index=optional_number(obj.get("voc_index"), 1, 500),
                        nox_index=optional_number(obj.get("nox_index"), 1, 500))
        gps = obj.get("aircraft_position")
        if not isinstance(gps, dict):
            gps = {}
        lat = optional_number(gps.get("latitude"), -90, 90)
        lon = optional_number(gps.get("longitude"), -180, 180)
        if lat is None or lon is None:
            lat = lon = None
        metadata = {key: obj.get(key) if type(obj.get(key)) is int and obj[key] >= 0 else None
                    for key in ("sender_boot", "sender_sequence", "acquired_uptime_ms")}
        return Measurement(timestamp, co2, obj["origin"], obj["session"], sequence,
                           latitude=lat, longitude=lon,
                           signal_level=gps.get("signal_level") if type(gps.get("signal_level")) is int else None,
                           **optional, **metadata)
    except (ValueError, KeyError, TypeError, UnicodeError, RecursionError):
        return None


class NdjsonFramer:
    def __init__(self, limit=16384):
        self.limit = limit
        self.buffer = bytearray()
        self.discarding = False
        self.oversized = 0

    def feed(self, data: bytes) -> list[bytes]:
        lines = []
        for byte in data:
            if byte == 10:
                if self.buffer and not self.discarding:
                    lines.append(bytes(self.buffer).rstrip(b"\r"))
                self.buffer.clear()
                self.discarding = False
            elif not self.discarding:
                if len(self.buffer) == self.limit:
                    self.buffer.clear()
                    self.discarding = True
                    self.oversized += 1
                else:
                    self.buffer.append(byte)
        return lines


class History:
    def __init__(self, capacity=7200):
        if capacity < 1:
            raise ValueError("capacity must be positive")
        self.samples = deque(maxlen=capacity)
        self.retired = set()
        self.last = None
        self.arrival = None
        self.missing = self.restarts = self.ignored = self.evicted = 0

    def append(self, sample: Measurement, monotonic_time: float) -> bool:
        previous = self.last
        if sample.session in self.retired or (previous and previous.session == sample.session and sample.sequence <= previous.sequence):
            self.ignored += 1
            return False
        if previous:
            if previous.session != sample.session:
                self.retired.add(previous.session)
                self.restarts += 1
            else:
                self.missing += sample.sequence - previous.sequence - 1
        if len(self.samples) == self.samples.maxlen:
            self.evicted += 1
        self.samples.append(sample)
        self.last, self.arrival = sample, monotonic_time
        return True

    def fresh(self, now: float) -> bool:
        return self.arrival is not None and 0 <= now - self.arrival <= 5

    def alarm(self, now: float, threshold: int, connected=True) -> bool:
        return bool(connected and self.last and self.fresh(now) and self.last.co2_ppm >= threshold)


def utc_label(ms: int) -> str:
    seconds, millis = divmod(ms, 1000)
    return datetime.fromtimestamp(seconds, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S") + f".{millis:03d}Z"


def local_label(ms: int) -> str:
    try:
        return datetime.fromtimestamp(ms / 1000).strftime("%H:%M:%S")
    except (ValueError, OverflowError, OSError):
        return "—"


def export_csv(path: str | Path, samples: Iterable[Measurement]):
    """Write a snapshot to a sibling temporary file, then replace atomically.

    Errors preserve the previous destination and remove the incomplete temporary file.
    """
    target = Path(path)
    temp = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="", dir=target.parent,
                                         prefix=".microgas-", suffix=".tmp", delete=False) as stream:
            temp = Path(stream.name)
            writer = csv.writer(stream, lineterminator="\n")
            writer.writerow(CSV_FIELDS)
            for m in samples:
                writer.writerow((utc_label(m.received_at_ms), m.co2_ppm, m.temperature_c, m.humidity_pct,
                                 m.pm1_0, m.pm2_5, m.pm4_0, m.pm10, m.voc_index, m.nox_index,
                                 m.latitude, m.longitude, m.origin, m.session, m.sequence))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, target)
    finally:
        if temp is not None and temp.exists():
            temp.unlink()


def demo_frame(step: int, session: str, now_ms: int) -> bytes:
    wave = math.sin(step / 8)
    return (json.dumps(dict(v=1, session=session, sequence=step, received_at_ms=now_ms,
                           co2_ppm=650 + 160 * wave, temperature_c=24 + 2 * wave,
                           humidity_pct=60 + 10 * wave, pm1_0=5 + wave, pm2_5=8 + 2 * wave,
                           pm4_0=10 + 3 * wave, pm10=12 + 4 * wave, voc_index=100 + 30 * wave,
                           nox_index=20 + 10 * wave, origin="simulado", aircraft_position=None)) + "\n").encode()
