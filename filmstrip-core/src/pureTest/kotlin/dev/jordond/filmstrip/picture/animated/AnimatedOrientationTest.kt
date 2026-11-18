package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedWebps
import dev.jordond.filmstrip.test.OrientedAnimations
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class AnimatedOrientationTest {
  private val canvas = OrientedAnimations.CANVAS
  private val truth = AnimatedPictures.canvases(canvas, OrientedAnimations.OPS)

  @Test
  fun `every frame of an apng is handed on turned the way its tag says`() {
    for (exif in 1..8) {
      val picture = animated(OrientedAnimations.apng(exif))
      val orientation = PictureOrientation.ofExif(exif)
      picture.orientation shouldBe orientation
      picture.shown shouldBe orientation.orientedSize(canvas)

      var seen = 0
      compositeFrames(picture, { null }) { index, frame ->
        withClue("exif $exif frame $index") {
          Size(frame.width, frame.height) shouldBe picture.shown
          frame.rgba.toList() shouldBe widened(orientedSamples(orientation, truth[index], canvas)).toList()
        }
        seen++
      } shouldBe CompositeResult.Done
      seen shouldBe truth.size
    }
  }

  @Test
  fun `a quarter turn swaps the sides of what is shown and a half turn keeps them`() {
    animated(OrientedAnimations.apng(6)).shown shouldBe Size(canvas.height, canvas.width)
    animated(OrientedAnimations.apng(8)).shown shouldBe Size(canvas.height, canvas.width)
    animated(OrientedAnimations.apng(3)).shown shouldBe canvas
  }

  @Test
  fun `an untagged picture and a gif are shown as stored`() {
    animated(AnimatedGifs.bytes(canvas, OrientedAnimations.OPS)).orientation shouldBe PictureOrientation.Normal
    animated(OrientedAnimations.apng(1)).orientation shouldBe PictureOrientation.Normal
  }

  @Test
  fun `every frame of a tagged webp is handed on turned the way its tag says`() {
    for (exif in 1..8) {
      val picture = animated(OrientedAnimations.webp(exif))
      val orientation = PictureOrientation.ofExif(exif)
      picture.orientation shouldBe orientation
      picture.shown shouldBe orientation.orientedSize(canvas)

      var seen = 0
      compositeFrames(picture, knownStills()) { index, frame ->
        withClue("webp exif $exif frame $index") {
          Size(frame.width, frame.height) shouldBe picture.shown
          frame.rgba.toList() shouldBe widened(orientedSamples(orientation, truth[index], canvas)).toList()
        }
        seen++
      } shouldBe CompositeResult.Done
      seen shouldBe truth.size
    }
  }

  @Test
  fun `a turned picture holds one more canvas than an upright one at every frame count`() {
    val texels = canvas.width.toLong() * canvas.height
    for (frames in listOf(2, 7, 30, 61, 100)) {
      val upright = animatedPeakBytes(canvas, frames, AnimatedCopies.Media3)!!
      val turned = animatedPeakBytes(canvas, frames, AnimatedCopies.Media3, turned = true)!!
      withClue("$frames frames") { turned - upright shouldBe texels * 8 }
    }
  }

  @Test
  fun `the peak count of a picture reads its turn from the picture`() {
    val upright = animated(OrientedAnimations.apng(1))
    val turned = animated(OrientedAnimations.apng(6))
    val ceiling = animatedPeakBytes(canvas, upright.frames.size, AnimatedCopies.Media3, source = source(upright))!!

    animatedPeakRefusal(upright, AnimatedCopies.Media3, ceiling) shouldBe null
    animatedPeakRefusal(turned, AnimatedCopies.Media3, ceiling) shouldBe
      animatedPictureTooLarge(
        animatedPeakBytes(canvas, turned.frames.size, AnimatedCopies.Media3, source = source(turned), turned = true)!!,
        ceiling,
      )
  }

  // The decoded samples of each frame's tile, found by the still WebP the reader rebuilt for it.
  private fun knownStills(): (ByteArray) -> FrameSamples? {
    val stills =
      OrientedAnimations.OPS.map {
        it to
          AnimatedWebps.still(AnimatedWebps.chunksOf(it), it.tile.width, it.tile.height)
      }
    return { bytes ->
      stills.firstOrNull { it.second.contentEquals(bytes) }?.first?.tile?.let { tile ->
        val rgba = widened(IntArray(tile.width * tile.height) { tile.texel(it % tile.width, it / tile.width) })
        FrameSamples(tile.width, tile.height, rgba, false)
      }
    }
  }

  private fun source(picture: AnimatedPicture): Long = checkNotNull(animatedSourceBytes(picture))

  private fun animated(bytes: ByteArray): AnimatedPicture =
    animatedPictureOf(bytes).shouldBeInstanceOf<AnimatedRead.Animated>().picture

  private fun widened(argb: IntArray): ShortArray =
    ShortArray(argb.size * 4) {
      val texel = argb[it / 4]
      val code =
        when (it % 4) {
          0 -> texel ushr 16 and 0xFF
          1 -> texel ushr 8 and 0xFF
          2 -> texel and 0xFF
          else -> texel ushr 24
        }
      (code * 257).toShort()
    }
}
