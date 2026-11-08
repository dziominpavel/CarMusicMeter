package com.example.carmusicmeter.data

import org.json.JSONObject

fun eventLine(
    ts: Long,
    mono: Long,
    type: String,
    bootId: String = "b1",
    verified: Boolean = true,
    fields: Map<String, Any?> = emptyMap(),
): String = JSONObject().apply {
    put("ts", ts)
    put("type", type)
    put("bootId", bootId)
    put("monoMs", mono)
    put("clockVerified", verified)
    fields.forEach { (key, value) -> put(key, value) }
}.toString()

fun startFields(trackKey: String = "k", playId: String = "p1"): Map<String, Any?> =
    linkedMapOf(
        "trackKey" to trackKey,
        "playId" to playId,
        "package" to "com.p",
        "source" to "media_session",
        "title" to "Track",
        "artist" to "Artist",
        "durationMs" to 180_000L,
    )

fun endFields(
    trackKey: String = "k",
    playId: String = "p1",
    startedAt: Long = 1_000L,
    playedMs: Long = 900L,
    endReason: String = "skipped",
): Map<String, Any?> = linkedMapOf(
    "trackKey" to trackKey,
    "playId" to playId,
    "package" to "com.p",
    "source" to "media_session",
    "title" to "Track",
    "artist" to "Artist",
    "durationMs" to 180_000L,
    "playedMs" to playedMs,
    "rawPlayedMs" to playedMs,
    "endPositionMs" to playedMs,
    "endReason" to endReason,
    "completed" to (endReason == "completed"),
    "startedAt" to startedAt,
    "volumeStart" to 10,
    "volumeEnd" to 12,
    "volumeLow" to 10,
    "volumeHigh" to 12,
    "volumeChanges" to 1,
    "volumeMax" to 43,
    "volumeSource" to "mcu",
    "volumeStats" to mapOf(
        "mcu" to mapOf(
            "start" to 10,
            "low" to 10,
            "high" to 12,
            "end" to 12,
            "max" to 43,
            "changes" to 1,
        ),
    ),
)
