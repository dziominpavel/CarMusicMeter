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


def start(ts: int, key: str, title: str = "Song", artist: str = "Artist", duration: int = 180_000):
    return {
        "ts": ts,
        "type": "track_started",
        "trackKey": key,
        "package": "com.example.player",
        "title": title,
        "artist": artist,
        "durationMs": duration,
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
    duration: int = 180_000,
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
        "endPositionMs": end_pos,
        "endReason": reason,
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


if __name__ == "__main__":
    unittest.main()
