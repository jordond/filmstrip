package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedWebps
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class AnimatedPeakBytesTest {
  private val canvas = Size(480, 270)
  private val texels = 480L * 270
  private val band = texels * 16

  private val backends =
    mapOf(
      "media3" to (AnimatedCopies.Media3 to 100 * texels * 4),
      "apple" to (AnimatedCopies.Apple to 100 * texels * 4 + 2 * texels * 16),
      "browser" to (AnimatedCopies.Browser to 100 * texels * 4),
      "ffmpeg" to (AnimatedCopies.Ffmpeg to 100 * texels * 12),
      "ffmpeg on a tagged file" to (AnimatedCopies.FfmpegTagged to 100 * texels * 16),
      "ffmpeg on a kept grade" to (AnimatedCopies.FfmpegHdr to 100 * texels * 28),
    )

  @Test
  fun `a hundred frames of 480 by 270 hold what the table says for each backend`() {
    for ((_, held) in backends) {
      animatedPeakBytes(canvas, 100, held.first) shouldBe held.second + band
    }
    animatedPeakBytes(canvas, 100, AnimatedCopies.Media3) shouldBe 51_840_000L + band
    animatedPeakBytes(canvas, 100, AnimatedCopies.Apple) shouldBe 51_840_000L + 4_147_200L + band
  }

  @Test
  fun `the count grows with the frames and not only at the ends`() {
    for ((_, held) in backends) {
      val one = animatedPeakBytes(canvas, 1, held.first)!! - band
      for (frames in listOf(2, 37, 50, 99, 100, 101, 400)) {
        val cell = held.first.cellBytes * texels
        animatedPeakBytes(canvas, frames, held.first)!! - band - cell shouldBe (one - cell) * frames
      }
    }
  }

  @Test
  fun `ffmpeg counts what it holds at each depth and grade and in the middle of a range too`() {
    for (frames in listOf(2, 37, 61, 99)) {
      val n = frames * texels
      animatedPeakBytes(canvas, frames, AnimatedCopies.Ffmpeg) shouldBe n * (4 + 2 * 4) + band
      animatedPeakBytes(canvas, frames, AnimatedCopies.FfmpegTagged) shouldBe n * (4 + 4 + 2 * 4) + band
      animatedPeakBytes(canvas, frames, AnimatedCopies.FfmpegTagged, sixteenBit = true) shouldBe n * (8 + 2 * 8) + band
      animatedPeakBytes(canvas, frames, AnimatedCopies.Ffmpeg, sixteenBit = true) shouldBe n * (8 + 2 * 8) + band
      animatedPeakBytes(canvas, frames, AnimatedCopies.FfmpegHdr) shouldBe n * (4 + 8 + 2 * 8) + band
      animatedPeakBytes(canvas, frames, AnimatedCopies.FfmpegHdr, sixteenBit = true) shouldBe n * (8 + 8 + 2 * 8) + band
    }
  }

  @Test
  fun `a sixteen bit file doubles the frames and leaves the cells and the canvases`() {
    val deep = animatedPeakBytes(canvas, 100, AnimatedCopies.Apple, sixteenBit = true)
    deep shouldBe 2 * 100 * texels * 4 + 2 * texels * 16 + band
    animatedPeakBytes(canvas, 100, AnimatedCopies.Media3, sixteenBit = true) shouldBe 2 * 100 * texels * 4 + band
  }

  @Test
  fun `a ceiling at half the peak admits the picture`() {
    for ((_, held) in backends) {
      val peak = animatedPeakBytes(canvas, 100, held.first)!!
      animatedPeakRefusal(canvas, 100, held.first, ceiling = peak * 2).shouldBeNull()
    }
  }

  @Test
  fun `a ceiling at the peak admits the picture and one under it refuses`() {
    for ((_, held) in backends) {
      val peak = animatedPeakBytes(canvas, 100, held.first)!!
      animatedPeakRefusal(canvas, 100, held.first, ceiling = peak).shouldBeNull()
      animatedPeakRefusal(canvas, 100, held.first, ceiling = peak - 1) shouldBe animatedPictureTooLarge(peak, peak - 1)
    }
  }

  @Test
  fun `the frame that tips a picture over the ceiling is the one that is refused`() {
    for ((_, held) in backends) {
      val ceiling = animatedPeakBytes(canvas, 60, held.first)!!
      animatedPeakRefusal(canvas, 59, held.first, ceiling = ceiling).shouldBeNull()
      animatedPeakRefusal(canvas, 60, held.first, ceiling = ceiling).shouldBeNull()
      animatedPeakRefusal(canvas, 61, held.first, ceiling = ceiling) shouldBe
        animatedPictureTooLarge(animatedPeakBytes(canvas, 61, held.first)!!, ceiling)
    }
  }

  @Test
  fun `a count past a long is null and refused as the largest long`() {
    val huge = Size(Int.MAX_VALUE, Int.MAX_VALUE)
    animatedPeakBytes(huge, Int.MAX_VALUE, AnimatedCopies.Ffmpeg).shouldBeNull()
    animatedPeakRefusal(huge, Int.MAX_VALUE, AnimatedCopies.Ffmpeg) shouldBe
      animatedPictureTooLarge(Long.MAX_VALUE, PICTURE_PEAK_CEILING)
  }

  @Test
  fun `a hundred full frames of source add eight bytes a texel each beside the table's copy`() {
    val source = 100 * texels * 8
    animatedPeakBytes(canvas, 100, AnimatedCopies.Media3, source = source) shouldBe 51_840_000L + source + band
    animatedPeakBytes(canvas, 100, AnimatedCopies.Apple, source = source) shouldBe
      51_840_000L + 4_147_200L + source + band
    for ((_, held) in backends) {
      animatedPeakBytes(canvas, 100, held.first, source = source) shouldBe held.second + source + band
    }
  }

  @Test
  fun `a sixteen bit file doubles the frame copies and not the canvases`() {
    val shallow = animatedPeakBytes(canvas, 100, AnimatedCopies.Media3)!!
    val deep = animatedPeakBytes(canvas, 100, AnimatedCopies.Media3, sixteenBit = true)!!

    deep - shallow shouldBe 100 * texels * 4
    FrameSamples(2, 2, ShortArray(16), sixteenBit = true).rgba.size * 2L shouldBe 2 * 2 * 8
  }

  @Test
  fun `source bytes of decoded frames are the samples of each region`() {
    val ops = AnimatedPictures.counterOps()
    val picture = animated(AnimatedApngs.bytes(AnimatedPictures.COUNTER_CANVAS, ops))

    animatedSourceBytes(picture) shouldBe ops.sumOf { it.tile.width * it.tile.height * 8L }
  }

  @Test
  fun `the encoded file is held beside the decoded frames whatever its size`() {
    val ops = AnimatedPictures.counterOps()
    val picture = animated(AnimatedApngs.bytes(AnimatedPictures.COUNTER_CANVAS, ops))
    val frames = ops.sumOf { it.tile.width * it.tile.height * 8L }

    for (encoded in listOf(0L, 1_000L, 777_777L)) {
      animatedSourceBytes(picture, encoded) shouldBe frames + encoded
    }
  }

  @Test
  fun `source bytes of gif frames are four bytes a texel of each region`() {
    val ops = AnimatedPictures.counterOps()
    val picture = animated(AnimatedGifs.bytes(AnimatedPictures.COUNTER_CANVAS, ops))

    animatedSourceBytes(picture) shouldBe ops.sumOf { it.tile.width * it.tile.height * 4L }
  }

  @Test
  fun `a gif the reader and the frames alone admit is refused once its source counts`() {
    val size = AnimatedPictures.COUNTER_CANVAS
    val ops = AnimatedPictures.counterOps()
    val bytes = AnimatedGifs.bytes(size, ops)
    val picture = animated(bytes)
    val source = ops.size * 32L * 16 * 4
    val frames = animatedPeakBytes(size, ops.size, AnimatedCopies.Media3)!!

    animatedPictureOf(bytes, frames).shouldBeInstanceOf<AnimatedRead.Animated>()
    animatedPeakRefusal(size, ops.size, AnimatedCopies.Media3, ceiling = frames).shouldBeNull()
    animatedPeakRefusal(picture, AnimatedCopies.Media3, frames) shouldBe
      animatedPictureTooLarge(frames + source, frames)
    animatedPeakRefusal(picture, AnimatedCopies.Media3, frames + source).shouldBeNull()
  }

  @Test
  fun `source bytes of webp frames are the stills and one decoded frame`() {
    val ops = AnimatedPictures.counterOps()
    val picture = animated(AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, ops))
    val stills = ops.sumOf { AnimatedWebps.still(AnimatedWebps.chunksOf(it), it.tile.width, it.tile.height).size }

    animatedSourceBytes(picture) shouldBe stills + 32L * 16 * 8
  }

  @Test
  fun `a picture the reader and the frames alone admit is refused once its source counts`() {
    val size = AnimatedPictures.COUNTER_CANVAS
    val ops = AnimatedPictures.counterOps()
    val bytes = AnimatedApngs.bytes(size, ops)
    val source = animatedSourceBytes(animated(bytes))!!
    val frames = animatedPeakBytes(size, ops.size, AnimatedCopies.Media3)!!

    animatedPictureOf(bytes, frames).shouldBeInstanceOf<AnimatedRead.Animated>()
    animatedPeakRefusal(size, ops.size, AnimatedCopies.Media3, ceiling = frames).shouldBeNull()
    val picture = animated(bytes)
    animatedPeakRefusal(picture, AnimatedCopies.Media3, frames) shouldBe
      animatedPictureTooLarge(frames + source, frames)
    animatedPeakRefusal(picture, AnimatedCopies.Media3, frames + source / 2) shouldBe
      animatedPictureTooLarge(frames + source, frames + source / 2)
    animatedPeakRefusal(picture, AnimatedCopies.Media3, frames + source).shouldBeNull()
    animatedPeakRefusal(picture, AnimatedCopies.Media3, frames + source - 1) shouldBe
      animatedPictureTooLarge(frames + source, frames + source - 1)
  }

  @Test
  fun `a sixteen bit picture is counted at its depth with its source`() {
    val size = AnimatedPictures.COUNTER_CANVAS
    val ops = AnimatedPictures.counterOps()
    val picture = animated(AnimatedApngs.bytes(size, ops, sixteenBit = true))
    val peak =
      animatedPeakBytes(
        size,
        ops.size,
        AnimatedCopies.Browser,
        sixteenBit = true,
        animatedSourceBytes(picture)!!,
      )!!

    animatedPeakRefusal(picture, AnimatedCopies.Browser, peak).shouldBeNull()
    animatedPeakRefusal(picture, AnimatedCopies.Browser, peak - 1) shouldBe animatedPictureTooLarge(peak, peak - 1)
    peak - animatedPeakBytes(size, ops.size, AnimatedCopies.Browser, source = animatedSourceBytes(picture)!!)!! shouldBe
      ops.size * 96L * 96 * 4
  }

  private fun animated(bytes: ByteArray): AnimatedPicture =
    animatedPictureOf(bytes).shouldBeInstanceOf<AnimatedRead.Animated>().picture
}
