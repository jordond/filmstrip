package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.effects.filters.bloom.Bloom
import dev.jordond.filmstrip.effects.filters.bloom.BloomParams
import dev.jordond.filmstrip.effects.filters.bloom.BloomSignal
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan

// A bloom's limits against the signal, in the planes the frame holds. The signal reads the gbrap codes the bloom is
// handed, so what is left is the frame rounding to a code twice on its way back to the planes, at most 0.93 of a code.
// The most measured is 0.89 on ffmpeg 8.0.1 x86_64 and 0.77 on 9.0.2 arm64.
internal const val BLOOM_LUMA_CODES = 1.0
internal const val BLOOM_CHROMA_CODES = 1.0

/**
 * Reads [frame] of [bloom] against [BloomSignal] over [plain], a render of the same opaque clip with no bloom on it.
 *
 * The glow's source and the pixel under it both come from [plain], read as the 8 bit codes ffmpeg converts it to
 * before the bloom. Each of [extra] is read as well.
 */
internal fun bloomReads(
  frame: Yuv420Frame,
  plain: Yuv420Frame,
  bloom: Bloom,
  extra: List<Pair<Int, Int>> = emptyList(),
): List<FilmRead> {
  val params = BloomParams.of(bloom)
  val glow = BloomSignal.glow(frame.width, frame.height, params) { x, y -> plain.gbrapAt(x, y) }
  return planeReads(
    frame,
    readPointsOf(frame.width, frame.height) + extra,
    BLOOM_LUMA_CODES,
    BLOOM_CHROMA_CODES,
  ) { x, y ->
    val (red, green, blue) = plain.gbrapAt(x, y)
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
