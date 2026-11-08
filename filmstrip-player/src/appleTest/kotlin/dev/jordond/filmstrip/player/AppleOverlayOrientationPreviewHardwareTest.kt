package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A picture tagged with a quarter turn in a preview frame read, drawn over the fill inside a gap.
 *
 * The badge is placed at the size the turn shows it at, and the middle texel of each quadrant is held to
 * [pictureSrgbCodes] of the texel [orientedSamples] lays there, so the preview turns the picture the way the export
 * does.
 */
class AppleOverlayOrientationPreviewHardwareTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a frame read draws a tagged picture turned`() =
    contractTest { scope ->
      val badge = ImageOverlay(ImageSource.ofBytes(TAGGED), Corner.TopStart, BADGE_MARGIN, BADGE_SCALE)
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine.awaitComposition(compositionOf(badge)).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val read = engine.readback.awaitFrame(IN_GAP)
        read.presentationTime shouldBe IN_GAP
        val frame = read.asTestFrame()
        val shown = TURN.orientedSize(OrientedPictures.SIZE)
        val expected = pictureSrgbCodes(null, orientedSamples(TURN, OrientedPictures.samples(), OrientedPictures.SIZE))
        val rect = badge.placedOn(frame.size, shown).rectOn(frame.size)
        val misses =
          quadrantMiddles(shown.width, shown.height).mapNotNull { (column, row) ->
            val x = ((rect.left + rect.width * (column + HALF) / shown.width) * frame.size.width).toInt()
            val y = ((rect.top + rect.height * (row + HALF) / shown.height) * frame.size.height).toInt()
            val offset = (y * frame.size.width + x) * CHANNELS
            val drawn = List(3) { frame.pixels[offset + it].toInt() and BYTE_MASK }
            val want = AuthoredColors.channelsOf(expected[row * shown.width + column])
            val close = drawn.indices.all { abs(drawn[it] - want[it]) <= AuthoredColors.TOLERANCE }
            if (close) null else "texel ($column, $row) read $drawn at ($x, $y), where the turned picture holds $want"
          }
        withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
      }
    }

  // The middle texel of each quadrant of a picture shown at width by height.
  private fun quadrantMiddles(
    width: Int,
    height: Int,
  ): List<Pair<Int, Int>> =
    listOf(0, width / 2).flatMap { left ->
      listOf(0, height / 2).map { top -> left + (width / 2) / 2 to top + (height / 2) / 2 }
    }

  // The fixture clip waits GAP_LENGTH on its track, so the video opens on a gap only the fill covers.
  private fun compositionOf(badge: ImageOverlay): EditComposition =
    EditComposition(
      tracks =
        listOf(
          Track(
            listOf(Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH))),
            start = GAP_LENGTH,
          ),
        ),
      effects = listOf(badge),
      fill = Fill.Solid(AuthoredColors.MID_GREY),
    )

  private companion object {
    val TURN = PictureOrientation.Rotate90
    val TAGGED = OrientedPictures.taggedPng(OrientedPictures.png(), TURN.exif)

    val GAP_LENGTH = 1.seconds

    // Inside the gap and on the fixture's 30fps grid, rather than at either end of the gap.
    val IN_GAP = 400.milliseconds

    const val BADGE_MARGIN = 0.04f
    const val BADGE_SCALE = 0.4f
    const val HALF = 0.5f

    const val CHANNELS = 4
    const val BYTE_MASK = 0xFF
  }
}
