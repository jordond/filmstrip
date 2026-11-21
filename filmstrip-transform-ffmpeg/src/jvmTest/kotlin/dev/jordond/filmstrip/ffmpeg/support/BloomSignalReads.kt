package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.effects.filters.bloom.Bloom
import dev.jordond.filmstrip.effects.filters.bloom.BloomParams
import dev.jordond.filmstrip.effects.filters.bloom.BloomSignal
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan

// A bloom's limits against the signal, in the planes the frame holds. The trip through gbrap rounds the frame twice
// and the response reads codes already rounded once, which a patch half way up the knee shows most. The most measured
// on ffmpeg 8.0.1 is 1.12, at three quarters intensity and a full spread.
internal const val BLOOM_LUMA_CODES = 1.5
internal const val BLOOM_CHROMA_CODES = 1.5

/**
 * Reads [frame] of [bloom] against [BloomSignal] over [plain], a render of the same opaque clip with no bloom on it.
 *
 * The glow's source and the pixel under it both come from [plain]. Each of [extra] is read as well.
 */
internal fun bloomReads(
  frame: Yuv420Frame,
  plain: Yuv420Frame,
  bloom: Bloom,
  extra: List<Pair<Int, Int>> = emptyList(),
): List<FilmRead> {
  val params = BloomParams.of(bloom)
  val glow = BloomSignal.glow(frame.width, frame.height, params) { x, y -> plain.rgbAt(x, y) }
  return planeReads(
    frame,
    readPointsOf(frame.width, frame.height) + extra,
    BLOOM_LUMA_CODES,
    BLOOM_CHROMA_CODES,
  ) { x, y ->
    val (red, green, blue) = plain.rgbAt(x, y)
    planeCodesOf(BloomSignal.bloomed(red, green, blue, glow.at(x, y), params))
  }
}

/**
 * Asserts every read [bloomReads] takes lands inside its limit.
 */
internal fun assertBloomLanded(
  frame: Yuv420Frame,
  plain: Yuv420Frame,
  bloom: Bloom,
  extra: List<Pair<Int, Int>> = emptyList(),
) = bloomReads(frame, plain, bloom, extra).forEach { read ->
  withClue(read.label) { read.miss shouldBeLessThan read.limit }
}
