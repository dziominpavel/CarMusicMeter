#!/usr/bin/env python3
"""Отчёт по выгрузкам CarMusicMeter.

Принимает один или несколько ZIP-экспортов (или распакованных папок),
склеивает журналы без дублей, проверяет manifest.json и считает метрики
по трекам. Формулы и ограничения — docs/METRICS.md.

Примеры:
    py scripts/report.py export.zip
    py scripts/report.py export-1.zip export-2.zip --json
    py scripts/report.py C:\\logs\\export --csv report.csv --all
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import statistics
import sys
import zipfile
from collections import defaultdict
from datetime import datetime
from pathlib import Path

SESSION_GAP_MS = 10 * 60 * 1000
POSITION_RESET_MAX_MS = 1_000
POSITION_RESET_MIN_PLAYED_MS = 5_000
RATED_REASONS = {"completed", "skipped", "interrupted"}
CENSORED_REASONS = {
    "service_stopped", "session_lost", "not_played", "stopped", "unknown",
}

# Оценка симпатии, версия 1. Правила — docs/METRICS.md; зеркало Kotlin-движка.
AFFINITY_VERSION = "v1"
AFFINITY_PRIOR = 0.5
AFFINITY_K = 1.5
AFFINITY_VOLUME_WEIGHT = 0.65
AFFINITY_TIME_WEIGHT = 0.35
AFFINITY_MIN_PLAYS = 30
AFFINITY_CENSORED = {"session_lost", "stopped", "unknown", "service_stopped"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Отчёт по прослушиваниям CarMusicMeter")
    parser.add_argument("paths", nargs="+", help="ZIP-экспорты или папки с logs/")
    parser.add_argument("--json", action="store_true", help="вывести JSON вместо таблицы")
    parser.add_argument("--json-out", metavar="FILE", help="сохранить JSON в файл (UTF-8)")
    parser.add_argument("--csv", metavar="FILE", help="сохранить метрики в CSV")
    parser.add_argument("--plays", metavar="FILE", help="сохранить факты из ZIP в CSV")
    parser.add_argument("--no-verify", action="store_true", help="не проверять manifest.json")
    parser.add_argument("--strict", action="store_true", help="выходить с ошибкой при проблемах манифеста")
    parser.add_argument("--min-plays", type=int, default=3, help="минимум прослушиваний для кандидатов")
    parser.add_argument("--min-duration", type=int, default=60, help="минимальная длительность, секунды")
    parser.add_argument("--session-gap", type=int, default=10, help="разрыв между поездками, минуты")
    parser.add_argument("--limit", type=int, default=40, help="сколько треков показать в таблице")
    parser.add_argument("--all", action="store_true", help="показать все треки")
    return parser.parse_args()


# ---------- загрузка и проверка ----------


def read_json_file(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def load_zip(path: Path, verify: bool):
    events, malformed, manifest, problems, _plays = load_zip_full(path, verify)
    return events, malformed, manifest, problems


def load_zip_full(path: Path, verify: bool):
    events = {}
    plays = []
    malformed = 0
    with zipfile.ZipFile(path) as archive:
        manifest = None
        if "manifest.json" in archive.namelist():
            try:
                manifest = json.loads(archive.read("manifest.json"))
            except ValueError:
                manifest = None

        for name in sorted(archive.namelist()):
            if name.startswith("plays/") and name.endswith(".jsonl"):
                raw = archive.read(name).decode("utf-8", errors="replace")
                for line in raw.splitlines():
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        plays.append(json.loads(line))
                    except ValueError:
                        malformed += 1
                continue
            if not (name.startswith("logs/") and name.endswith(".jsonl")):
                continue
            raw = archive.read(name).decode("utf-8", errors="replace")
            for line in raw.splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except ValueError:
                    malformed += 1
                    continue
                events.setdefault(line, event)

        problems = verify_zip(archive, manifest) if verify and manifest else []
    if plays:
        events = {
            json.dumps(event, sort_keys=True, ensure_ascii=False): event
            for event in plays_to_events(plays)
        }
    return list(events.values()), malformed, manifest, problems, plays


def verify_zip(archive: zipfile.ZipFile, manifest: dict) -> list[str]:
    problems = []
    listed = set()
    for item in manifest.get("files", []):
        rel = item.get("path", "")
        listed.add(rel)
        try:
            data = archive.read(rel)
        except KeyError:
            problems.append(f"нет файла: {rel}")
            continue
        if item.get("size") is not None and len(data) != item["size"]:
            problems.append(f"размер не совпал: {rel}")
        digest = hashlib.sha256(data).hexdigest()
        if item.get("sha256") and digest != item["sha256"]:
            problems.append(f"SHA-256 не совпал: {rel}")
    extras = [
        name
        for name in archive.namelist()
        if not name.endswith("/") and name != "manifest.json" and name not in listed
    ]
    if extras:
        problems.append(f"файлов вне манифеста: {len(extras)}")
    return problems


def load_dir(path: Path, verify: bool):
    events, malformed, manifest, problems, _plays = load_dir_full(path, verify)
    return events, malformed, manifest, problems


def load_dir_full(path: Path, verify: bool):
    events = {}
    plays = []
    malformed = 0
    manifest = read_json_file(path / "manifest.json")
    plays_dir = path / "plays"
    play_files = sorted(plays_dir.glob("*.jsonl")) if plays_dir.is_dir() else []
    for play_file in play_files:
        with play_file.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    plays.append(json.loads(line))
                except ValueError:
                    malformed += 1
    logs = sorted((path / "logs").glob("*.jsonl")) if (path / "logs").is_dir() else []
    for log in logs:
        with log.open(encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except ValueError:
                    malformed += 1
                    continue
                events.setdefault(line, event)
    problems = verify_dir(path, manifest) if verify and manifest else []
    if plays:
        events = {
            json.dumps(event, sort_keys=True, ensure_ascii=False): event
            for event in plays_to_events(plays)
        }
    return list(events.values()), malformed, manifest, problems, plays


def verify_dir(path: Path, manifest: dict) -> list[str]:
    problems = []
    listed = set()
    for item in manifest.get("files", []):
        rel = item.get("path", "")
        listed.add(rel)
        file = path / rel
        if not file.is_file():
            problems.append(f"нет файла: {rel}")
            continue
        if item.get("size") is not None and file.stat().st_size != item["size"]:
            problems.append(f"размер не совпал: {rel}")
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        if item.get("sha256") and digest != item["sha256"]:
            problems.append(f"SHA-256 не совпал: {rel}")
    for sub in ("logs", "snapshots"):
        folder = path / sub
        if not folder.is_dir():
            continue
        for file in folder.iterdir():
            if file.is_file() and f"{sub}/{file.name}" not in listed:
                problems.append(f"файл вне манифеста: {sub}/{file.name}")
    return problems


def load_exports(paths: list[str], verify: bool):
    events = {}
    plays_by_id = {}
    malformed = 0
    sources = []
    manifests = []
    problems = []
    for raw_path in paths:
        path = Path(raw_path)
        if path.is_dir():
            found, bad, manifest, issues, found_plays = load_dir_full(path, verify)
        elif path.suffix.lower() == ".zip":
            found, bad, manifest, issues, found_plays = load_zip_full(path, verify)
        else:
            raise SystemExit(f"Не ZIP и не папка: {raw_path}")
        malformed += bad
        problems.extend(f"{path.name}: {issue}" for issue in issues)
        if manifest:
            manifests.append(manifest)
        sources.append({
            "path": str(path),
            "events": len(found),
            "plays": len(found_plays),
            "manifest": manifest is not None,
        })
        for play in found_plays:
            key = play.get("playId") or json.dumps(play, sort_keys=True, ensure_ascii=False)
            plays_by_id.setdefault(key, play)
        for event in found:
            key = json.dumps(event, sort_keys=True, ensure_ascii=False)
            events.setdefault(key, event)

    ordered = order_events(list(events.values()))
    return ordered, malformed, sources, manifests, problems, list(plays_by_id.values())


def plays_to_events(plays: list[dict]) -> list[dict]:
    """Превращает готовые факты в события, которые понимает analyze().

    Так отчёт читает новый ZIP без второй реализации метрик: факты уже
    содержат playedMs, endReason и volumeStats. Синтетические seek-события
    сохраняют счётчики перемоток.
    """
    events: list[dict] = []
    for play in plays:
        if not isinstance(play, dict):
            continue
        play_id = play.get("playId") or "play"
        track_key = play.get("trackKey")
        started_at = int(play.get("startedAt") or 0)
        verified = play.get("clockVerified", True) is not False
        common = {
            "bootId": play_id,
            "clockVerified": verified,
            "trackKey": track_key,
            "package": play.get("package"),
            "source": play.get("source"),
            "title": play.get("title"),
            "artist": play.get("artist"),
            "album": play.get("album"),
            "mediaId": play.get("mediaId"),
            "durationMs": play.get("durationMs"),
        }
        events.append({
            "ts": started_at,
            "type": "track_started",
            "positionMs": None,
            "volume": play.get("volumeStart"),
            "volumeMax": play.get("volumeMax"),
            "volumeSource": play.get("volumeSource"),
            **common,
        })
        if play.get("open"):
            events.append({
                "ts": int(play.get("endedAt") or started_at),
                "type": "track_progress",
                "positionMs": play.get("endPositionMs"),
                "playedMs": play.get("playedMs") or 0,
                "volume": play.get("volumeEnd"),
                "volumeMax": play.get("volumeMax"),
                "volumeSource": play.get("volumeSource"),
                "phase": "playing",
                "startedAt": started_at,
                **common,
            })
        else:
            ended = {
                "ts": int(play.get("endedAt") or started_at),
                "type": "track_ended",
                "playedMs": play.get("playedMs") or 0,
                "rawPlayedMs": play.get("rawPlayedMs") or play.get("playedMs") or 0,
                "endPositionMs": play.get("endPositionMs"),
                "endReason": play.get("endReason") or "unknown",
                "completed": bool(play.get("completed")),
                "startedAt": started_at,
                "volumeStart": play.get("volumeStart"),
                "volumeEnd": play.get("volumeEnd"),
                "volumeLow": play.get("volumeLow"),
                "volumeHigh": play.get("volumeHigh"),
                "volumeChanges": play.get("volumeChanges") or 0,
                "volumeMax": play.get("volumeMax"),
                "volumeSource": play.get("volumeSource"),
                "volumeStats": play.get("volumeStats") or {},
                **common,
            }
            if play.get("restored"):
                ended["restored"] = True
            events.append(ended)
        for _ in range(int(play.get("seekBack") or 0)):
            events.append({
                "ts": started_at + 1,
                "type": "seek",
                "deltaMs": -1,
                "fromMs": 1,
                "toMs": 0,
                **common,
            })
        for _ in range(int(play.get("seekForward") or 0)):
            events.append({
                "ts": started_at + 1,
                "type": "seek",
                "deltaMs": 1,
                "fromMs": 0,
                "toMs": 1,
                **common,
            })
    return events


def write_plays_csv(path: str, plays: list[dict]) -> None:
    fields = [
        "playId", "origin", "trackKey", "package", "source", "title", "artist",
        "startedAt", "endedAt", "playedMs", "endReason", "open",
    ]
    with open(path, "w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for play in plays:
            writer.writerow({key: play.get(key) for key in fields})


# ---------- порядок и поправка часов ----------


def clock_jumps(events: list[dict]) -> dict[str, list[tuple[int, int]]]:
    """Скачки часов по процессам: ``bootId`` -> [(monoMs, wallDelta)]."""
    jumps: dict[str, list[tuple[int, int]]] = {}
    for event in events:
        if event.get("type") != "clock_changed":
            continue
        boot = event.get("bootId")
        mono = event.get("monoMs")
        delta = event.get("wallDelta")
        if boot is None or mono is None or not isinstance(delta, (int, float)):
            continue
        jumps.setdefault(boot, []).append((int(mono), int(delta)))
    return jumps


def corrected_ts(event: dict, jumps: dict[str, list[tuple[int, int]]]) -> int:
    """Время события с поправкой на скачки часов того же процесса.

    Событие до скачка записано по старым часам; сдвиг на величину скачка ставит
    его на реальное место. Старые ZIP без ``bootId``/``monoMs`` считаются по ``ts``.
    """
    ts = int(event.get("ts") or 0)
    boot = event.get("bootId")
    mono = event.get("monoMs")
    if boot is None or mono is None:
        return ts
    shift = sum(delta for jump_mono, delta in jumps.get(boot, ()) if jump_mono > mono)
    return int(ts + shift)


def order_events(events: list[dict]) -> list[dict]:
    """Упорядочивает события и правит даты, испорченные сбитыми часами.

    Внутри одного процесса порядок задаёт ``monoMs`` (он не зависит от часов),
    между процессами — исправленное время первого события. Старые ZIP без
    конверта остаются упорядоченными по ``ts``.
    """
    jumps = clock_jumps(events)
    for event in events:
        raw = int(event.get("ts") or 0)
        fixed = corrected_ts(event, jumps)
        if fixed != raw:
            event["_rawTs"] = raw
            event["ts"] = fixed

    blocks: list[tuple[int, list[dict]]] = []
    by_boot: dict[str, list[dict]] = {}
    for event in events:
        boot = event.get("bootId")
        if boot is not None and event.get("monoMs") is not None:
            by_boot.setdefault(boot, []).append(event)
        else:
            blocks.append((int(event.get("ts") or 0), [event]))
    for boot_events in by_boot.values():
        boot_events.sort(key=lambda item: int(item.get("monoMs") or 0))
        blocks.append((int(boot_events[0].get("ts") or 0), boot_events))
    blocks.sort(key=lambda block: block[0])
    return [event for _, block in blocks for event in block]


# ---------- анализ ----------


# События без отметки о достоверности часов (архивы схемы 1) считаются
# датированными, если их дата не раньше чем за год до выгрузки: приложение
# появилось в 2026-м, и записей из прошлого у него быть не может.
CONFIRMATION_HORIZON_MS = 365 * 24 * 60 * 60 * 1000


def date_is_confirmed(
    event: dict,
    jumps: dict[str, list[tuple[int, int]]],
    exported_at_ms: int | None,
) -> bool:
    """Можно ли верить дате события.

    ``clockVerified`` — отметка новой сборки. Непроверенное событие всё же
    датируется, если в его процессе есть ``clock_changed`` позже него: отчёт
    уже сдвинул ``ts`` по поправке. Архивы схемы 1 отметки не имеют, для них
    остаётся эвристика «слишком старая дата невозможна».
    """
    flag = event.get("clockVerified")
    if flag is True:
        return True
    boot = event.get("bootId")
    mono = event.get("monoMs")
    if flag is False:
        if boot is None or mono is None:
            return False
        return any(jump_mono > int(mono) for jump_mono, _ in jumps.get(boot, ()))
    if exported_at_ms is None:
        return True
    return int(event.get("ts") or 0) >= exported_at_ms - CONFIRMATION_HORIZON_MS


def split_by_date(
    events: list[dict],
    jumps: dict[str, list[tuple[int, int]]],
    exported_at_ms: int | None,
) -> tuple[list[dict], list[dict]]:
    """Делит события на датированные и неподтверждённые."""
    dated: list[dict] = []
    undated: list[dict] = []
    for event in events:
        if date_is_confirmed(event, jumps, exported_at_ms):
            dated.append(event)
        else:
            undated.append(event)
    return dated, undated


def undated_summary(undated: list[dict]) -> dict:
    """Сколько событий осталось без подтверждённой даты."""
    timestamps = [int(event.get("ts") or 0) for event in undated]
    return {
        "events": len(undated),
        "starts": sum(1 for event in undated if event.get("type") == "track_started"),
        "first_ts": min(timestamps) if timestamps else None,
        "last_ts": max(timestamps) if timestamps else None,
    }


def fmt_ms(ms: float | int | None) -> str:
    if not ms:
        return "-"
    total = int(ms / 1000)
    hours, rest = divmod(total, 3600)
    minutes, seconds = divmod(rest, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{seconds:02d}"
    return f"{minutes}:{seconds:02d}"


def pct(value: float | None) -> str:
    return "-" if value is None else f"{value * 100:.0f}%"


def date_of(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d")


def time_of(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M")


def new_track(key: str) -> dict:
    return {
        "key": key,
        "title": None,
        "artist": None,
        "package": None,
        "starts": [],
        "plays": [],
        "last_pause": None,
        "unknown": 0,
        "vol_changes": 0,
        "vol_up": 0,
        "vol_down": 0,
        "seek_back": 0,
        "seek_fwd": 0,
    }


def normalize_text(value) -> str:
    return " ".join(str(value or "").strip().lower().split())


def group_key(event: dict) -> str | None:
    """Единый ключ для старых (с duration/mediaId) и новых trackKey.

    Новый trackKey сам стабилен (package::title::artist), поэтому его нельзя
    пересобирать из полей: поздний artist иначе разведёт start и end.
    Старый ключ содержит mediaId/duration; для него группируем по полям.
    """
    key = event.get("trackKey")
    if key and str(key).count("::") == 2:
        return str(key)
    package = event.get("package")
    title = event.get("title")
    if package and title:
        return f"{package}::{normalize_text(title)}::{normalize_text(event.get('artist'))}"
    return str(key) if key else None


def mcu_volume(event: dict, field: str):
    """Значение из ``volumeStats.mcu``: дельта громкости считается только по MCU.

    Верхнеуровневые ``volumeStart``/``volumeEnd`` отражают последний сегмент
    источника, поэтому при переключении на ``audio`` они дают фиктивные 25/25.
    """
    stats = event.get("volumeStats")
    if not isinstance(stats, dict):
        return None
    mcu = stats.get("mcu")
    if not isinstance(mcu, dict):
        return None
    value = mcu.get(field)
    return value if isinstance(value, (int, float)) else None


def _affinity_median(values):
    return statistics.median(values) if values else 0


def _affinity_percentile(sorted_values, fraction):
    if not sorted_values:
        return 0
    index = round((len(sorted_values) - 1) * fraction)
    return sorted_values[max(0, min(index, len(sorted_values) - 1))]


def _affinity_plural(n, one, few, many):
    mod100 = n % 100
    mod10 = n % 10
    if mod100 in range(11, 15):
        return many
    if mod10 == 1:
        return one
    if mod10 in range(2, 5):
        return few
    return many


def _format_offset(ms):
    if ms is None:
        return None
    total = max(0, int(ms // 1000))
    return f"{total // 60}:{total % 60:02d}"


def play_volume(play):
    """Агрегаты громкости MCU одного прослушивания.

    Новые факты хранят up/down/level/raiseMs/dropMs; у старых их нет, тогда
    берём start/low/high и помечаем вклад приблизительным.
    """
    start = play.get("mcu_start")
    if start is None:
        return None
    low = play.get("mcu_low")
    high = play.get("mcu_high")
    end = play.get("mcu_end")
    up = play.get("mcu_up")
    down = play.get("mcu_down")
    level = play.get("mcu_level")
    low_value = int(low if low is not None else start)
    high_value = int(high if high is not None else start)
    end_value = int(end if end is not None else start)
    approximate = up is None or down is None or level is None
    return {
        "start": int(start),
        "low": low_value,
        "high": high_value,
        "end": end_value,
        "up": int(up) if up is not None else max(0, high_value - int(start)),
        "down": int(down) if down is not None else max(0, int(start) - low_value),
        "level": int(level) if level is not None else (int(start) + end_value) // 2,
        "raise_ms": play.get("mcu_raise_ms"),
        "drop_ms": play.get("mcu_drop_ms"),
        "approximate": approximate,
    }


def calibrate_affinity(plays):
    volumes = [volume for volume in (play_volume(play) for play in plays) if volume]
    if len(volumes) < AFFINITY_MIN_PLAYS:
        return {"ceiling": 30, "habit": 15, "iqr": 10.0, "up": 5, "down": 3, "trained": False}
    levels = sorted(volume["level"] for volume in volumes)
    samples = sorted(
        value for volume in volumes
        for value in (volume["start"], volume["low"], volume["high"], volume["end"], volume["level"])
    )
    ups = sorted(volume["up"] for volume in volumes if volume["up"] > 0)
    downs = sorted(volume["down"] for volume in volumes if volume["down"] > 0)
    return {
        "ceiling": max(1, _affinity_percentile(samples, 0.95)),
        "habit": int(_affinity_median(levels)),
        "iqr": max(4.0, _affinity_percentile(levels, 0.75) - _affinity_percentile(levels, 0.25)),
        "up": int(_affinity_median(ups)) if ups else 5,
        "down": int(_affinity_median(downs)) if downs else 3,
        "trained": True,
    }


def trip_levels(plays):
    by_session = {}
    for play in plays:
        volume = play_volume(play)
        if volume:
            by_session.setdefault(play.get("session", 0), []).append(volume["level"])
    return {
        session: int(_affinity_median(values))
        for session, values in by_session.items() if values
    }


def play_affinity(play, calibration, trip_level):
    reason = play.get("reason")
    if reason == "not_played":
        return None
    if play.get("restored") and play.get("played", 0) <= 0:
        return None
    duration = play.get("duration") or 0
    heard = min(max(play.get("played", 0) / duration, 0.0), 1.0) if duration > 0 else None
    volume = play_volume(play)
    if reason == "completed":
        time_score = 1.0
    elif reason == "interrupted":
        time_score = 0.4
    elif reason == "skipped" and heard is not None:
        time_score = 0.05 + 0.75 * heard
    elif reason in AFFINITY_CENSORED and heard is not None:
        time_score = 0.25 + 0.5 * heard
    else:
        time_score = None
    if volume is None and time_score is None:
        return None
    if volume is None:
        value = 0.5
    else:
        headroom = max(4, calibration["ceiling"] - volume["start"])
        up = 0.5 * min(volume["up"] / calibration["up"], 1.0) + 0.5 * min(volume["up"] / headroom, 1.0)
        down = 0.0
        if reason in ("skipped", "interrupted"):
            down = (
                0.5 * min(volume["down"] / calibration["down"], 1.0)
                + 0.5 * min(volume["down"] / max(4, volume["start"]), 1.0)
            )
        habit = max(-1.0, min(1.0, (volume["level"] - calibration["habit"]) / calibration["iqr"]))
        trip = 0.0
        if trip_level is not None:
            trip = max(-1.0, min(1.0, (volume["level"] - trip_level) / calibration["iqr"]))
        state = 0.5 * habit + 0.5 * trip
        heard_factor = 0.4 + 0.6 * (heard if heard is not None else 0.3)
        value = max(0.0, min(1.0, 0.5 + 0.35 * up - 0.25 * down + 0.15 * state * heard_factor))
    satisfaction = (
        value if time_score is None
        else AFFINITY_VOLUME_WEIGHT * value + AFFINITY_TIME_WEIGHT * time_score
    )
    weight = 0.6 if (time_score is None or volume is None or reason in AFFINITY_CENSORED) else 1.0
    return {
        "s": satisfaction,
        "w": weight,
        "volume": volume,
        "heard": heard,
        "reason": reason,
        "played": play,
    }


def affinity_reasons(items):
    reasons = []
    with_up = [item for item in items if item["volume"] and item["volume"]["up"] >= 1]
    if with_up:
        best = max(with_up, key=lambda item: item["volume"]["up"])
        offset = _format_offset(best["volume"]["raise_ms"])
        reasons.append(
            f"подъём +{best['volume']['up']}" + (f" на {offset}" if offset else "")
        )
    with_down = [
        item for item in items
        if item["volume"] and item["volume"]["down"] >= 3
        and item["reason"] in ("skipped", "interrupted")
    ]
    if with_down:
        best = max(with_down, key=lambda item: item["volume"]["down"])
        offset = _format_offset(best["volume"]["drop_ms"])
        reasons.append(
            f"понижение −{best['volume']['down']}" + (f" на {offset}" if offset else "")
        )
    heard = [item["heard"] for item in items if item["heard"] is not None]
    if heard:
        reasons.append(f"прослушано в среднем {round(100 * sum(heard) / len(heard))}%")
    completed = sum(1 for item in items if item["reason"] == "completed")
    skipped = sum(1 for item in items if item["reason"] == "skipped")
    if completed == 0 and skipped > 0:
        reasons.append(
            f"{skipped} {_affinity_plural(skipped, 'скип', 'скипа', 'скипов')}, ни разу не дослушан"
        )
    elif completed == 0:
        reasons.append("ни разу не дослушан")
    else:
        reasons.append(f"дослушан {completed} {_affinity_plural(completed, 'раз', 'раза', 'раз')}")
    if any(item["volume"] is None for item in items):
        reasons.append("громкость известна не везде")
    if len(items) < 2:
        reasons.append("мало данных")
    return reasons


def track_affinity(plays, calibration, trip_levels_map):
    items = []
    for play in plays:
        item = play_affinity(play, calibration, trip_levels_map.get(play.get("session", 0)))
        if item:
            items.append(item)
    if not items:
        return None
    weight = sum(item["w"] for item in items)
    total = sum(item["s"] * item["w"] for item in items)
    score = round(100 * (total + AFFINITY_PRIOR * AFFINITY_K) / (weight + AFFINITY_K))
    with_volume = sum(1 for item in items if item["volume"])
    approximate = with_volume == 0 or any(
        item["volume"] is not None and item["volume"]["approximate"] for item in items
    )
    return {
        "score": int(max(0, min(100, score))),
        "confidence": round(weight, 2),
        "plays": len(items),
        "approximate": approximate,
        "max_up": max((item["volume"]["up"] for item in items if item["volume"]), default=0),
        "reasons": affinity_reasons(items),
    }


def analyze(events: list[dict], session_gap_ms: int):
    # Сессии (поездки) по разрывам между событиями.
    sessions = []
    previous_ts = None
    for event in events:
        ts = int(event.get("ts") or 0)
        if previous_ts is None or ts - previous_ts > session_gap_ms:
            sessions.append({"start": ts, "end": ts, "values": []})
        sessions[-1]["end"] = ts
        if event.get("type") == "volume_changed" and isinstance(event.get("value"), (int, float)):
            sessions[-1]["values"].append(event["value"])
        event["_session"] = len(sessions) - 1
        previous_ts = ts

    all_values = [value for session in sessions for value in session["values"]]
    global_median = statistics.median(all_values) if all_values else None
    medians = [
        statistics.median(session["values"]) if session["values"] else global_median
        for session in sessions
    ]

    tracks: dict[str, dict] = {}
    current: dict[str, dict] = {}
    last_progress: dict[str, dict] = {}
    last_volume = None
    position_resets = 0

    for event in events:
        event_type = event.get("type")
        key = group_key(event)
        ts = int(event.get("ts") or 0)

        if event_type == "volume_changed":
            last_volume = event.get("value", last_volume)
            if not key:
                continue
            track = tracks.setdefault(key, new_track(key))
            track["vol_changes"] += 1
            delta = event.get("delta") or 0
            if delta > 0:
                track["vol_up"] += 1
            elif delta < 0:
                track["vol_down"] += 1
            continue

        if not key:
            continue

        if event_type == "track_progress":
            if key not in current:
                continue
            track = tracks.get(key)
            if track is not None:
                for field in ("title", "artist", "package"):
                    if event.get(field) is not None:
                        track[field] = event[field]
            last_progress[key] = {
                "ts": ts,
                "played": int(event.get("playedMs") or 0),
                "end_pos": event.get("positionMs"),
                "volume": event.get("volume"),
                "duration": event.get("durationMs"),
                "session": event["_session"],
            }
            continue

        track = tracks.setdefault(key, new_track(key))
        for field, source in (("title", "title"), ("artist", "artist"), ("package", "package")):
            if event.get(source) is not None:
                track[field] = event[source]

        if event_type == "track_started":
            info = {
                "ts": ts,
                "volume": event.get("volume"),
                "duration": event.get("durationMs"),
                "source": event.get("source") or "media_session",
                "session": event["_session"],
            }
            track["starts"].append(info)
            current[key] = info

        elif event_type == "track_ended":
            start = current.pop(key, None) or {}
            end_pos = event.get("endPositionMs")
            played = int(event.get("playedMs") or 0)
            duration = event.get("durationMs") or start.get("duration")
            if (
                end_pos is not None
                and end_pos <= POSITION_RESET_MAX_MS
                and played > POSITION_RESET_MIN_PLAYED_MS
            ):
                position_resets += 1
                end_pos = None
            track["plays"].append({
                "startedAt": int(event.get("startedAt") or start.get("ts") or ts),
                "played": played,
                "raw_played": int(event.get("rawPlayedMs") or played),
                "duration": duration,
                "end_pos": end_pos,
                "reason": event.get("endReason"),
                "restored": bool(event.get("restored")),
                "source": event.get("source") or start.get("source") or "media_session",
                "vol_start": event.get("volumeStart", start.get("volume")),
                "vol_end": event.get("volumeEnd", last_volume),
                "mcu_start": mcu_volume(event, "start"),
                "mcu_end": mcu_volume(event, "end"),
                "mcu_low": mcu_volume(event, "low"),
                "mcu_high": mcu_volume(event, "high"),
                "mcu_up": mcu_volume(event, "up"),
                "mcu_down": mcu_volume(event, "down"),
                "mcu_level": mcu_volume(event, "level"),
                "mcu_raise_ms": mcu_volume(event, "raiseMs"),
                "mcu_drop_ms": mcu_volume(event, "dropMs"),
                "session": start.get("session", event["_session"]),
            })

        elif event_type == "playback_paused":
            track["last_pause"] = {
                "ts": ts,
                "played": int(event.get("playedMs") or 0),
                "volume": last_volume,
            }

        elif event_type == "seek":
            if (event.get("deltaMs") or 0) < 0:
                track["seek_back"] += 1
            else:
                track["seek_fwd"] += 1

    all_closed = [play for track in tracks.values() for play in track["plays"]]
    calibration = calibrate_affinity(all_closed)
    trip_levels_map = trip_levels(all_closed)

    metrics = []
    for track in tracks.values():
        closed = track["plays"]
        open_play = None
        if track["key"] in current:
            start = current[track["key"]]
            pause = track["last_pause"]
            checkpoint = last_progress.get(track["key"])
            pause_ts = pause["ts"] if pause else -1
            checkpoint_ts = checkpoint["ts"] if checkpoint else -1
            use_checkpoint = checkpoint is not None and checkpoint_ts > start["ts"] and (
                pause is None or checkpoint_ts >= pause_ts
            )
            use_pause = not use_checkpoint and pause is not None and pause_ts > start["ts"]
            open_play = {
                "startedAt": start["ts"],
                "played": (
                    checkpoint["played"] if use_checkpoint
                    else pause["played"] if use_pause
                    else 0
                ),
                "raw_played": (
                    checkpoint["played"] if use_checkpoint
                    else pause["played"] if use_pause
                    else 0
                ),
                "duration": (
                    checkpoint.get("duration") if use_checkpoint
                    else start.get("duration")
                ) or start.get("duration"),
                "end_pos": checkpoint.get("end_pos") if use_checkpoint else None,
                "reason": "open",
                "vol_start": start.get("volume"),
                "vol_end": (
                    checkpoint.get("volume") if use_checkpoint
                    else pause["volume"] if use_pause
                    else None
                ),
                "session": start["session"],
            }

        completions = []
        listened_values = []
        skip_points = []
        volume_deltas = []
        volume_vs_trip = []
        volume_known = 0
        volume_unknown = 0
        if open_play and open_play.get("duration") and open_play["played"]:
            listened_values.append(min(open_play["played"] / open_play["duration"], 1.0))
        for play in closed:
            duration = play.get("duration")
            if duration and duration > 0:
                # Пустое восстановленное закрытие — артефакт перезапуска
                # процесса, а не прослушивание: «слышал 0%» оно не портит.
                if not (play.get("restored") and play["played"] <= 0):
                    listened_values.append(min(play["played"] / duration, 1.0))
                if play["reason"] in RATED_REASONS:
                    progress = play.get("end_pos")
                    if progress is None:
                        progress = play["played"]
                    completions.append(min(max(progress / duration, 0.0), 1.0))
                if play["reason"] == "skipped":
                    point = play.get("end_pos")
                    if point is None:
                        point = play["played"]
                    skip_points.append(min(max(point / duration, 0.0), 1.0))
            mcu_start = play.get("mcu_start")
            mcu_end = play.get("mcu_end")
            if mcu_start is not None and mcu_end is not None:
                volume_deltas.append(mcu_end - mcu_start)
                volume_known += 1
                median = medians[play.get("session", 0)] if medians else None
                if median is not None:
                    volume_vs_trip.append(mcu_end - median)
            else:
                volume_unknown += 1

        skipped = sum(1 for play in closed if play["reason"] == "skipped")
        completed = sum(1 for play in closed if play["reason"] == "completed")
        interrupted = sum(1 for play in closed if play["reason"] == "interrupted")
        service_stopped = sum(1 for play in closed if play["reason"] == "service_stopped")
        session_lost = sum(1 for play in closed if play["reason"] == "session_lost")
        not_played = sum(1 for play in closed if play["reason"] == "not_played")
        stopped = sum(1 for play in closed if play["reason"] == "stopped")
        unknown = sum(1 for play in closed if play["reason"] == "unknown")
        rated_plays = sum(1 for play in closed if play["reason"] in RATED_REASONS)
        early = sum(1 for point in skip_points if point < 0.25)
        played_total = sum(play["played"] for play in closed)
        raw_played_total = sum(play.get("raw_played", play["played"]) for play in closed)
        restored = sum(1 for play in closed if play.get("restored"))
        restored_empty = sum(
            1 for play in closed if play.get("restored") and play["played"] <= 0
        )
        if open_play:
            played_total += open_play["played"]
            raw_played_total += open_play.get("raw_played", open_play["played"])
        plays_total = len(track["starts"]) - restored_empty
        closed_total = len(closed) - restored_empty
        durations = [play["duration"] for play in closed if play.get("duration")]
        if open_play and open_play.get("duration"):
            durations.append(open_play["duration"])
        track_duration = statistics.median(durations) if durations else None
        affinity = track_affinity(closed, calibration, trip_levels_map)

        metrics.append({
            "key": track["key"],
            "title": track["title"] or "(без названия)",
            "artist": track["artist"],
            "package": track["package"],
            "plays": plays_total,
            "closed_plays": closed_total,
            "rated_plays": rated_plays,
            "open": open_play is not None,
            "completed": completed,
            "skipped": skipped,
            "interrupted": interrupted,
            "service_stopped": service_stopped,
            "session_lost": session_lost,
            "not_played": not_played,
            "stopped": stopped,
            "unknown": unknown,
            "restored": restored,
            "restored_empty": restored_empty,
            "played_ms": played_total,
            "raw_played_ms": raw_played_total,
            "censored_ms": max(0, raw_played_total - played_total),
            "duration_ms": track_duration,
            "completion": statistics.mean(completions) if completions else None,
            "listened": statistics.mean(listened_values) if listened_values else None,
            "skip_rate": (skipped / rated_plays) if rated_plays else None,
            "early_skip_rate": (early / skipped) if skipped else None,
            "early_skips": early,
            "avg_skip_pct": statistics.mean(skip_points) if skip_points else None,
            "volume_delta": statistics.mean(volume_deltas) if volume_deltas else None,
            "volume_vs_trip": statistics.mean(volume_vs_trip) if volume_vs_trip else None,
            "volume_known": volume_known,
            "volume_unknown": volume_unknown,
            "volume_changes": track["vol_changes"],
            "volume_up": track["vol_up"],
            "volume_down": track["vol_down"],
            "seek_back": track["seek_back"],
            "seek_fwd": track["seek_fwd"],
            "days": len({date_of(start["ts"]) for start in track["starts"]}),
            "last_played": max((start["ts"] for start in track["starts"]), default=None),
            "affinity": affinity["score"] if affinity else None,
            "affinity_confidence": affinity["confidence"] if affinity else None,
            "affinity_plays": affinity["plays"] if affinity else 0,
            "affinity_approx": affinity["approximate"] if affinity else False,
            "affinity_reasons": affinity["reasons"] if affinity else [],
            "volume_up_max": affinity["max_up"] if affinity else 0,
        })

    metrics.sort(key=lambda item: (-item["plays"], -(item["played_ms"] or 0)))
    return metrics, sessions, medians, position_resets


def source_summary(events: list[dict]) -> dict[str, dict]:
    """Считает начала и окончания по источникам, не смешивая их."""
    stats: dict[str, dict] = {}
    for event in events:
        event_type = event.get("type")
        if event_type not in ("track_started", "track_ended"):
            continue
        source = event.get("source") or "media_session"
        item = stats.setdefault(source, {
            "starts": 0,
            "closed": 0,
            "rated": 0,
            "completed": 0,
            "skipped": 0,
            "interrupted": 0,
            "unknown": 0,
            "censored": 0,
        })
        if event_type == "track_started":
            item["starts"] += 1
            continue
        item["closed"] += 1
        reason = event.get("endReason")
        if reason in RATED_REASONS:
            item["rated"] += 1
        else:
            item["censored"] += 1
        if reason in ("completed", "skipped", "interrupted", "unknown"):
            item[reason] += 1
    return dict(sorted(stats.items()))


def capture_gap_summary(events: list[dict]) -> dict:
    """Разрывы сбора: только начала, чтобы не удваивать границы."""
    gaps = [
        event for event in events
        if event.get("type") == "capture_gap" and event.get("active") is True
    ]
    reasons: dict[str, int] = {}
    for event in gaps:
        reason = str(event.get("reason") or "unknown")
        reasons[reason] = reasons.get(reason, 0) + 1
    return {
        "count": len(gaps),
        "last_ts": max((int(event.get("ts") or 0) for event in gaps), default=None),
        "reasons": reasons,
    }


def media_button_summary(events: list[dict]) -> dict:
    """Считает нажатия, не удваивая down/up."""
    stats = {"next": 0, "previous": 0, "play_pause": 0, "other": 0, "total": 0}
    for event in events:
        if event.get("type") != "media_button":
            continue
        action = event.get("action")
        if action not in (None, "down"):
            continue
        key = str(event.get("key") or "")
        if key == "media_next":
            stats["next"] += 1
        elif key == "media_previous":
            stats["previous"] += 1
        elif key == "media_play_pause":
            stats["play_pause"] += 1
        else:
            stats["other"] += 1
        stats["total"] += 1
    return stats


def delete_candidates(metrics: list[dict], min_plays: int, min_duration: int) -> list[dict]:
    """Кандидаты «послушать ещё» по оценке симпатии (v1): рейтинг, а не приговор."""
    candidates = []
    for track in metrics:
        if track.get("affinity") is None or track["affinity"] > 30:
            continue
        if track["rated_plays"] < min_plays:
            continue
        duration = track.get("duration_ms")
        if duration is not None and duration < min_duration * 1000:
            continue
        if track["completed"] > 0:
            continue
        if track.get("volume_up_max", 0) >= 3:
            continue
        if track["days"] < 2:
            continue
        reasons = [
            f"оценка {track['affinity']}",
            f"{track['skipped']} из {track['rated_plays']} — скип",
            f"средний прогресс {pct(track['completion'])}",
            "ни разу не дослушан",
        ]
        if track["volume_vs_trip"] is not None and track["volume_vs_trip"] < -0.5:
            reasons.append("громкость ниже обычной")
        candidates.append({**track, "reasons": reasons})
    candidates.sort(key=lambda item: (item["affinity"], -item["rated_plays"]))
    return candidates


# ---------- вывод ----------


def render_table(metrics: list[dict], limit: int) -> str:
    shown = metrics if limit <= 0 else metrics[:limit]
    header = (
        f"{'трек':44} {'оцен':>4} {'play':>4} {'prog':>5} {'heard':>5} "
        f"{'skip':>5} {'volΔ':>5} {'←/→':>5} {'last':>10}"
    )
    lines = [header, "-" * len(header)]
    for track in shown:
        title = track["title"]
        if track["artist"]:
            title = f"{title} — {track['artist']}"
        if len(title) > 44:
            title = title[:43] + "…"
        volume = "-" if track["volume_delta"] is None else f"{track['volume_delta']:+.0f}"
        score = "-" if track.get("affinity") is None else str(track["affinity"])
        last = date_of(track["last_played"]) if track["last_played"] else "-"
        lines.append(
            f"{title:44} {score:>4} {track['plays']:>4} {pct(track['completion']):>5} "
            f"{pct(track['listened']):>5} {pct(track['skip_rate']):>5} {volume:>5} "
            f"{track['seek_back']}/{track['seek_fwd']:<3} {last:>10}"
        )
    if limit > 0 and len(metrics) > limit:
        lines.append(f"… показано {limit} из {len(metrics)} треков (--all — все)")
    return "\n".join(lines)


def render_report(
    metrics, sessions, manifests, sources, event_count,
    malformed, problems, position_resets, args,
    source_stats=None, gap_stats=None, media_stats=None, clock_stats=None,
    undated_stats=None,
):
    total_played = sum(track["played_ms"] or 0 for track in metrics)
    closed = sum(track["closed_plays"] for track in metrics)
    rated = sum(track["rated_plays"] for track in metrics)
    completed = sum(track["completed"] for track in metrics)
    skipped = sum(track["skipped"] for track in metrics)
    interrupted = sum(track["interrupted"] for track in metrics)
    service_stopped = sum(track["service_stopped"] for track in metrics)
    session_lost = sum(track["session_lost"] for track in metrics)
    not_played = sum(track["not_played"] for track in metrics)
    stopped = sum(track["stopped"] for track in metrics)
    unknown = sum(track["unknown"] for track in metrics)
    source_stats = source_stats or {}
    gap_stats = gap_stats or {"count": 0, "last_ts": None, "reasons": {}}
    media_stats = media_stats or {
        "next": 0, "previous": 0, "play_pause": 0, "other": 0, "total": 0,
    }
    censored = sum(track["censored_ms"] for track in metrics)
    opens = sum(1 for track in metrics if track["open"])
    volume_known = sum(track["volume_known"] for track in metrics)
    volume_unknown = sum(track["volume_unknown"] for track in metrics)
    clock_stats = clock_stats or {"jumps": 0, "corrected": 0}
    undated_stats = undated_stats or {"events": 0, "starts": 0}
    first_ts = sessions[0]["start"] if sessions else None
    last_ts = sessions[-1]["end"] if sessions else None
    devices = sorted({(m.get("deviceId") or "?") for m in manifests if m})

    lines = ["CarMusicMeter — отчёт по прослушиваниям", ""]
    lines.append(f"Источники: {len(sources)}")
    for source in sources:
        lines.append(f"  - {source['path']}")
    if manifests:
        ids = ", ".join(device[:8] for device in devices)
        if args.no_verify:
            status = "не проверялся"
        else:
            status = "ок" if not problems else "есть проблемы"
        lines.append(f"Манифест: {status}, устройств: {len(devices)} ({ids})")
        if len(devices) > 1:
            lines.append("  внимание: выгрузки с разных устройств")
    else:
        lines.append("Манифест: нет (старая версия выгрузки)")
    if problems:
        lines.append("Проблемы целостности:")
        for problem in problems:
            lines.append(f"  ! {problem}")
    if malformed:
        lines.append(f"Битых строк: {malformed}")
    if position_resets:
        lines.append(f"Подозрительных позиций (сброс в ноль): {position_resets} — учтены через playedMs")
    if clock_stats.get("jumps"):
        corrected = clock_stats.get("corrected") or 0
        suffix = f" · событий с поправкой: {corrected}" if corrected else ""
        lines.append(f"Скачки часов магнитолы: {clock_stats['jumps']}{suffix}")
    volume_total = volume_known + volume_unknown
    if volume_total:
        share = 100 * volume_known / volume_total
        lines.append(
            f"Громкость MCU: известна у {volume_known} из {volume_total} закрытых ({share:.0f}%)"
            f" · без MCU-сегмента: {volume_unknown}"
        )

    lines.append("")
    if undated_stats.get("events"):
        lines.append(
            f"Дата не подтверждена: {undated_stats['events']} событий · "
            f"{undated_stats['starts']} стартов — в период и метрики не входят"
        )
    if first_ts and last_ts:
        lines.append(f"Период: {time_of(first_ts)} — {time_of(last_ts)}")
    lines.append(
        f"Событий: {event_count} · сессий: {len(sessions)} · "
        f"треков: {len(metrics)} · прослушано: {fmt_ms(total_played)}"
    )
    lines.append(
        f"Закрытых: {closed} · оценённых: {rated} · completed: {completed} · "
        f"skipped: {skipped} · interrupted: {interrupted} · открытых: {opens}"
    )
    lines.append(
        f"Технические окончания: service_stopped: {service_stopped} · "
        f"session_lost: {session_lost} · not_played: {not_played} · "
        f"stopped: {stopped} · unknown: {unknown}"
    )
    if censored:
        lines.append(f"Цензурировано (rawPlayedMs > playedMs): {fmt_ms(censored)}")
    restored = sum(track.get("restored", 0) for track in metrics)
    if restored:
        lines.append(
            f"Восстановленные закрытия: {restored} — не считаются отдельными прослушиваниями"
        )
    if media_stats.get("total"):
        lines.append(
            f"Кнопки: next {media_stats['next']} · previous {media_stats['previous']} · "
            f"play_pause {media_stats['play_pause']}"
        )
    if gap_stats.get("count"):
        last_gap = time_of(gap_stats["last_ts"]) if gap_stats.get("last_ts") else "-"
        reasons = ", ".join(
            f"{reason}: {count}"
            for reason, count in sorted(gap_stats.get("reasons", {}).items())
        )
        suffix = f" ({reasons})" if reasons else ""
        lines.append(f"Разрывы сбора: {gap_stats['count']} · последний: {last_gap}{suffix}")
    if source_stats:
        lines.append("По источникам:")
        for source, item in source_stats.items():
            lines.append(
                f"  {source}: стартов {item['starts']} · закрыто {item['closed']} · "
                f"оценено {item['rated']} · skipped {item['skipped']} · "
                f"unknown {item['unknown']} · цензурировано {item['censored']}"
            )
    lines.append("")
    lines.append(render_table(metrics, 0 if args.all else args.limit))
    lines.append("")

    candidates = delete_candidates(metrics, args.min_plays, args.min_duration)
    lines.append(
        f"Кандидаты на удаление (оценка v1 ≤30, ≥{args.min_plays} прослушиваний, "
        f"без дослушиваний и подъёмов громкости, длительность ≥{args.min_duration} с, ≥2 дней):"
    )
    if not candidates:
        lines.append("  нет — либо всё в порядке, либо данных ещё мало")
    else:
        for track in candidates:
            lines.append(f"  • {track['title']} — {track['artist'] or '?'}")
            for reason in track["reasons"]:
                lines.append(f"      {reason}")
    return "\n".join(lines)


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, OSError):
        pass

    args = parse_args()
    events, malformed, sources, manifests, problems, plays = load_exports(
        args.paths, verify=not args.no_verify,
    )
    if args.plays:
        write_plays_csv(args.plays, plays)
    exported_at = max((m.get("exportedAtMs") or 0) for m in manifests) if manifests else None
    jumps = clock_jumps(events)
    dated, undated = split_by_date(events, jumps, exported_at or None)
    undated_stats = undated_summary(undated)
    metrics, sessions, _medians, position_resets = analyze(
        dated, args.session_gap * 60 * 1000,
    )
    source_stats = source_summary(dated)
    gap_stats = capture_gap_summary(dated)
    media_stats = media_button_summary(dated)
    clock_stats = {
        "jumps": sum(1 for event in events if event.get("type") == "clock_changed"),
        "corrected": sum(1 for event in events if "_rawTs" in event),
    }

    if args.csv:
        fields = [
            "key", "package", "title", "artist", "plays", "closed_plays", "rated_plays", "open",
            "completed", "skipped", "interrupted", "service_stopped", "session_lost",
            "not_played", "stopped", "unknown", "restored", "restored_empty",
            "played_ms", "raw_played_ms", "censored_ms",
            "duration_ms", "completion", "listened", "skip_rate",
            "early_skip_rate", "early_skips", "avg_skip_pct", "volume_delta", "volume_vs_trip",
            "volume_known", "volume_unknown", "volume_changes", "volume_up", "volume_down",
            "seek_back", "seek_fwd", "days", "last_played",
            "affinity", "affinity_confidence", "affinity_plays", "affinity_approx",
            "affinity_reasons", "volume_up_max",
        ]
        with open(args.csv, "w", newline="", encoding="utf-8-sig") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
            writer.writeheader()
            for track in metrics:
                row = dict(track)
                row["affinity_reasons"] = "; ".join(track.get("affinity_reasons") or [])
                writer.writerow(row)

    payload = {
        "sources": sources,
        "sessions": [
            {"start": session["start"], "end": session["end"]}
            for session in sessions
        ],
        "manifest": {
            "present": bool(manifests),
            "devices": sorted({(m.get("deviceId") or "?") for m in manifests if m}),
            "problems": problems,
        },
        "summary": {
            "events": len(plays) if plays else len(events),
            "facts": len(plays),
            "tracks": len(metrics),
            "sessions": len(sessions),
            "played_ms": sum(track["played_ms"] or 0 for track in metrics),
            "rated_plays": sum(track["rated_plays"] for track in metrics),
            "completed": sum(track["completed"] for track in metrics),
            "skipped": sum(track["skipped"] for track in metrics),
            "interrupted": sum(track["interrupted"] for track in metrics),
            "service_stopped": sum(track["service_stopped"] for track in metrics),
            "session_lost": sum(track["session_lost"] for track in metrics),
            "not_played": sum(track["not_played"] for track in metrics),
            "stopped": sum(track["stopped"] for track in metrics),
            "unknown": sum(track["unknown"] for track in metrics),
            "open": sum(1 for track in metrics if track["open"]),
            "position_resets": position_resets,
            "capture_gaps": gap_stats["count"],
            "censored_ms": sum(track["censored_ms"] for track in metrics),
            "media_buttons": media_stats["total"],
            "volume_known": sum(track["volume_known"] for track in metrics),
            "volume_unknown": sum(track["volume_unknown"] for track in metrics),
            "clock_jumps": clock_stats["jumps"],
            "clock_corrected": clock_stats["corrected"],
            "undated_events": undated_stats["events"],
            "undated_starts": undated_stats["starts"],
        },
        "undated": undated_stats,
        "source_stats": source_stats,
        "capture_gaps": gap_stats,
        "media_buttons": media_stats,
        "tracks": metrics,
        "delete_candidates": delete_candidates(metrics, args.min_plays, args.min_duration),
    }
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    if args.json_out:
        Path(args.json_out).write_text(text, encoding="utf-8")
    if args.json:
        print(text)
    else:
        print(render_report(
            metrics, sessions, manifests, sources,
            len(plays) if plays else len(events), malformed,
            problems, position_resets, args, source_stats, gap_stats, media_stats,
            clock_stats, undated_stats,
        ))

    if problems and args.strict:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
