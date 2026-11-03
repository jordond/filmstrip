package dev.jordond.filmstrip.media3

import android.graphics.Color
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.test.OrientedPictures
import dev.jordond.filmstrip.test.OverlayPixels.ENCODED_TOLERANCE
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * A picture tagged with a quarter turn on real media3, placed at the size the turn shows it at and drawn turned.
 *
 * The middle texel of each quadrant is held to [pictureSrgbCodes] of the texel [orientedSamples] lays there, so a
 * picture the export drew as stored, or placed at its stored size, misses.
 */
class AndroidOverlayOrientationTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aPictureTaggedSixIsDrawnTurnedOnAnSdrExport() =
    runTest(timeout = TIMEOUT) {
      val badge = ImageOverlay(ImageSource.Bytes(TAGGED), Corner.TopStart, MARGIN, SCALE)
      val composition = EditComposition(listOf(Track(listOf(Clip(probe.fixture())))), listOf(badge))
      val frame = probe.frame(probe.export(composition))
      val size = frame.frameSize()
      val shown = TURN.orientedSize(OrientedPictures.SIZE)
      val expected = pictureSrgbCodes(null, orientedSamples(TURN, OrientedPictures.samples(), OrientedPictures.SIZE))
      val rect = badge.placedOn(size, shown).rectOn(size)

      val misses =
        quadrantMiddles(shown.width, shown.height).mapNotNull { (column, row) ->
          val x = ((rect.left + rect.width * (column + HALF) / shown.width) * size.width).toInt()
          val y = ((rect.top + rect.height * (row + HALF) / shown.height) * size.height).toInt()
          val pixel = frame.getPixel(x, y)
          val drawn = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
          val texel = expected[row * shown.width + column]
          val want = listOf(Color.red(texel), Color.green(texel), Color.blue(texel))
          val off = drawn.indices.maxOf { abs(drawn[it] - want[it]) }
          "texel ($column, $row) read $drawn at ($x, $y), where the turned picture holds $want"
            .takeIf { off > ENCODED_TOLERANCE }
        }

      withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
    }

  // The middle texel of each quadrant of a picture shown at width by height.
  private fun quadrantMiddles(
    width: Int,
    height: Int,
  ): List<Pair<Int, Int>> =
    listOf(0, width / 2).flatMap { left ->
      listOf(0, height / 2).map { top -> left + (width / 2) / 2 to top + (height / 2) / 2 }
    }

  private companion object {
    val TURN = PictureOrientation.Rotate90
    val TAGGED = OrientedPictures.taggedPng(OrientedPictures.png(), TURN.exif)

    const val MARGIN = 0.04f
    const val SCALE = 0.4f
    const val HALF = 0.5f
  }
}
