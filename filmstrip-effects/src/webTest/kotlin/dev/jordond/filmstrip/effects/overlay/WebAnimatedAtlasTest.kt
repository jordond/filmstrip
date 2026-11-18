package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.animated.ANIMATED_AVIF_REFUSED
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPeakBytes
import dev.jordond.filmstrip.picture.animated.animatedPeakRefusal
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.picture.animated.animatedSourceBytes
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedWebps
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * Animated pictures decoded into the one eight-bit atlas the browser holds, compared with the fixture painter.
 *
 * Every expected canvas is painted texel by texel from the operation list the file was written from, never read from
 * the file.
 */
class WebAnimatedAtlasTest {
  @Test
  fun everyContainerHoldsTheCanvasThePainterShowsOnEachFrame() =
    runTest {
      val ops = AnimatedPictures.SHARED
      val files =
        mapOf(
          "gif" to AnimatedGifs.bytes(AnimatedPictures.CANVAS, ops),
          "apng" to AnimatedApngs.bytes(AnimatedPictures.CANVAS, ops),
          "sixteen bit apng" to AnimatedApngs.bytes(AnimatedPictures.CANVAS, ops, sixteenBit = true),
          "webp" to AnimatedWebps.bytes(AnimatedPictures.CANVAS, ops),
        )
      for ((name, bytes) in files) {
        withClue(name) { assertCanvases(bytes, ops, tolerance = 0) }
      }
    }

  @Test
  fun aFrameWithAPreviousDisposalRestoresWhatItDrewOver() =
    runTest {
      val ops = AnimatedPictures.WITH_PREVIOUS
      assertCanvases(AnimatedGifs.bytes(AnimatedPictures.CANVAS, ops), ops, tolerance = 0)
      assertCanvases(AnimatedApngs.bytes(AnimatedPictures.CANVAS, ops), ops, tolerance = 0)
    }

  @Test
  fun aLossyWebpFrameHoldsTheTexelsLibwebpDecodesItTo() =
    runTest {
      val ops = AnimatedPictures.LOSSY_FRAMES
      assertCanvases(AnimatedWebps.bytes(AnimatedPictures.CANVAS, ops), ops, tolerance = 1)
    }

  @Test
  fun anApngDrawsItsFirstFrameAndNotItsDefaultImage() =
    runTest {
      val ops = AnimatedPictures.DEFAULT_IS_FRAME
      val bytes = AnimatedApngs.bytes(AnimatedPictures.CANVAS, ops, defaultIsFrame = false)

      assertCanvases(bytes, ops, tolerance = 0)
    }

  @Test
  fun anAnimatedAvifIsRefusedByName() =
    runTest {
      val refused = decodeOf(ANIMATED_AVIF, ceiling = DEFAULT_CEILING).shouldBeInstanceOf<OverlayDecode.Refused>()

      refused.message shouldBe ANIMATED_AVIF_REFUSED
    }

  // The count is the one core makes for a browser, which holds one four-byte atlas copy of every frame.
  @Test
  fun aPictureWhosePeakPassesTheCeilingIsRefusedWithCoresSentence() =
    runTest {
      val bytes = AnimatedGifs.bytes(AnimatedPictures.CANVAS, AnimatedPictures.SHARED)
      val picture = (animatedPictureOf(bytes) as AnimatedRead.Animated).picture
      val source = animatedSourceBytes(picture, bytes.size.toLong()).shouldNotBeNull()
      val peak =
        animatedPeakBytes(picture.canvas, picture.frames.size, AnimatedCopies.Browser, false, source).shouldNotBeNull()
      val expected =
        animatedPeakRefusal(
          picture,
          AnimatedCopies.Browser,
          peak - 1,
          bytes.size.toLong(),
        ).shouldNotBeNull()

      decodeOf(bytes, peak - 1).shouldBeInstanceOf<OverlayDecode.Refused>().message shouldBe expected
      decodeOf(bytes, peak).shouldBeInstanceOf<OverlayDecode.Animated>()
    }

  @Test
  fun aStillGifAndAStillWebpAreNotAnimated() =
    runTest {
      val one = listOf(AnimatedPictures.SHARED.first())
      val gif = AnimatedGifs.bytes(AnimatedPictures.CANVAS, one)

      decodeOf(gif, DEFAULT_CEILING).shouldBeInstanceOf<OverlayDecode.Decoded>()
    }

  private suspend fun assertCanvases(
    bytes: ByteArray,
    ops: List<AnimatedPictures.Op>,
    tolerance: Int,
  ) {
    val atlas = decodeOf(bytes, DEFAULT_CEILING).shouldBeInstanceOf<OverlayDecode.Animated>().atlas
    val expected = AnimatedPictures.canvases(AnimatedPictures.CANVAS, ops)
    atlas.grid.cells.size shouldBe expected.size
    val width = atlas.grid.size.width
    for ((index, canvas) in expected.withIndex()) {
      val cell = atlas.grid.cells[index]
      var worst = 0
      var at = ""
      for (y in 0 until AnimatedPictures.CANVAS.height) {
        for (x in 0 until AnimatedPictures.CANVAS.width) {
          val offset = ((cell.top + y) * width + cell.left + x) * 4
          val painted = canvas[y * AnimatedPictures.CANVAS.width + x]
          val read = intArrayOf(0, 8, 16, 24).indices.map { atlas.codes[offset + it].toInt() and 0xFF }
          val truth = listOf(painted shr 16, painted shr 8, painted, painted ushr 24).map { it and 0xFF }
          val off = read.indices.maxOf { abs(read[it] - truth[it]) }
          if (truth[3] == 0 && read[3] == 0) continue
          if (off > worst) {
            worst = off
            at = "$x,$y read $read painted $truth"
          }
        }
      }
      withClue("frame $index of ${expected.size}, worst at $at") { (worst <= tolerance) shouldBe true }
    }
  }

  private suspend fun decodeOf(
    bytes: ByteArray,
    ceiling: Long,
  ): OverlayDecode {
    val source = ImageSource.ofBytes(bytes)
    return OverlayImages(ceiling = ceiling).apply { preload(source) }[source].shouldNotBeNull()
  }

  private companion object {
    const val DEFAULT_CEILING = 1L shl 30

    // An ftyp box whose major brand is `avis`, which is how an AVIF image sequence names itself.
    val ANIMATED_AVIF =
      byteArrayOf(0, 0, 0, 24) + "ftypavis".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) +
        "avisavif".encodeToByteArray()
  }
}
