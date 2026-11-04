from __future__ import annotations

import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import report  # noqa: E402

BASE = 1_700_000_000_000


def start(
    ts: int,
    key: str,
    title: str = "Song",
    artist: str = "Artist",
    duration: int = 180_000,
    source: str = "media_session",
):
    return {
        "ts": ts,
        "type": "track_started",
        "trackKey": key,
        "package": "com.example.player",
        "title": title,
        "artist": artist,
        "durationMs": duration,
        "source": source,
        "volume": 10,
    }


def end(
    ts: int,
    key: str,
    played: int,
    end_pos: int,
    reason: str = "completed",
    title: str = "Song",
    artist: str = "Artist",
    duration: int | None = 180_000,
    source: str = "media_session",
    raw_played: int | None = None,
):
    return {
        "ts": ts,
        "type": "track_ended",
        "trackKey": key,
        "package": "com.example.player",
        "title": title,
        "artist": artist,
        "durationMs": duration,
        "playedMs": played,
        "rawPlayedMs": raw_played if raw_played is not None else played,
        "endPositionMs": end_pos,
        "endReason": reason,
        "source": source,
        "startedAt": ts - played,
        "volumeStart": 10,
        "volumeEnd": 12,
    }


class ReportTest(unittest.TestCase):

    def test_legacy_and_new_keys_merge_by_title_and_artist(self) -> None:
        events = [
            start(BASE, "com.example.player::id::song|artist::180000"),
            end(BASE + 180_000, "com.example.player::id::song|artist::180000", 180_000, 180_000),
            start(BASE + 200_000, "com.example.player::song::artist"),
            end(
                BASE + 300_000,
                "com.example.player::song::artist",
                100_000,
                100_000,
                reason="skipped",
            ),
        ]

        metrics, _, _, _ = report.analyze(events, 10 * 60 * 1000)

        self.assertEqual(1, len(metrics))
        self.assertEqual(2, metrics[0]["plays"])
        self.assertEqual(2, metrics[0]["closed_plays"])

    def test_late_artist_does_not_split_new_key(self) -> None:
        key = "com.example.player::song::"
        events = [
            start(BASE, key, artist=None),
            end(BASE + 60_000, key, 60_000, 60_000, artist="Artist"),
        ]

        metrics, _, _, _ = report.analyze(events, 10 * 60 * 1000)

        self.assertEqual(1, len(metrics))
        self.assertEqual(1, metrics[0]["closed_plays"])
        self.assertEqual("Artist", metrics[0]["artist"])

    def test_track_progress_recovers_open_play(self) -> None:
        key = "com.example.player::song::artist"
        events = [
            start(BASE, key),
            {
                "ts": BASE + 121_000,
                "type": "track_progress",
                "trackKey": key,
                "package": "com.example.player",
                "title": "Song",
                "artist": "Artist",
                "positionMs": 120_000,
                "playedMs": 120_000,
                "durationMs": 180_000,
                "volume": 12,
                "phase": "playing",
            },
        ]

        metrics, _, _, _ = report.analyze(events, 10 * 60 * 1000)

        self.assertEqual(1, len(metrics))
        self.assertTrue(metrics[0]["open"])
        self.assertEqual(120_000, metrics[0]["played_ms"])
        self.assertAlmostEqual(2 / 3, metrics[0]["listened"])
        self.assertIsNone(metrics[0]["completion"])

    def test_golden_zip_with_duplicate_malformed_and_reset(self) -> None:
        key_a = "player::a::artist"
        key_b = "player::b::artist"
        key_c = "player::c::artist"
        lines = [
            start(BASE, key_a, "A"),
            end(BASE + 180_000, key_a, 180_000, 180_000, title="A"),
            start(BASE + 200_000, key_b, "B"),
            end(BASE + 230_000, key_b, 30_000, 30_000, reason="skipped", title="B"),
            start(BASE + 300_000, key_c, "C", duration=400_000),
            end(BASE + 700_000, key_c, 350_000, 0, reason="stopped", title="C", duration=400_000),
        ]
        # duplicate of the first event and one broken line
        text = "\n".join(json.dumps(line, ensure_ascii=False) for line in lines)
        text += "\n" + json.dumps(lines[0], ensure_ascii=False)
        text += "\n{broken line\n"

        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "export.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("logs/events-2026-01-01.jsonl", text)
            events, malformed, _, problems = report.load_zip(path, verify=False)
            metrics, _, _, position_resets = report.analyze(events, 10 * 60 * 1000)

        self.assertEqual(1, malformed)
        self.assertEqual([], problems)
        self.assertEqual(6, len(events))
        self.assertEqual(3, len(metrics))
        self.assertEqual(1, position_resets)
        by_title = {item["title"]: item for item in metrics}
        self.assertEqual(1, by_title["A"]["completed"])
        self.assertEqual(1, by_title["B"]["skipped"])
        self.assertEqual(1, by_title["C"]["closed_plays"])
        self.assertEqual(1, by_title["C"]["stopped"])
        self.assertEqual(0, by_title["C"]["service_stopped"])
        self.assertEqual(0, by_title["C"]["rated_plays"])

    def test_technical_endings_do_not_enter_skip_rate_or_candidates(self) -> None:
        key = "com.example.player::song::artist"
        events = [start(BASE, key)]
        ts = BASE
        for _ in range(3):
            ts += 60_000
            events.append(end(ts, key, 30_000, 30_000, reason="service_stopped"))
        ts += 60_000
        events.append(end(ts, key, 30_000, 30_000, reason="skipped"))
        ts += 60_000
        events.append(end(ts, key, 1_000, 1_000, reason="not_played"))

        metrics, _, _, _ = report.analyze(events, 10 * 60 * 1000)
        track = metrics[0]

        self.assertEqual(1, track["rated_plays"])
        self.assertEqual(1, track["not_played"])
        self.assertEqual(1.0, track["skip_rate"])
        self.assertEqual([], report.delete_candidates(metrics, min_plays=3, min_duration=60))

    def test_unknown_is_censored_and_not_rated(self) -> None:
        key = "com.syu.music::song::artist"
        events = [
            start(BASE, key, duration=None),
            end(
                BASE + 60_000,
                key,
                60_000,
                0,
                reason="unknown",
                duration=None,
                source="accessibility",
            ),
        ]

        metrics, _, _, _ = report.analyze(events, 10 * 60 * 1000)
        track = metrics[0]

        self.assertEqual(0, track["rated_plays"])
        self.assertEqual(1, track["unknown"])
        self.assertIsNone(track["skip_rate"])
        self.assertIsNone(track["completion"])

    def test_fyt_service_media_buttons_and_censored_played(self) -> None:
        key = "com.syu.music::song::artist"
        events = [
            start(BASE, key, duration=185_000, source="fyt_service"),
            end(
                BASE + 185_000,
                key,
                185_000,
                185_000,
                reason="session_lost",
                duration=185_000,
                source="fyt_service",
                raw_played=205_669,
            ),
            {"ts": BASE + 1_000, "type": "media_button", "key": "media_next", "action": "down"},
            {"ts": BASE + 1_100, "type": "media_button", "key": "media_next", "action": "up"},
            {"ts": BASE + 2_000, "type": "media_button", "key": "media_previous", "action": "down"},
            {"ts": BASE + 3_000, "type": "media_button", "key": "media_play_pause", "action": "down"},
        ]

        metrics, _, _, _ = report.analyze(events, 10 * 60 * 1000)
        summary = report.source_summary(events)
        buttons = report.media_button_summary(events)

        self.assertEqual(1, summary["fyt_service"]["starts"])
        self.assertEqual(1, summary["fyt_service"]["censored"])
        self.assertEqual(1, buttons["next"])
        self.assertEqual(1, buttons["previous"])
        self.assertEqual(1, buttons["play_pause"])
        self.assertEqual(3, buttons["total"])
        self.assertEqual(185_000, metrics[0]["played_ms"])
        self.assertEqual(205_669, metrics[0]["raw_played_ms"])
        self.assertEqual(20_669, metrics[0]["censored_ms"])

    def test_capture_gap_no_source(self) -> None:
        events = [
            {
                "ts": BASE,
                "type": "capture_gap",
                "active": True,
                "reason": "no_source",
            },
        ]

        gaps = report.capture_gap_summary(events)

        self.assertEqual(1, gaps["count"])
        self.assertEqual({"no_source": 1}, gaps["reasons"])

    def test_source_summary_and_capture_gaps(self) -> None:
        key = "com.example.player::song::artist"
        events = [
            start(BASE, key),
            end(BASE + 60_000, key, 60_000, 60_000, source="media_session"),
            {
                "ts": BASE + 70_000,
                "type": "capture_gap",
                "active": True,
                "reason": "stock_window_unavailable",
            },
            {
                "ts": BASE + 80_000,
                "type": "capture_gap",
                "active": False,
                "reason": "stock_window_visible",
            },
        ]

        summary = report.source_summary(events)
        gaps = report.capture_gap_summary(events)

        self.assertEqual(1, summary["media_session"]["starts"])
        self.assertEqual(1, summary["media_session"]["rated"])
        self.assertEqual(1, gaps["count"])
        self.assertEqual(BASE + 70_000, gaps["last_ts"])

    def test_volume_delta_uses_mcu_segment(self) -> None:
        key = "com.syu.music::song::artist"
        ended = end(BASE + 60_000, key, 60_000, 60_000, reason="skipped")
        # Верхнеуровневые поля — последний сегмент (переключились на audio),
        # реальный подъём громкости виден только в сегменте MCU.
        ended["volumeStart"] = 25
        ended["volumeEnd"] = 25
        ended["volumeStats"] = {
            "audio": {"start": 25, "low": 25, "high": 25, "end": 25, "max": 25, "changes": 0},
            "mcu": {"start": 4, "low": 4, "high": 14, "end": 14, "max": 43, "changes": 9},
        }
        events = [start(BASE, key, source="fyt_service"), ended]

        metrics, _, _, _ = report.analyze(events, 10 * 60 * 1000)
        track = metrics[0]

        self.assertEqual(10, track["volume_delta"])
        self.assertEqual(1, track["volume_known"])
        self.assertEqual(0, track["volume_unknown"])

    def test_volume_delta_unknown_without_mcu(self) -> None:
        key = "com.syu.music::song::artist"
        events = [
            start(BASE, key, source="fyt_service"),
            end(BASE + 60_000, key, 60_000, 60_000, source="fyt_service"),
        ]

        metrics, _, _, _ = report.analyze(events, 10 * 60 * 1000)
        track = metrics[0]

        self.assertIsNone(track["volume_delta"])
        self.assertEqual(0, track["volume_known"])
        self.assertEqual(1, track["volume_unknown"])

    def test_clock_jump_restores_real_date_and_order(self) -> None:
        boot = "abcd1234"
        old = 1_683_706_000_000
        new = 1_793_800_000_000
        delta = new - old
        key = "com.syu.music::song::artist"
        events = [
            {
                "ts": old,
                "type": "track_started",
                "bootId": boot,
                "monoMs": 100,
                "trackKey": key,
                "package": "com.syu.music",
                "title": "Song",
                "artist": "Artist",
                "durationMs": 180_000,
            },
            {
                "ts": old + 60_000,
                "type": "track_ended",
                "bootId": boot,
                "monoMs": 60_100,
                "trackKey": key,
                "package": "com.syu.music",
                "title": "Song",
                "artist": "Artist",
                "durationMs": 180_000,
                "playedMs": 60_000,
                "rawPlayedMs": 60_000,
                "endPositionMs": 60_000,
                "endReason": "skipped",
            },
            {
                "ts": new,
                "type": "clock_changed",
                "bootId": boot,
                "monoMs": 120_000,
                "wallDelta": delta,
                "monoDelta": 505,
                "driftMs": delta,
            },
        ]

        ordered = report.order_events(events)

        self.assertEqual(
            ["track_started", "track_ended", "clock_changed"],
            [event["type"] for event in ordered],
        )
        self.assertEqual(new, ordered[0]["ts"])
        self.assertEqual(old, ordered[0]["_rawTs"])
        self.assertEqual(new, ordered[2]["ts"])
        self.assertNotIn("_rawTs", ordered[2])

        metrics, _, _, _ = report.analyze(ordered, 10 * 60 * 1000)
        self.assertEqual(1, len(metrics))
        self.assertEqual(new, metrics[0]["last_played"])
        self.assertEqual(1, metrics[0]["closed_plays"])

    def test_old_zip_without_envelope_keeps_ts(self) -> None:
        key = "com.example.player::song::artist"
        events = [start(BASE, key), end(BASE + 60_000, key, 60_000, 60_000)]

        ordered = report.order_events(events)

        self.assertEqual(BASE, ordered[0]["ts"])
        self.assertEqual(BASE + 60_000, ordered[1]["ts"])
        self.assertNotIn("_rawTs", ordered[0])


if __name__ == "__main__":
    unittest.main()
