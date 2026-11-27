package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.effects.filters.digicam.Digicam
import dev.jordond.filmstrip.effects.filters.digicam.DigicamParams
import dev.jordond.filmstrip.effects.filters.digicam.DigicamSignal
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan

// A sample rounds to its nearest code, half a code at most.
private const val ROUNDING = 0.5

// The noise map holds a move in this many steps either side of none, which is ffmpeg's eight bit plane.
private const val MAP_STEPS = 127.0

/**
 * How far a channel of a frame ffmpeg draws for [digicam] may sit from [DigicamSignal], in eight bit codes.
 *
 * The graph reads the picture and its blur as eight bit codes, which the halo and the clip gain scale up, and rounds
 * the frame once after them. The noise map holds a move in eight bits and is weighed by an eight bit luma, whose
 * weight changes by at most twice what the luma does, and the weighed move rounds once more.
 */
internal fun digicamCodes(digicam: Digicam): Double {
  val params = DigicamParams.of(digicam)
  val picture = ROUNDING * (1.0 + params.haloGain) * params.clipGain
  val blurred = ROUNDING * params.haloGain * params.clipGain
  val reach = DigicamSignal.noised(0.0, 0.0, 0.0, doubleArrayOf(1.0, 1.0), params).max() * FULL
  val noise = if (reach > 0.0) reach * ROUNDING / MAP_STEPS + reach * 2.0 * ROUNDING / FULL + ROUNDING else 0.0
  return picture + blurred + ROUNDING + noise
}

/**
 * Reads [frame] of [digicam] at [points] against [DigicamSignal.pixelAt] over [plain], a render of the same opaque
 * picture with no digicam on it, at the noise's [tick].
 */
internal fun digicamReads(
  frame: Yuv420Frame,
  plain: Yuv420Frame,
  digicam: Digicam,
  tick: Int,
  points: List<Pair<Int, Int>>,
  seed: Int = digicam.seed,
): List<FilmRead> {
  val params = DigicamParams.of(digicam)
  val blur = DigicamSignal.blur(frame.width, frame.height) { x, y -> plain.gbrapAt(x, y) }
  val codes = digicamCodes(digicam)
  return planeReads(
    frame,
    points,
    codes * VHS_LUMA_SPAN / FULL + ROUNDING,
    codes * VHS_CHROMA_SPAN / FULL + ROUNDING,
  ) { x, y ->
    planeCodesOf(DigicamSignal.pixelAt(x, y, frame.height, seed, tick, params, plain.gbrapAt(x, y), blur.at(x, y)))
  }
}

/**
 * Asserts every read [digicamReads] takes lands inside its limit.
 */
internal fun assertDigicamLanded(
  frame: Yuv420Frame,
  plain: Yuv420Frame,
  digicam: Digicam,
  tick: Int,
  points: List<Pair<Int, Int>>,
) {
  val reads = digicamReads(frame, plain, digicam, tick, points)
  val missed = reads.filter { it.miss >= it.limit }
  reads.forEach { read ->
    withClue("${missed.size} of ${reads.size} reads missed, ${read.label}") { read.miss shouldBeLessThan read.limit }
  }
}
