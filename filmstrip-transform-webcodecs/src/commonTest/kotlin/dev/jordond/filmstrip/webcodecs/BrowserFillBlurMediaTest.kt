package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * The bars the browser draws for a [Fill.Blurred], read against [blurPlane] run by the fill's shared plan on the cover
 * crop of the clip.
 *
 * A wide clip sets the output, and a taller clip after it is contained with bars beside it. The tall clip is as wide
 * as the output, so its cover is a crop with no scale and its rows are the clip's own. The crop cuts through rows of
 * another grey, so a blur held at the cover crop's edge reads apart from one held at the clip's.
 */
class BrowserFillBlurMediaTest {
  @Test
  fun aBlurredFillLandsOnTheSharedPlanMidFrameAndAtTheCoverCropEdge() =
    runTest {
      val wide = MediaSource.Bytes(makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _, _ -> MID })
      val tall = MediaSource.Bytes(makePixelClip(WIDTH, TALL_HEIGHT, FRAMES, FRAME_RATE) { _, x, y -> tallAt(x, y) })
      val fill = Fill.Blurred(radius = RADIUS, dim = 0f)
      val drawn = frameOf(listOf(Clip(wide), Clip(tall)), fill, FRAMES + SLOT)
      val source = frameOf(listOf(Clip(tall)), Fill.Black, SLOT)
      drawn.size shouldBe Size(WIDTH, HEIGHT)
      source.size shouldBe Size(WIDTH, TALL_HEIGHT)

      val plan = fill.blurPlan(drawn.size)
      val sigma = plan.sigmaPx / plan.width
      val cover = DoubleArray(WIDTH * HEIGHT) { source.redAt(it + CROP_TOP * WIDTH) }
      val expected = blurPlane(cover, DoubleArray(cover.size) { 1.0 }, WIDTH, HEIGHT, sigma)
      val whole = DoubleArray(WIDTH * TALL_HEIGHT) { source.redAt(it) }
      val heldAtClip = blurPlane(whole, DoubleArray(whole.size) { 1.0 }, WIDTH, TALL_HEIGHT, sigma)

      var worst = 0.0
      var worstAt = 0
      var spread = 0.0
      var apart = 0.0
      for (y in 0 until HEIGHT) {
        for (x in BAR_COLUMNS) {
          val index = y * WIDTH + x
          val off = abs(drawn.redAt(index) - expected[index])
          if (off > worst) {
            worst = off
            worstAt = index
          }
          spread = maxOf(spread, abs(expected[index] - cover[index]))
          if (y == 0 ||
            y == HEIGHT - 1
          ) {
            apart = maxOf(apart, abs(heldAtClip[index + CROP_TOP * WIDTH] - expected[index]))
          }
        }
      }
      withClue("the shared blur moves the cover by $spread codes, so the read is not of a sharp frame") {
        spread shouldBeGreaterThan SHARP_SPREAD
      }
      withClue("a blur held at the clip's edge lands $apart codes from one held at the crop's, too close to tell") {
        apart shouldBeGreaterThan 2 * DRAWN_CODES
      }
      withClue(
        "the fill is $worst codes off the shared plan at ${worstAt % WIDTH}, ${worstAt / WIDTH}, " +
          "reading ${drawn.redAt(worstAt)} for ${expected[worstAt]}",
      ) {
        worst shouldBeLessThanOrEqualTo DRAWN_CODES
      }
    }

  // An edge inside the left bar's cover between rows the crop keeps, and a darker grey on the rows it cuts.
  private fun tallAt(
    x: Int,
    y: Int,
  ): Rgb =
    when {
      y < CROP_TOP || y >= CROP_TOP + HEIGHT -> DARK
      x < EDGE -> LIGHT
      else -> MID
    }

  private suspend fun frameOf(
    clips: List<Clip>,
    fill: Fill,
    slot: Int,
  ): PreviewFrame {
    val composition = Composition(tracks = listOf(Track(clips)), audio = AudioSpec.Remove, fill = fill)
    val resolved =
      webCodecsExportEngine(
        components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
        prober = BrowserProber(),
      ).resolve(composition, ExportSpec(videoCodec = VideoCodec.H264))
        .shouldBeInstanceOf<ResolveResult.Resolved>()
        .composition
    val preview = resolved.toBrowserPreview(composition)
    try {
      return preview.frameAt(outputSlotTime(slot.toLong(), FRAME_RATE)).shouldNotBeNull()
    } finally {
      preview.release()
    }
  }

  private fun PreviewFrame.redAt(index: Int): Double = (pixels[index * RGBA].toInt() and BYTE_MASK).toDouble()

  private companion object {
    const val WIDTH = 320
    const val HEIGHT = 180

    // Twice the output's height, so it is contained 160 wide and its cover crops a quarter off the top and bottom.
    const val TALL_HEIGHT = 360
    const val CROP_TOP = 90
    const val EDGE = 40
    val BAR_COLUMNS = (0 until 80) + (240 until WIDTH)

    // A sigma of 9 output pixels, which the plan boxes down by 2 onto whole pixels.
    const val RADIUS = 0.05f
    const val FRAMES = 12
    const val FRAME_RATE = 30
    const val SLOT = 6
    const val RGBA = 4
    const val BYTE_MASK = 0xFF

    val LIGHT = Rgb(220, 220, 220)
    val MID = Rgb(130, 130, 130)
    val DARK = Rgb(30, 30, 30)

    // Each eight bit target between the steps rounds once, and the read rounds once more, as for a GaussianBlur.
    // Measured at 1.18 on headless Chrome.
    const val DRAWN_CODES = 2.0

    // A blur of a 90 code edge at this sigma moves the columns beside it by far more.
    const val SHARP_SPREAD = 30.0
  }
}
