package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.sample.ui.asClock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Duration

/**
 * The shape of the composition rather than the composition itself: every track with where it starts and how it
 * loops and fades, and every clip on it with its cut, pace, fades, level and effects. Sources are named by
 * [redactedName], so no path reaches the report.
 */
internal fun Composition.asJson(): JsonObject =
  buildJsonObject {
    putJsonArray("tracks") { tracks.forEach { add(it.asJson()) } }
    putJsonArray("effects") { effects.forEach { add(it.id.value) } }
    putJsonArray("audioEffects") { audioEffects.forEach { add(it.id.value) } }
    put("durationMs", duration?.inWholeMilliseconds)
  }

/**
 * The id of every effect in the composition, whole-edit ones first, then each track's and each clip's in order.
 */
internal fun Composition.effectIds(): List<String> = allEffects().map { it.id.value }

/**
 * Every effect in the composition, whole-edit ones first, then each track's and each clip's in order.
 */
internal fun Composition.allEffects(): List<EffectSpec> =
  effects + tracks.flatMap { track -> track.effects + track.clips.flatMap { it.effects } }

/**
 * One line a track and one a clip, for the report a human reads. A field left at its default is left out.
 */
internal fun Composition.trackLines(): List<String> =
  tracks.flatMapIndexed { index, track ->
    val heading =
      listOfNotNull(
        "Track ${index + 1} (${track.content.name})",
        track.start.takeIf { it > Duration.ZERO }?.let { "from $it" },
        "looping".takeIf { track.looping },
        track.fadeIn.fade("in"),
        track.fadeOut.fade("out"),
        track.audio.summary(),
      ).joinToString(", ")

    listOf("* $heading") + track.clips.map { "    * ${it.summary()}" }
  }

private fun Track.asJson(): JsonObject =
  buildJsonObject {
    put("content", content.name)
    put("startMs", start.inWholeMilliseconds)
    put("looping", looping)
    put("fadeInMs", fadeIn.inWholeMilliseconds)
    put("fadeOutMs", fadeOut.inWholeMilliseconds)
    put("level", audio.asJson())
    put("durationMs", duration?.inWholeMilliseconds)
    putJsonArray("effects") { effects.forEach { add(it.id.value) } }
    putJsonArray("audioEffects") { audioEffects.forEach { add(it.id.value) } }
    putJsonArray("clips") { clips.forEach { add(it.asJson()) } }
  }

private fun Clip.asJson(): JsonObject =
  buildJsonObject {
    put("source", source.redactedName())
    put("trim", trim?.asJson() ?: JsonNull)
    put("snapWithinMs", snapWithin.inWholeMilliseconds)
    put("speed", speed)
    put("fadeInMs", fadeIn.inWholeMilliseconds)
    put("fadeOutMs", fadeOut.inWholeMilliseconds)
    put("level", audio.asJson())
    put("audioTrack", audioTrack?.value)
    put("durationMs", duration?.inWholeMilliseconds)
    putJsonArray("effects") { effects.forEach { add(it.id.value) } }
    putJsonArray("audioEffects") { audioEffects.forEach { add(it.id.value) } }
  }

private fun TimeRange.asJson(): JsonObject =
  buildJsonObject {
    put("startMs", start.inWholeMilliseconds)
    put("endMs", endExclusive?.inWholeMilliseconds)
  }

private fun AudioLevel.asJson(): JsonElement =
  buildJsonObject {
    when (val level = this@asJson) {
      AudioLevel.Inherit -> {
        put("kind", "Inherit")
      }
      AudioLevel.Mute -> {
        put("kind", "Mute")
      }
      is AudioLevel.Volume -> {
        put("kind", "Volume")
        put("gain", level.gain)
      }
      is AudioLevel.Envelope -> {
        put("kind", "Envelope")
        putJsonArray("points") {
          level.points.forEach { point ->
            add(
              buildJsonObject {
                put("atMs", point.at.inWholeMilliseconds)
                put("gain", point.gain)
                put("from", point.from.name)
              },
            )
          }
        }
      }
    }
  }

private fun Clip.summary(): String =
  listOfNotNull(
    source.redactedName(),
    trim?.summary(),
    speed.takeIf { it != 1.0 }?.let { "${it.toString().removeSuffix(".0")}x" },
    fadeIn.fade("in"),
    fadeOut.fade("out"),
    audio.summary(),
    effects.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "effects ") { it.id.value },
  ).joinToString(", ")

private fun TimeRange.summary(): String = "${start.asClock()} to ${endExclusive?.asClock() ?: "the end"}"

private fun Duration.fade(end: String): String? = takeIf { it > Duration.ZERO }?.let { "fade $end $it" }

private fun AudioLevel.summary(): String? =
  when (this) {
    AudioLevel.Inherit -> null
    AudioLevel.Mute -> "muted"
    is AudioLevel.Volume -> "volume $gain"
    is AudioLevel.Envelope -> "envelope of ${points.size} points"
  }
