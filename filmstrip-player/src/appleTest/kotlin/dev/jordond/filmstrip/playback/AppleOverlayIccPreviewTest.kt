package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.playback.contract.asTestFrame
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.awaitFrame
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.AvPlayerEngine
import dev.jordond.filmstrip.playback.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.IccPictures
import dev.jordond.filmstrip.test.TestFrame
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Colour described overlay pictures in a preview frame read, drawn over the fill inside a gap.
 *
 * Two ICC tagged pictures and a `cICP` one are each held to [pictureSrgbCodes] of their texel, read through the
 * description core reads out of the picture's bytes, the codes an SDR export draws it at. The preview so reads a
 * described picture through the same raster the export does.
 */
@OptIn(InternalFilmstripApi::class)
class AppleOverlayIccPreviewTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a frame read draws a described picture at its description's colour`() =
    contractTest { scope ->
      val badges = PICTURES.zip(CORNERS) { picture, corner -> picture to badgeOf(picture, corner) }
      val composition = compositionOf(badges.map { it.second })
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig())
      withEngine(engine) { recorder ->
        engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val read = engine.readback.awaitFrame(IN_GAP)
        read.presentationTime shouldBe IN_GAP
        val frame = read.asTestFrame()
        badges.forEach { (picture, badge) -> frame.assertDrawsColourOf(picture, badge) }
      }
    }

  private fun TestFrame.assertDrawsColourOf(
    picture: Badge,
    badge: ImageOverlay,
  ) {
    val rect = badge.placedOn(size, BADGE).rectOn(size)
    val x = (((rect.left + rect.right) / 2f) * size.width).toInt().coerceIn(0, size.width - 1)
    val y = (((rect.top + rect.bottom) / 2f) * size.height).toInt().coerceIn(0, size.height - 1)
    val offset = (y * size.width + x) * CHANNELS
    val read = List(3) { pixels[offset + it].toInt() and BYTE_MASK }
    val stored = AuthoredColours.channelsOf(picture.texel)
    val colour = pictureColour(picture.png)
    val expected = AuthoredColours.channelsOf(pictureSrgbCodes(colour, intArrayOf(picture.texel)).single())

    assertTrue(
      stored.indices.any { abs(stored[it] - expected[it]) > AuthoredColours.TOLERANCE },
      "the ${picture.name} picture's colour $expected is too close to its stored $stored to tell them apart",
    )
    assertTrue(
      read.indices.all { abs(read[it] - expected[it]) <= AuthoredColours.TOLERANCE },
      "the ${picture.name} badge read $read at ($x, $y), where its description gives $expected",
    )
  }

  private fun badgeOf(
    picture: Badge,
    corner: Corner,
  ): ImageOverlay = ImageOverlay(ImageSource.ofBytes(picture.png), corner, BADGE_MARGIN, BADGE_SCALE)

  /**
   * A badge picture's name, its PNG painted [texel] all over and that texel.
   */
  private class Badge(
    val name: String,
    val png: ByteArray,
    val texel: Int,
  )

  // The fixture clip waits GAP_LENGTH on its track, so the video opens on a gap only the fill covers.
  private fun compositionOf(badges: List<ImageOverlay>): EditComposition =
    EditComposition(
      tracks =
        listOf(
          Track(
            listOf(Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH))),
            start = GAP_LENGTH,
          ),
        ),
      effects = badges,
      fill = Fill.Solid(AuthoredColours.MID_GREY),
    )

  private companion object {
    val GAP_LENGTH = 1.seconds

    // Inside the gap and on the fixture's 30fps grid, rather than at either end of the gap.
    val IN_GAP = 400.milliseconds

    val BADGE = Size(64, 64)

    // Linear sRGB moves a mid grey far from its stored codes, Display P3 orange clips at the edge of BT.709, and the
    // cICP picture is Display P3 with no ICC profile at all.
    val PICTURES =
      listOf(IccPictures.LINEAR_SRGB, IccPictures.DISPLAY_P3).map { Badge(it.name, it.pngOf(BADGE), it.texel) } +
        ColourPictures.CICP_DISPLAY_P3.let { Badge(it.name, it.pngOf(BADGE), it.texel) }
    val CORNERS = listOf(Corner.TopStart, Corner.BottomEnd, Corner.TopEnd)

    const val BADGE_MARGIN = 0.04f
    const val BADGE_SCALE = 0.25f

    const val CHANNELS = 4
    const val BYTE_MASK = 0xFF
  }
}
