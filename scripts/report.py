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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Отчёт по прослушиваниям CarMusicMeter")
    parser.add_argument("paths", nargs="+", help="ZIP-экспорты или папки с logs/")
    parser.add_argument("--json", action="store_true", help="вывести JSON вместо таблицы")
    parser.add_argument("--json-out", metavar="FILE", help="сохранить JSON в файл (UTF-8)")
    parser.add_argument("--csv", metavar="FILE", help="сохранить метрики в CSV")
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
    events = {}
    malformed = 0
    with zipfile.ZipFile(path) as archive:
        manifest = None
        if "manifest.json" in archive.namelist():
            try:
                manifest = json.loads(archive.read("manifest.json"))
            except ValueError:
                manifest = None

        for name in sorted(archive.namelist()):
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
    return list(events.values()), malformed, manifest, problems


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
    events = {}
    malformed = 0
    manifest = read_json_file(path / "manifest.json")
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
    return list(events.values()), malformed, manifest, problems


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
    malformed = 0
    sources = []
    manifests = []
    problems = []
    for raw_path in paths:
        path = Path(raw_path)
        if path.is_dir():
            found, bad, manifest, issues = load_dir(path, verify)
        elif path.suffix.lower() == ".zip":
            found, bad, manifest, issues = load_zip(path, verify)
        else:
            raise SystemExit(f"Не ZIP и не папка: {raw_path}")
        malformed += bad
        problems.extend(f"{path.name}: {issue}" for issue in issues)
        if manifest:
            manifests.append(manifest)
        sources.append({
            "path": str(path),
            "events": len(found),
            "manifest": manifest is not None,
        })
        for event in found:
            key = json.dumps(event, sort_keys=True, ensure_ascii=False)
            events.setdefault(key, event)

    ordered = sorted(events.values(), key=lambda item: item.get("ts", 0))
    return ordered, malformed, sources, manifests, problems


# ---------- анализ ----------


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
                "source": event.get("source") or start.get("source") or "media_session",
                "vol_start": event.get("volumeStart", start.get("volume")),
                "vol_end": event.get("volumeEnd", last_volume),
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
        if open_play and open_play.get("duration") and open_play["played"]:
            listened_values.append(min(open_play["played"] / open_play["duration"], 1.0))
        for play in closed:
            duration = play.get("duration")
            if duration and duration > 0:
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
            if play.get("vol_start") is not None and play.get("vol_end") is not None:
                volume_deltas.append(play["vol_end"] - play["vol_start"])
                median = medians[play.get("session", 0)] if medians else None
                if median is not None:
                    volume_vs_trip.append(play["vol_end"] - median)

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
        if open_play:
            played_total += open_play["played"]
            raw_played_total += open_play.get("raw_played", open_play["played"])
        plays_total = len(track["starts"])
        closed_total = len(closed)
        durations = [play["duration"] for play in closed if play.get("duration")]
        if open_play and open_play.get("duration"):
            durations.append(open_play["duration"])
        track_duration = statistics.median(durations) if durations else None

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
            "volume_changes": track["vol_changes"],
            "volume_up": track["vol_up"],
            "volume_down": track["vol_down"],
            "seek_back": track["seek_back"],
            "seek_fwd": track["seek_fwd"],
            "days": len({date_of(start["ts"]) for start in track["starts"]}),
            "last_played": max((start["ts"] for start in track["starts"]), default=None),
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
    candidates = []
    for track in metrics:
        if track["rated_plays"] < min_plays:
            continue
        duration = track.get("duration_ms")
        if duration is not None and duration < min_duration * 1000:
            continue
        if track["skip_rate"] is None or track["skip_rate"] < 0.6:
            continue
        if track["completion"] is None or track["completion"] > 0.4:
            continue
        if track["completed"] > 0:
            continue
        reasons = [
            f"{track['skipped']} из {track['rated_plays']} — скип",
            f"средний прогресс {pct(track['completion'])}",
            "ни разу не дослушан",
        ]
        if track["volume_vs_trip"] is not None and track["volume_vs_trip"] < -0.5:
            reasons.append("громкость ниже обычной")
        candidates.append({**track, "reasons": reasons})
    candidates.sort(key=lambda item: (-(item["skip_rate"] or 0), -item["rated_plays"]))
    return candidates


# ---------- вывод ----------


def render_table(metrics: list[dict], limit: int) -> str:
    shown = metrics if limit <= 0 else metrics[:limit]
    header = (
        f"{'трек':44} {'play':>4} {'prog':>5} {'heard':>5} "
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
        last = date_of(track["last_played"]) if track["last_played"] else "-"
        lines.append(
            f"{title:44} {track['plays']:>4} {pct(track['completion']):>5} "
            f"{pct(track['listened']):>5} {pct(track['skip_rate']):>5} {volume:>5} "
            f"{track['seek_back']}/{track['seek_fwd']:<3} {last:>10}"
        )
    if limit > 0 and len(metrics) > limit:
        lines.append(f"… показано {limit} из {len(metrics)} треков (--all — все)")
    return "\n".join(lines)


def render_report(
    metrics, sessions, manifests, sources, event_count,
    malformed, problems, position_resets, args,
    source_stats=None, gap_stats=None, media_stats=None,
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

    lines.append("")
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
        f"Кандидаты на удаление (≥{args.min_plays} прослушиваний, скип ≥60%, "
        f"завершение ≤40%, длительность ≥{args.min_duration} с):"
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
    events, malformed, sources, manifests, problems = load_exports(
        args.paths, verify=not args.no_verify,
    )
    metrics, sessions, _medians, position_resets = analyze(
        events, args.session_gap * 60 * 1000,
    )
    source_stats = source_summary(events)
    gap_stats = capture_gap_summary(events)
    media_stats = media_button_summary(events)

    if args.csv:
        fields = [
            "key", "package", "title", "artist", "plays", "closed_plays", "rated_plays", "open",
            "completed", "skipped", "interrupted", "service_stopped", "session_lost",
            "not_played", "stopped", "unknown", "played_ms", "raw_played_ms", "censored_ms",
            "duration_ms", "completion", "listened", "skip_rate",
            "early_skip_rate", "early_skips", "avg_skip_pct", "volume_delta", "volume_vs_trip",
            "volume_changes", "volume_up", "volume_down", "seek_back", "seek_fwd", "days",
            "last_played",
        ]
        with open(args.csv, "w", newline="", encoding="utf-8-sig") as handle:
            writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
            writer.writeheader()
            for track in metrics:
                writer.writerow(track)

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
            "events": len(events),
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
        },
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
            metrics, sessions, manifests, sources, len(events), malformed,
            problems, position_resets, args, source_stats, gap_stats, media_stats,
        ))

    if problems and args.strict:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
