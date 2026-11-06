package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Overlays drawn into a real browser export and read back out of the written file.
 *
 * Every rect comes from `placedOn` and `rectOn`, every opacity from `frameAt`, and every authored color
 * from [AuthoredColors], so a case measures the compositor against the shared functions rather than
 * against a number of its own.
 */
class BrowserOverlayExportTest {
  @Test
  fun aCornerWatermarkCoversItsRectAndLeavesTheFarCorner() =
    runTest {
      val badge = ImageOverlay(IMAGE, Corner.TopEnd)
      val frame = exported(single(effects = listOf(badge))).last()
      val rect = badge.placedOn(frame.size, PNG).rectOn(frame.size)

      assertReads(OVERLAY, frame.around(rect.middleX, rect.middleY), "the badge")
      assertReads(
        Rgb.Blue,
        frame.around(1.0 - rect.middleX, 1.0 - rect.middleY),
        "the far corner",
        Rgb.DEFAULT_TOLERANCE.toFloat(),
      )
    }

  // Both ends of a ramp agree whether the opacity is added or multiplied in, so it is read at a quarter
  // and three quarters. The two colors it blends are read off the same file, the clip where the fade
  // has not started and the badge where it has finished.
  @Test
  fun aFadeInLandsAtTheSharedOpacityInsideItsRamp() =
    runTest {
      val badge = ImageOverlay(IMAGE, Corner.TopEnd, animation = fade(inDuration = RAMP))
      val frames = exported(single(effects = listOf(badge), frames = LONG_FRAMES))
      val size = frames.first().size
      val rect = badge.placedOn(size, PNG).rectOn(size)
      val span = TimeRange(Duration.ZERO, LONG_FRAMES.seconds / FRAME_RATE)

      withClue("the fade opens drawn") {
        (badge.frameAt(Duration.ZERO, span).shouldNotBeNull().opacity == 0f) shouldBe true
      }
      val under = frames.first().around(rect.middleX, rect.middleY)
      val over = frames.last().around(rect.middleX, rect.middleY)
      for (at in listOf(RAMP / 4, RAMP * 3 / 4)) {
        val slot = outputSlotOf(at, FRAME_RATE)
        val opacity = badge.frameAt(outputSlotTime(slot, FRAME_RATE), span).shouldNotBeNull().opacity
        val drawn = frames[slot.toInt()].around(rect.middleX, rect.middleY)
        assertReads(under.towards(over, opacity), drawn, "slot $slot at $opacity", OverlayPixels.ENCODED_TOLERANCE)
      }
    }

  @Test
  fun aClipOverlayUnderACompositionFlipMovesToTheFlippedCorner() =
    runTest {
      val badge = ImageOverlay(IMAGE, Corner.TopStart)
      val frame = exported(single(clipEffects = listOf(badge), effects = listOf(Flip(FlipAxis.Horizontal)))).last()
      val rect = badge.placedOn(frame.size, PNG).rectOn(frame.size)

      assertReads(OVERLAY, frame.around(1.0 - rect.middleX, rect.middleY), "the flipped badge")
      assertReads(
        Rgb.Blue,
        frame.around(rect.middleX, rect.middleY),
        "where it was authored",
        Rgb.DEFAULT_TOLERANCE.toFloat(),
      )
    }

  @Test
  fun aCompositionSepiaGradesAClipOverlayAndNotACompositionOne() =
    runTest {
      val onClip = ImageOverlay(IMAGE, Corner.TopStart)
      val onComposition = ImageOverlay(IMAGE, Corner.BottomEnd)
      val sepia = Sepia(1f)
      val frame = exported(single(clipEffects = listOf(onClip), effects = listOf(sepia, onComposition))).last()
      val clipRect = onClip.placedOn(frame.size, PNG).rectOn(frame.size)
      val compositionRect = onComposition.placedOn(frame.size, PNG).rectOn(frame.size)

      assertReads(OVERLAY.graded(sepia), frame.around(clipRect.middleX, clipRect.middleY), "the clip's badge")
      assertReads(OVERLAY, frame.around(compositionRect.middleX, compositionRect.middleY), "the composition's badge")
    }

  // A composition overlay is placed on the output frame, so one authored into a bar draws there and
  // the fill keeps its own color everywhere else.
  @Test
  fun aCompositionOverlayDrawsOverASolidBar() =
    runTest {
      val badge = ImageOverlay(IMAGE, Corner.TopStart)
      val frame = exported(letterboxed(effects = listOf(badge))).last()
      val rect = badge.placedOn(frame.size, PNG).rectOn(frame.size)
      withClue("the badge reaches ${rect.right}, past the bar at $BAR") { (rect.right < BAR) shouldBe true }

      assertReads(OVERLAY, frame.around(rect.middleX, rect.middleY), "the badge in the bar")
      assertReads(FILL, frame.around(rect.middleX, (rect.bottom + 1.0) / 2), "the bar under the badge")
      assertReads(FILL, frame.around(1.0 - BAR / 2, 0.5), "the far bar")
    }

  // An SDR overlay blends on the frame's own codes on every backend, and both colors land there as
  // authored, so where a partial opacity lands is predicted from the two colors' own codes.
  @Test
  fun aHalfOpaqueCompositionOverlayBlendsWithTheBarOnItsCodes() =
    runTest {
      val badge = ImageOverlay(IMAGE, Corner.TopStart, opacity = HALF)
      val frame = exported(letterboxed(effects = listOf(badge))).last()
      val rect = badge.placedOn(frame.size, PNG).rectOn(frame.size)
      val opacity = badge.frameAt(Duration.ZERO, TimeRange(Duration.ZERO, 1.seconds)).shouldNotBeNull().opacity

      assertReads(FILL.towards(OVERLAY, opacity), frame.around(rect.middleX, rect.middleY), "the half opaque badge")
    }

  @Test
  fun aCompositionOverlayDrawsOverTheGapBeforeALatePrimary() =
    runTest {
      val badge = ImageOverlay(IMAGE, Corner.TopStart)
      val frame =
        exported(single(effects = listOf(badge), start = LATE_START, fill = Fill.Solid(AuthoredColors.MID_GREY)))
          .first()
      val rect = badge.placedOn(frame.size, PNG).rectOn(frame.size)

      assertReads(OVERLAY, frame.around(rect.middleX, rect.middleY), "the badge over the gap")
      assertReads(FILL, frame.around(0.5, 0.5), "the gap where the picture will be")
    }

  // Half the badge is pushed off the clip's own frame, so that half must not land in the bar beside it.
  // The half still on the clip is read too, or a badge that never drew would pass.
  @Test
  fun aClipOverlayPushedOffItsClipIsCutAtTheClipsEdge() =
    runTest {
      val authored = ImageOverlay(IMAGE, Corner.TopStart, scale = CLIP_BADGE_SCALE)
      val placed = authored.placedOn(PORTRAIT, PNG)
      val unmoved = placed.rectOn(PORTRAIT)
      val push = OverlayFrame(offset = OverlayOffset(-(unmoved.left + unmoved.width / 2f), 0f))
      val badge = ImageOverlay(IMAGE, Corner.TopStart, scale = CLIP_BADGE_SCALE, animation = OverlayAnimation { push })
      val rect = placed.animatedBy(push).rectOn(PORTRAIT)
      withClue("the badge at $rect does not straddle the clip's edge") {
        (rect.left < 0f && rect.right > 0f) shouldBe true
      }

      val frame = exported(letterboxed(clipEffects = listOf(badge))).last()

      // The picture spans the output's full height, so a clip's y is the output's y and only x moves.
      assertReads(OVERLAY, frame.around(BAR + rect.right * PICTURE / 2, rect.middleY), "the badge on the clip")
      assertReads(FILL, frame.around(BAR + rect.left * PICTURE / 2, rect.middleY), "the bar beside it")
    }

  // A blurred fill has no color of its own, so the bar is read against the same export without the
  // overlay. Every other backend blurs the frame after the clip's chain has run, overlays included.
  @Test
  fun aBlurredFillsBarMovesTowardALargeClipOverlay() =
    runTest {
      val tall = ImageSource.ofBytes(OverlayPixels.pngOf(TALL_PNG, AuthoredColors.MID_GREEN))
      val cover = ImageOverlay(tall, Corner.TopStart, 0f, 1f)
      val rect = cover.placedOn(PORTRAIT, TALL_PNG).rectOn(PORTRAIT)
      withClue("the overlay at $rect does not cover the clip's width") {
        (rect.left <= 0f && rect.right >= 1f) shouldBe true
      }
      val probeX = BAR * 3 / 4

      val without = exported(letterboxed(fill = Fill.Blur)).last().around(probeX, rect.middleY)
      val with =
        exported(
          letterboxed(clipEffects = listOf(cover), fill = Fill.Blur),
        ).last().around(probeX, rect.middleY)

      withClue("the bar read $with with the overlay and $without without it, and the overlay is $OVERLAY") {
        (with.distanceTo(OVERLAY) + BLUR_SHIFT < without.distanceTo(OVERLAY)) shouldBe true
      }
    }

  // The held tail draws the last covered slot again, overlays included, so a fade that would still be
  // rising at the tail's own time reads as it did on that slot.
  @Test
  fun anOverlayOverAHeldTailHoldsWithTheLastCoveredSlot() =
    runTest {
      val badge = ImageOverlay(IMAGE, Corner.TopEnd, animation = fade(inDuration = BED_LENGTH))
      val primary =
        makeClip(width = WIDTH, height = HEIGHT, frames = FRAME_RATE, frameRate = FRAME_RATE, color = Rgb.Blue)
      val bed = makeClip(width = WIDTH, height = HEIGHT, frames = BED_FRAMES, frameRate = FRAME_RATE)
      val composition =
        EditComposition(
          tracks =
            listOf(
              Track(listOf(Clip(MediaSource.Bytes(primary)))),
              Track(listOf(Clip(MediaSource.Bytes(bed))), content = TrackContent.Audio),
            ),
          effects = listOf(badge),
          audio = AudioSpec.Remove,
        )
      val tail =
        engine()
          .resolve(composition, SPEC)
          .shouldBeInstanceOf<ResolveResult.Resolved>()
          .composition.heldTail
      withClue("a bed under a shorter primary left no tail") { tail.isEmpty() shouldBe false }

      val frames = exported(composition)
      val rect = badge.placedOn(frames.first().size, PNG).rectOn(frames.first().size)
      val closing = frames[tail.first.toInt() - 1].around(rect.middleX, rect.middleY)

      // The last slot is read too, since the fade has barely moved by the first and would pass either way.
      for (slot in listOf(tail.first, tail.last)) {
        val held = frames[slot.toInt()].around(rect.middleX, rect.middleY)
        assertReads(closing, held, "held slot $slot of $tail", HELD_TOLERANCE)
      }
    }

  // The preview composites through the export's own compositor, so a frame read inside the ramp is the
  // pixel the file carries for that slot, give or take the encoder's trip.
  @Test
  fun aPreviewFrameMidFadeMatchesTheExportsPixel() =
    runTest {
      val badge = ImageOverlay(IMAGE, Corner.TopEnd, animation = fade(inDuration = RAMP))
      val composition = single(effects = listOf(badge), frames = LONG_FRAMES)
      val frames = exported(composition)
      val rect = badge.placedOn(frames.first().size, PNG).rectOn(frames.first().size)
      val resolved = engine().resolve(composition, SPEC).shouldBeInstanceOf<ResolveResult.Resolved>().composition
      val preview = resolved.toBrowserPreview(composition)

      try {
        for (at in listOf(RAMP / 4, RAMP * 3 / 4)) {
          val slot = outputSlotOf(at, FRAME_RATE)
          val drawn =
            withClue("no preview frame at $at") {
              preview.frameAt(outputSlotTime(slot, FRAME_RATE)).shouldNotBeNull()
            }
          val column = (rect.middleX * drawn.size.width).toInt()
          val row = (rect.middleY * drawn.size.height).toInt()
          val offset = (row * drawn.size.width + column) * RGBA_CHANNELS
          val previewed =
            Rgb(
              drawn.pixels[offset].toInt() and BYTE_MASK,
              drawn.pixels[offset + 1].toInt() and BYTE_MASK,
              drawn.pixels[offset + 2].toInt() and BYTE_MASK,
            )
          val exported = frames[slot.toInt()].around(rect.middleX, rect.middleY)
          assertReads(exported, previewed, "slot $slot", OverlayPixels.ENCODED_TOLERANCE)
        }
      } finally {
        preview.release()
      }
    }

  /**
   * One clip [WIDTH] by [HEIGHT] painted blue, opening at [start].
   */
  private suspend fun single(
    clipEffects: List<EffectSpec> = emptyList(),
    effects: List<EffectSpec> = emptyList(),
    frames: Int = SHORT_FRAMES,
    start: Duration = Duration.ZERO,
    fill: Fill = Fill.Black,
  ): EditComposition {
    val clip = makeClip(width = WIDTH, height = HEIGHT, frames = frames, frameRate = FRAME_RATE, color = Rgb.Blue)
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clip), effects = clipEffects)), start = start)),
      effects = effects,
      audio = AudioSpec.Remove,
      fill = fill,
    )
  }

  /**
   * A landscape clip that sets the output frame, then a blue portrait one letterboxed into it over
   * [fill]. Every case reads the portrait clip, which is the file's last frame.
   */
  private suspend fun letterboxed(
    clipEffects: List<EffectSpec> = emptyList(),
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Solid(AuthoredColors.MID_GREY),
  ): EditComposition {
    val landscape =
      makeClip(width = WIDTH, height = HEIGHT, frames = SHORT_FRAMES, frameRate = FRAME_RATE, color = Rgb.Green)
    val portrait =
      makeClip(
        width = PORTRAIT.width,
        height = PORTRAIT.height,
        frames = SHORT_FRAMES,
        frameRate = FRAME_RATE,
        color = Rgb.Blue,
      )
    return EditComposition(
      tracks =
        listOf(
          Track(listOf(Clip(MediaSource.Bytes(landscape)), Clip(MediaSource.Bytes(portrait), effects = clipEffects))),
        ),
      effects = effects,
      audio = AudioSpec.Remove,
      fill = fill,
    )
  }

  private suspend fun exported(composition: EditComposition): List<OutputFrame> {
    val engine = engine()
    val plan =
      when (val verdict = engine.plan(composition, SPEC)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
      }
    val statuses = engine.export(plan, MediaSink.Uri("")).toList()
    statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()?.let {
      throw AssertionError("the export failed: ${it.error.message}")
    }
    val success = (statuses.filterIsInstance<ExportStatus.Success>().singleOrNull()).shouldNotBeNull()
    return decodeFrames(MediaSource.Uri(success.output.shouldBeInstanceOf<MediaSink.Uri>().uri))
  }

  private fun engine(): BrowserExportEngine =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    )

  private fun assertReads(
    expected: Rgb,
    drawn: Rgb,
    what: String,
    tolerance: Float = AuthoredColors.TOLERANCE,
  ) {
    val off = maxOf(abs(drawn.red - expected.red), abs(drawn.green - expected.green), abs(drawn.blue - expected.blue))
    withClue("$what read $drawn, where $expected was expected within $tolerance") { (off <= tolerance) shouldBe true }
  }

  private val OutputFrame.size: Size get() = Size(width, height)

  /**
   * The mean color of the three by three block around a point, so one pixel's rounding does not
   * decide a reading.
   */
  private fun OutputFrame.around(
    x: Double,
    y: Double,
  ): Rgb {
    val column = (x * width).toInt()
    val row = (y * height).toInt()
    val samples =
      (-1..1).flatMap { dy ->
        (-1..1).map { dx -> at((column + dx + 0.5) / width, (row + dy + 0.5) / height) }
      }
    return Rgb(
      samples.map { it.red }.average().roundToInt(),
      samples.map { it.green }.average().roundToInt(),
      samples.map { it.blue }.average().roundToInt(),
    )
  }

  private fun Rgb.distanceTo(other: Rgb): Int = abs(red - other.red) + abs(green - other.green) + abs(blue - other.blue)

  private companion object {
    val PNG = Size(8, 4)
    val TALL_PNG = Size(8, 16)
    val IMAGE: ImageSource = ImageSource.ofBytes(OverlayPixels.pngOf(PNG, AuthoredColors.MID_GREEN))
    val OVERLAY = rgbOf(AuthoredColors.MID_GREEN)
    val FILL = rgbOf(AuthoredColors.MID_GREY)

    const val WIDTH = 256
    const val HEIGHT = 128
    const val FRAME_RATE = 25
    const val SHORT_FRAMES = 6
    const val LONG_FRAMES = 50
    val RAMP = 1.seconds
    val LATE_START = 400.milliseconds
    const val HALF = 0.5f

    // A portrait clip a quarter as wide as the output, so each bar is three eighths of it.
    val PORTRAIT = Size(64, 128)
    val PICTURE = PORTRAIT.width.toDouble() / PORTRAIT.height * HEIGHT / WIDTH
    val BAR = (1.0 - PICTURE) / 2
    const val CLIP_BADGE_SCALE = 0.5f

    // Three seconds of bed under a primary of one.
    const val BED_FRAMES = 75
    val BED_LENGTH = 3.seconds

    val SPEC = ExportSpec(videoCodec = VideoCodec.H264, frameRate = FRAME_RATE)

    // Every held slot goes through the encoder again.
    const val HELD_TOLERANCE = 8f

    // How much closer to the overlay a bar has to read before it counts as having moved.
    const val BLUR_SHIFT = 12

    const val RGBA_CHANNELS = 4
    const val BYTE_MASK = 0xFF
  }
}
