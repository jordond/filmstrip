package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.videoSlots
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.render.BrowserCompositor
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The whole published pipeline in a real browser: clips are synthesised, planned and exported
 * through the public facade, then decoded back through a reader that never saw the encoder.
 *
 * The assertions look at where a pixel landed, not only at how many there were. A frame counter
 * cannot tell an upside-down export from an upright one.
 */
class BrowserExportMediaTest {
  @Test
  fun exportKeepsTheSourceUpright() =
    runTest {
      val bytes =
        makeClip { _, y ->
          if (y < HALF) Rgb.Red else Rgb.Blue
        }
      val success = exportOf(compositionOf(MediaSource.Bytes(bytes)), MediaSink.Uri(""))

      val frame =
        withClue("the export decoded no frames") {
          decodeFrames(outputOf(success)).firstOrNull().shouldNotBeNull()
        }
      val top = frame.at(x = 0.5, y = 0.25)
      val bottom = frame.at(x = 0.5, y = 0.75)
      withClue("the top of the frame was $top, and the source's top was red") { top.isNear(Rgb.Red) shouldBe true }
      withClue("the bottom of the frame was $bottom, and the source's bottom was blue") {
        bottom.isNear(Rgb.Blue) shouldBe true
      }
    }

  @Test
  fun cropKeepsTheRequestedRegionTheRightWayUp() =
    runTest {
      // The built-in resolver builds a crop matrix as `offsetV = 1 - rect.bottom`, which is only the
      // right region once the upload is flipped. Retaining filmstrip's top half has to come out as
      // the source's top half, and nothing but it.
      val bytes = makeClip { _, y -> if (y < HALF) Rgb.Red else Rgb.Blue }
      val composition =
        Composition(
          tracks =
            listOf(
              Track(
                listOf(
                  Clip(MediaSource.Bytes(bytes), effects = listOf(CropRect(NormalizedRect(0f, 0f, 1f, 0.5f)))),
                ),
              ),
            ),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frame = decodeFrames(outputOf(success)).firstOrNull().shouldNotBeNull()
      for (y in listOf(0.15, 0.5, 0.85)) {
        for (x in listOf(0.1, 0.5, 0.9)) {
          val color = frame.at(x, y)
          withClue("the crop kept $color at ($x, $y), and the source's top half was red") {
            color.isNear(Rgb.Red) shouldBe true
          }
        }
      }
    }

  @Test
  fun letterboxBarsAreBlackAndTheClipIsNotCropped() =
    runTest {
      // The first clip sets the output size and fills it, so anything left in the bars afterwards
      // is the previous clip rather than a clear. The second is half as tall, so it letterboxes.
      val filler = makeClip(color = Rgb.Green)
      val wide =
        makeClip(width = 64, height = 32) { _, y ->
          if (y < WIDE_RED_ROWS) Rgb.Red else Rgb.Blue
        }

      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(filler)), Clip(MediaSource.Bytes(wide))))),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frames = decodeFrames(outputOf(success))
      withClue("the export decoded ${frames.size} frames") { (frames.size > 1) shouldBe true }
      val letterboxed = frames.last()

      withClue("the top bar was ${letterboxed.at(0.5, 0.12)}, and a letterbox bar is cleared to black") {
        letterboxed.at(x = 0.5, y = 0.12).isNear(Rgb.Black) shouldBe true
      }
      withClue("the bottom bar was ${letterboxed.at(0.5, 0.88)}, and a letterbox bar is cleared to black") {
        letterboxed.at(x = 0.5, y = 0.88).isNear(Rgb.Black) shouldBe true
      }
      // The drawn band runs from a quarter to three quarters of the frame, and the source's top
      // eighth is red. Sampling a quarter of the way down lands inside it only when the whole
      // source is fitted into the band rather than centre-cropped into it.
      withClue("the top of the letterboxed clip was ${letterboxed.at(0.5, 0.3125)}, and the source's top was red") {
        letterboxed.at(x = 0.5, y = 0.3125).isNear(Rgb.Red) shouldBe true
      }
      withClue("the middle of the letterboxed clip was ${letterboxed.at(0.5, 0.6)}, and the source's middle was blue") {
        letterboxed.at(x = 0.5, y = 0.6).isNear(Rgb.Blue) shouldBe true
      }
    }

  @Test
  fun aSolidFillOfANonBlackColorPaintsTheBars() =
    runTest {
      val success = exportOf(letterboxComposition(Fill.Solid(PURPLE_ARGB)), MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      withClue("the top bar was ${letterboxed.at(0.5, 0.12)}, and the fill was $PURPLE_RGB") {
        letterboxed.at(x = 0.5, y = 0.12).isNear(PURPLE_RGB) shouldBe true
      }
      withClue("the bottom bar was ${letterboxed.at(0.5, 0.88)}, and the fill was $PURPLE_RGB") {
        letterboxed.at(x = 0.5, y = 0.88).isNear(PURPLE_RGB) shouldBe true
      }
    }

  // A composition-level brightness must not reach a bar it was not given a color for, whatever
  // it does to the clip's own pixels.
  @Test
  fun aCompositionBrightnessLeavesASolidFillsBarsUntouched() =
    runTest {
      val composition = letterboxComposition(Fill.Solid(PURPLE_ARGB), effects = listOf(Brightness(HALF_BRIGHTNESS)))
      val success = exportOf(composition, MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      withClue("the top bar was ${letterboxed.at(0.5, 0.12)}, and a composition brightness should not reach it") {
        letterboxed.at(x = 0.5, y = 0.12).isNear(PURPLE_RGB) shouldBe true
      }
      val dimmedBlue = Rgb(Rgb.Blue.red / 2, Rgb.Blue.green / 2, Rgb.Blue.blue / 2)
      withClue("the clip's own pixels read ${letterboxed.at(0.5, 0.6)}, and half of ${Rgb.Blue} is $dimmedBlue") {
        letterboxed.at(x = 0.5, y = 0.6).isNear(dimmedBlue) shouldBe true
      }
    }

  // The picture opens where the composition does, so a primary that starts late sits behind the fill
  // until then rather than being drawn from zero ahead of its own audio. The start is eighteen slots
  // in, right on the frame grid.
  @Test
  fun aPrimaryThatStartsLateOpensOnTheFill() = runTest { assertOpensOnTheFill(ON_GRID_START) }

  // Fifteen slots and three tenths of another. The gap rounds to the nearer slot, so its last frame
  // and the clip's first never land on the same timestamp in the file.
  @Test
  fun aPrimaryThatStartsBetweenTwoSlotsWritesEveryFrameOnce() = runTest { assertOpensOnTheFill(OFF_GRID_START) }

  // Exactly half a slot in. A clip opened at the start itself puts every frame half a tick off the
  // file's timescale, and the muxer rounds some of them onto the tick of the frame before.
  @Test
  fun aPrimaryThatStartsHalfASlotInWritesEveryFrameOnce() =
    runTest { assertOpensOnTheFill(HALF_SLOT_START, frameRate = GRID_FRAME_RATE, clipFrames = GRID_CLIP_FRAMES) }

  /**
   * Exports a clip of [clipFrames] frames at [frameRate] behind a gap of [start], then checks the file holds the fill
   * on every slot the grid puts in front of the start and the clip on every slot after, one frame to a slot. The gap
   * and the clip are each read in their middle, since a file that dropped the gap still opens and closes on a frame.
   */
  private suspend fun assertOpensOnTheFill(
    start: Duration,
    frameRate: Int = LATE_FRAME_RATE,
    clipFrames: Int = LATE_CLIP_FRAMES,
  ) {
    val bytes = makeClip(frames = clipFrames, frameRate = frameRate, color = Rgb.Green)
    val composition =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))), start = start)),
        audio = AudioSpec.Remove,
        fill = Fill.Solid(PURPLE_ARGB),
      )
    val engine = browserEngine()

    val resolved = engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>().composition
    withClue("the slot counts here are for $frameRate fps") { resolved.output.frameRate shouldBe frameRate }
    val oneSlotUs = micros(1.seconds / frameRate)
    val clip =
      resolved.tracks
        .first()
        .clips
        .single()
    val gapFrames = outputSlotOf(clip.span.start, frameRate).toInt()
    val startUs = micros(clip.span.start)
    val frames = decodeFrames(outputOf(exportWith(engine, planWith(engine, composition))))

    withClue("a gap of $start wrote ${frames.size} frames") {
      frames.size shouldBe outputSlotOf(resolved.duration, frameRate).toInt()
    }
    frames.zipWithNext().forEach { (before, after) ->
      withClue("a frame at ${after.timestampUs}us follows one at ${before.timestampUs}us") {
        (after.timestampUs > before.timestampUs) shouldBe true
      }
    }
    val filled = frames.count { it.at(x = 0.5, y = 0.5).isNear(PURPLE_RGB) }
    withClue("a gap of $start wrote $filled frames of fill") { filled shouldBe gapFrames }

    // Each slot is drawn at the time the encoder writes, so the frames land one per slot of the grid
    // rather than bunched at the gap's opening or strung off the grid from the clip's own start.
    assertEveryFrameHoldsItsOwnSlot(frames, frameRate)

    val inGap = frames.nearestTo(startUs / 2).at(x = 0.5, y = 0.5)
    withClue("the middle of the gap was $inGap, and the fill was $PURPLE_RGB") {
      inGap.isNear(PURPLE_RGB) shouldBe true
    }
    val inClip = frames.nearestTo(micros(clip.span.start + clip.outputDuration / 2)).at(x = 0.5, y = 0.5)
    withClue("the middle of the clip was $inClip, and the clip was ${Rgb.Green}") {
      inClip.isNear(Rgb.Green) shouldBe true
    }

    val opening = frames.firstOrNull { it.at(x = 0.5, y = 0.5).isNear(Rgb.Green) }.shouldNotBeNull()
    withClue("the clip opened at ${opening.timestampUs}us, and the track starts at ${startUs}us") {
      (abs(opening.timestampUs - startUs) < oneSlotUs) shouldBe true
    }
    val endUs = frames.last().let { it.timestampUs + it.durationUs }
    withClue("the video ends at ${endUs}us, and the composition runs ${micros(resolved.duration)}us") {
      (abs(endUs - micros(resolved.duration)) < oneSlotUs) shouldBe true
    }
  }

  // A clip trimmed between two slots holds only the slots its span rounds onto, so its last frame and
  // the next clip's first never share a slot and nothing is left between them. One length ends a
  // quarter of a slot short of half way and the other a quarter past it.
  @Test
  fun anOffGridClipMeetsTheNextWithoutAGapOrACollision() =
    runTest {
      OFF_GRID_LENGTHS.forEach { length -> assertMeetsTheNext(length) }
    }

  /**
   * Exports a green clip trimmed to [length] with a red clip a second long after it, then checks the file holds one
   * frame on every slot of the grid, the slots either side of the seam each from their own clip, and each clip's color
   * in its middle.
   */
  private suspend fun assertMeetsTheNext(length: Duration) {
    val green = makeClip(frames = GRID_CLIP_FRAMES, frameRate = GRID_FRAME_RATE, color = Rgb.Green)
    val red = makeClip(frames = GRID_FRAME_RATE, frameRate = GRID_FRAME_RATE, color = Rgb.Red)
    val composition =
      Composition(
        tracks =
          listOf(
            Track(
              listOf(
                Clip(MediaSource.Bytes(green), trim = TimeRange(Duration.ZERO, length)),
                Clip(MediaSource.Bytes(red)),
              ),
            ),
          ),
        audio = AudioSpec.Remove,
      )
    val spec = ExportSpec(frameRate = GRID_FRAME_RATE)
    val engine = browserEngine()

    val resolved = engine.resolve(composition, spec).shouldBeInstanceOf<ResolveResult.Resolved>().composition
    val frames = decodeFrames(outputOf(exportWith(engine, planWith(engine, composition, spec))))

    withClue("a first clip of $length wrote ${frames.size} frames") {
      frames.size shouldBe outputSlotOf(resolved.duration, GRID_FRAME_RATE).toInt()
    }
    assertEveryFrameHoldsItsOwnSlot(frames, GRID_FRAME_RATE)

    val seam = outputSlotOf(length, GRID_FRAME_RATE).toInt()
    val before = frames[seam - 1].at(x = 0.5, y = 0.5)
    withClue("slot ${seam - 1}, the last before a seam at $length, drew $before") {
      before.isNear(Rgb.Green) shouldBe true
    }
    val after = frames[seam].at(x = 0.5, y = 0.5)
    withClue("slot $seam, the first after a seam at $length, drew $after") { after.isNear(Rgb.Red) shouldBe true }

    val second =
      resolved.tracks
        .first()
        .clips
        .last()
    val inFirst = frames.nearestTo(micros(length / 2)).at(x = 0.5, y = 0.5)
    withClue("the middle of the first clip was $inFirst") { inFirst.isNear(Rgb.Green) shouldBe true }
    val inSecond = frames.nearestTo(micros(second.span.start + second.outputDuration / 2)).at(x = 0.5, y = 0.5)
    withClue("the middle of the second clip was $inSecond") { inSecond.isNear(Rgb.Red) shouldBe true }
  }

  // A pass trimmed to 1.030 seconds covers twenty six slots, so its last slot runs on to 1.040 and past
  // the end of the window it reads. That slot holds the pass's last frame, the only red one, and the
  // pass laid after it opens on its first again.
  @Test
  fun aSlotThatRunsPastTheEndOfAPassHoldsItsLastFrame() =
    runTest {
      val bytes =
        makeClip(frames = PAST_END_FRAMES, frameRate = GRID_FRAME_RATE) { index, _ ->
          if (index == PAST_END_FRAMES - 1) Rgb.Red else Rgb.Green
        }
      val pass = Clip(MediaSource.Bytes(bytes), trim = TimeRange(Duration.ZERO, PAST_END_TRIM))
      val composition = Composition(tracks = listOf(Track(listOf(pass, pass))), audio = AudioSpec.Remove)
      val spec = ExportSpec(frameRate = GRID_FRAME_RATE)
      val engine = browserEngine()

      val resolved = engine.resolve(composition, spec).shouldBeInstanceOf<ResolveResult.Resolved>().composition
      val frames = decodeFrames(outputOf(exportWith(engine, planWith(engine, composition, spec))))

      frames.size shouldBe outputSlotOf(resolved.duration, GRID_FRAME_RATE).toInt()
      assertEveryFrameHoldsItsOwnSlot(frames, GRID_FRAME_RATE)
      resolved.tracks.first().clips.forEach { laid ->
        val slots = outputSlotsOf(laid.span, GRID_FRAME_RATE)
        val opening = frames[slots.first.toInt()].at(x = 0.5, y = 0.5)
        withClue("the pass at ${laid.span.start} opened on $opening") { opening.isNear(Rgb.Green) shouldBe true }
        val closing = frames[slots.last.toInt()].at(x = 0.5, y = 0.5)
        withClue("the pass at ${laid.span.start} closed on $closing") { closing.isNear(Rgb.Red) shouldBe true }
      }
    }

  // A bed that outlasts the picture sets the composition's length, and the file carries a frame on every slot of it.
  // Past the primary's last covered slot each one is that slot's frame again, the only red one the primary has.
  @Test
  fun aBedThatOutlastsThePrimaryHoldsItsLastFrameToTheEnd() =
    runTest {
      val (frames, tail) = exportUnderABed()

      heldProbes(tail).forEach { slot ->
        val color = frames[slot].at(x = 0.5, y = 0.5)
        withClue("slot $slot of a tail over $tail drew $color, and the primary ends on red") {
          color.isNear(Rgb.Red) shouldBe true
        }
      }
    }

  // The composition's effects are part of the frame that freezes. The last covered slot is graded, and every slot of
  // the tail reads the same as it rather than as the ungraded source.
  @Test
  fun aCompositionEffectHoldsWithTheFrameItGraded() =
    runTest {
      val (frames, tail) = exportUnderABed(effects = listOf(Brightness(HALF_BRIGHTNESS)))

      val closing = frames[tail.first.toInt() - 1].at(x = 0.5, y = 0.5)
      val graded = Rgb.Red.graded(Brightness(HALF_BRIGHTNESS))
      withClue("the last covered slot drew $closing, and the primary's red graded is $graded") {
        closing.isNear(graded) shouldBe true
      }
      heldProbes(tail).forEach { slot ->
        val color = frames[slot].at(x = 0.5, y = 0.5)
        withClue("slot $slot of a tail over $tail drew $color, and the last covered slot drew $closing") {
          color.isNear(closing, tolerance = HELD_TOLERANCE) shouldBe true
        }
      }
    }

  /**
   * Exports a primary a second long, whose last frame alone is red, over a bed [BED_FRAMES] frames long on an audio
   * track of its own, with [effects] on the composition. Checks the file holds one frame on every slot the composition
   * covers and hands back the decoded frames with the held tail they were read against.
   *
   * The bed's source carries no audio, so nothing is mixed and the bed does no more than set how long the composition
   * runs.
   */
  private suspend fun exportUnderABed(effects: List<EffectSpec> = emptyList()): Pair<List<OutputFrame>, LongRange> {
    val primary =
      makeClip(frames = GRID_FRAME_RATE, frameRate = GRID_FRAME_RATE) { index, _ ->
        if (index == GRID_FRAME_RATE - 1) Rgb.Red else Rgb.Green
      }
    val bed = makeClip(frames = BED_FRAMES, frameRate = GRID_FRAME_RATE)
    val composition =
      Composition(
        tracks =
          listOf(
            Track(listOf(Clip(MediaSource.Bytes(primary)))),
            Track(listOf(Clip(MediaSource.Bytes(bed))), content = TrackContent.Audio),
          ),
        effects = effects,
        audio = AudioSpec.Remove,
      )
    val spec = ExportSpec(frameRate = GRID_FRAME_RATE)
    val engine = browserEngine()

    val resolved = engine.resolve(composition, spec).shouldBeInstanceOf<ResolveResult.Resolved>().composition
    val tail = resolved.heldTail
    withClue("a bed of $BED_FRAMES frames under a shorter primary left no tail") { tail.isEmpty() shouldBe false }
    val frames = decodeFrames(outputOf(exportWith(engine, planWith(engine, composition, spec))))

    withClue("a tail over $tail wrote ${frames.size} frames") { frames.size shouldBe resolved.videoSlots.toInt() }
    assertEveryFrameHoldsItsOwnSlot(frames, GRID_FRAME_RATE)
    return frames to tail
  }

  /**
   * The slots a held tail is read on: the last one the primary covers, then the tail's first, its middle and its last.
   */
  private fun heldProbes(tail: LongRange): List<Int> =
    listOf(tail.first - 1, tail.first, (tail.first + tail.last) / 2, tail.last).map { it.toInt() }

  /**
   * Checks frame `i` of [frames] sits on slot `i` of the shared grid at [frameRate] and runs one slot, so nothing
   * landed between two slots, twice on one, or ran short of the next.
   */
  private fun assertEveryFrameHoldsItsOwnSlot(
    frames: List<OutputFrame>,
    frameRate: Int,
  ) {
    val slotUs = micros(1.seconds / frameRate)
    frames.forEachIndexed { slot, frame ->
      val opensUs = micros(outputSlotTime(slot.toLong(), frameRate))
      withClue("frame $slot landed at ${frame.timestampUs}us where its slot sits at ${opensUs}us") {
        (abs(frame.timestampUs - opensUs) < TIMESTAMP_TOLERANCE_US) shouldBe true
      }
      withClue("frame $slot runs ${frame.durationUs}us where a slot runs ${slotUs}us") {
        (abs(frame.durationUs - slotUs) < TIMESTAMP_TOLERANCE_US) shouldBe true
      }
    }
  }

  @Test
  fun aBlurredFillPaintsBarsThatAreNotFlat() =
    runTest {
      val success = exportOf(letterboxComposition(Fill.Blur), MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      // The wide clip's top eighth is red and the rest is blue, so a bar sample near the very edge
      // of the frame stays red while one closer to the sharp clip picks up blue from the blur.
      val nearEdge = letterboxed.at(x = 0.5, y = 0.03)
      val nearClip = letterboxed.at(x = 0.5, y = 0.23)
      withClue("the bar read $nearEdge at the edge and $nearClip near the clip, which is too flat for a blur") {
        !nearEdge.isNear(nearClip, tolerance = FLAT_TOLERANCE) shouldBe true
      }
    }

  @Test
  fun aBlurredFillLeavesTheSharpClipAlone() =
    runTest {
      val success = exportOf(letterboxComposition(Fill.Blur), MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      val centre = letterboxed.at(x = 0.5, y = 0.6)
      withClue("the sharp region read $centre, and the source's middle was blue") {
        centre.isNear(Rgb.Blue) shouldBe true
      }
    }

  @Test
  fun aFullyDimmedBlurredFillIsBlack() =
    runTest {
      val success = exportOf(letterboxComposition(Fill.Blurred(dim = 1f)), MediaSink.Uri(""))

      val letterboxed = decodeFrames(outputOf(success)).last()
      withClue("a fully dimmed bar was ${letterboxed.at(0.5, 0.12)}, and dim = 1f should read black") {
        letterboxed.at(x = 0.5, y = 0.12).isNear(Rgb.Black) shouldBe true
      }
    }

  @Test
  fun aHalfDimmedBlurredFillIsHalfAsBrightAsUndimmed() =
    runTest {
      // A multiplicative gain and an additive fade toward black agree at both ends of dim, which
      // is exactly why aFullyDimmedBlurredFillIsBlack cannot tell them apart on its own. Checking a
      // fractional dim against the same pixel undimmed is what catches an offset instead of a gain.
      val undimmed = exportOf(letterboxComposition(Fill.Blurred(dim = 0f)), MediaSink.Uri(""))
      val halfDimmed = exportOf(letterboxComposition(Fill.Blurred(dim = 0.5f)), MediaSink.Uri(""))

      val undimmedBar = decodeFrames(outputOf(undimmed)).last().at(x = 0.5, y = 0.12)
      val halfDimmedBar = decodeFrames(outputOf(halfDimmed)).last().at(x = 0.5, y = 0.12)
      val expected = Rgb(undimmedBar.red / 2, undimmedBar.green / 2, undimmedBar.blue / 2)
      withClue("half dim read $halfDimmedBar, and half of the undimmed $undimmedBar is $expected") {
        halfDimmedBar.isNear(expected, tolerance = HALF_DIM_TOLERANCE) shouldBe true
      }
    }

  // Halving reads as half the signal, not as a lifted black: an additive brightness of the same
  // size would land this grey near white instead.
  @Test
  fun brightnessScalesEveryChannel() =
    runTest {
      val bytes = makeClip(color = Rgb.Grey)
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = listOf(Brightness(HALF_BRIGHTNESS)))))),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frame = decodeFrames(outputOf(success)).firstOrNull().shouldNotBeNull()
      val dimmed = frame.at(x = 0.5, y = 0.5)
      val expected = Rgb(Rgb.Grey.red / 2, Rgb.Grey.green / 2, Rgb.Grey.blue / 2)
      withClue("a half-brightness clip encoded as $dimmed, and half of ${Rgb.Grey} is $expected") {
        dimmed.isNear(expected) shouldBe true
      }
    }

  // The other color effects lower through the same uniform, so one that pivots rather than scales
  // proves the whole matrix reached the shader and not only its diagonal.
  @Test
  fun contrastStretchesAboutMidGrey() =
    runTest {
      val contrast = Contrast(STRETCHED_CONTRAST)
      val bytes = makeClip(color = PIVOT_SPREAD)
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = listOf(contrast))))),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frame = decodeFrames(outputOf(success)).firstOrNull().shouldNotBeNull()
      val stretched = frame.at(x = 0.5, y = 0.5)
      val expected = PIVOT_SPREAD.graded(contrast)
      withClue("a contrast of $STRETCHED_CONTRAST encoded $PIVOT_SPREAD as $stretched, and the matrix says $expected") {
        stretched.isNear(expected) shouldBe true
      }
    }

  @Test
  fun aRequestedFrameRateResamples() =
    runTest {
      val bytes = makeClip(frames = 12, frameRate = 30)
      val spec =
        ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, frameRate = 15)
      val success = exportOf(compositionOf(MediaSource.Bytes(bytes)), MediaSink.Uri(""), spec)

      success.info.video
        .shouldNotBeNull()
        .frameRate shouldBe 15f
      // Twelve frames at thirty is four hundred milliseconds, which is six slots at fifteen.
      decodeFrames(outputOf(success)).size shouldBe 6
    }

  @Test
  fun theSourceFrameRateIsProbedRatherThanAssumed() =
    runTest {
      val bytes = makeClip(frames = 12, frameRate = 20)
      val success = exportOf(compositionOf(MediaSource.Bytes(bytes)), MediaSink.Uri(""))

      val rate =
        success.info.video
          .shouldNotBeNull()
          .frameRate
          .shouldNotBeNull()
      withClue("the export ran at $rate, and the source runs at 20") {
        (abs(rate - 20f) <= RATE_TOLERANCE) shouldBe true
      }
      // A rate that defaulted to thirty would ask for eighteen frames out of a twelve frame source.
      decodeFrames(outputOf(success)).size shouldBe 12
    }

  @Test
  fun trimKeepsOnlyTheRequestedWindow() =
    runTest {
      // Each frame is a shade brighter than the one before, so the first frame of the export says
      // which source frame the trim actually started from.
      val bytes = makeClip(frames = 60, frameRate = 30) { index, _ -> Rgb(index * RAMP_STEP, 0, 0) }
      val composition =
        Composition(
          tracks =
            listOf(
              Track(
                listOf(
                  Clip(MediaSource.Bytes(bytes), trim = TimeRange(TRIM_START, TRIM_END)),
                ),
              ),
            ),
          audio = AudioSpec.Remove,
        )
      val success = exportOf(composition, MediaSink.Uri(""))

      val frames = decodeFrames(outputOf(success))
      withClue("half a second at thirty frames a second is fifteen frames") { frames.size shouldBe 15 }

      val first = frames.first().at(x = 0.5, y = 0.5)
      val expected = Rgb(TRIM_START_FRAME * RAMP_STEP, 0, 0)
      withClue("the export opened on $first, and the source's frame at one second is $expected") {
        first.isNear(expected) shouldBe true
      }
    }

  // A trim used to force the encode path. A clip that lets the cut move back to a sync sample stays
  // on mediabunny's packet copy, and the ramp says which source frame the copy opened on, which a
  // duration alone would not.
  @Test
  fun aSnappedTrimCopiesFromTheOpeningThePlannerResolved() =
    runTest {
      val bytes = rampClip()
      val composition = snappedComposition(bytes, SNAP_WITHIN)
      val engine = browserEngine()

      val resolved = resolvedClipOf(engine, composition)
      withClue("the plan did not resolve the cut onto a sync sample") { resolved.startsAtKeyFrame shouldBe true }
      withClue("the cut resolved to ${resolved.start}, so the fixture never put the trim between two sync samples") {
        (resolved.start < TRIM_START) shouldBe true
      }

      val plan = planWith(engine, composition)
      withClue("a snapped trim on an untouched clip is meant to copy") { plan.path shouldBe ExportPath.Copy }

      val ramp = rampOf(MediaSource.Bytes(bytes))
      val frames = decodeFrames(outputOf(exportWith(engine, plan)))
      val openedFrame = ramp.frameOf(frames.first().at(x = 0.5, y = 0.5))
      withClue("the copy opened on frame $openedFrame and the plan resolved the cut to ${resolved.start}") {
        openedFrame shouldBe frameIndexAt(resolved.start)
      }
      // The count the plan settled from the source's packets, which is what says the tail was bounded as well as the
      // head.
      val counted =
        engine
          .resolve(composition, ExportSpec())
          .shouldBeInstanceOf<ResolveResult.Resolved>()
          .composition.copyPrefix
          .shouldNotBeNull()
      frames.size shouldBe counted.count
    }

  // Zero is the default and it keeps the cut where it was asked for. Nothing is a sync sample there,
  // so the copy is out of reach and the export decodes instead of quietly moving the cut.
  @Test
  fun aTrimLeftAtTheDefaultToleranceNeitherMovesNorCopies() =
    runTest {
      val bytes = rampClip()
      val composition = snappedComposition(bytes, Duration.ZERO)
      val engine = browserEngine()

      val resolved = resolvedClipOf(engine, composition)
      withClue("the cut moved with no tolerance to move it") { resolved.start shouldBe TRIM_START }
      withClue("the cut was reported as opening on a sync sample") { resolved.startsAtKeyFrame shouldBe false }

      val plan = planWith(engine, composition, ExportSpec(bitrate = MEASURING_BITRATE))
      withClue("a cut off a sync sample is not copyable") { plan.path shouldBe ExportPath.Transcode }
      withClue("the plan blamed ${plan.copyBlockedBy} for re-encoding") {
        (CopyBlocker.TrimNotOnSyncSample in plan.copyBlockedBy) shouldBe true
      }

      val ramp = reencodedRampOf(engine, bytes)
      val frames = decodeFrames(outputOf(exportWith(engine, plan)))
      withClue("the re-encode opened somewhere other than the cut") {
        ramp.frameOf(frames.first().at(x = 0.5, y = 0.5)) shouldBe TRIM_START_FRAME
      }
    }

  // The two tracks are copied separately, so a seek applied to one and not the other leaves an
  // audio track running the whole source under a video track that stops at the cut.
  @Test
  fun aSnappedTrimSeeksTheAudioTrackWithTheVideoTrack() =
    runTest {
      val bytes =
        makeClipWithAudio(
          frames = SNAPPED_FRAMES,
          frameRate = SNAPPED_FRAME_RATE,
          keyFrameSeconds = SNAPPED_KEY_FRAME_SECONDS,
        )
      val composition = snappedComposition(bytes, SNAP_WITHIN)
      val engine = browserEngine()

      val plan = planWith(engine, composition)
      withClue("a snapped trim on an untouched clip is meant to copy") { plan.path shouldBe ExportPath.Copy }

      val output = outputOf(exportWith(engine, plan))
      val audio = withClue("the copy dropped the audio track") { decodeAudio(output).shouldNotBeNull() }
      val frames = decodeFrames(output)
      val videoStartUs = frames.first().timestampUs
      val videoEndUs = frames.last().let { it.timestampUs + it.durationUs }

      // Both ends, since a seek applied to one track only leaves the other running the whole
      // source, and only one end of it would show.
      withClue("the audio opens at ${audio.startUs}us under video that opens at ${videoStartUs}us") {
        (abs(audio.startUs - videoStartUs) <= TRACK_SKEW_TOLERANCE_US) shouldBe true
      }
      withClue("the audio ends at ${audio.durationUs}us under video that ends at ${videoEndUs}us") {
        (abs(audio.durationUs - videoEndUs) <= TRACK_SKEW_TOLERANCE_US) shouldBe true
      }
    }

  @Test
  fun aUriSinkHandsBackABlobUrl() =
    runTest {
      val success = exportOf(compositionOf(MediaSource.Bytes(makeClip())), MediaSink.Uri(""))

      val output = success.output.shouldBeInstanceOf<MediaSink.Uri>()
      withClue("a Uri sink handed back ${output.uri}") { output.uri.startsWith("blob:") shouldBe true }
      withClue("the blob url decoded nothing") { decodeFrames(MediaSource.Uri(output.uri)).isNotEmpty() shouldBe true }
    }

  @Test
  fun aTemporarySinkResolvesToAName() =
    runTest {
      val success = exportOf(compositionOf(MediaSource.Bytes(makeClip())), MediaSink.Temporary)

      val output = success.output.shouldBeInstanceOf<MediaSink.Path>()
      withClue("a Temporary sink resolved to ${output.path}") { output.path.endsWith(".mp4") shouldBe true }
    }

  @Test
  fun aPathWhoseDirectoryIsMissingStillDownloads() =
    runTest {
      val sink = MediaSink.Path("/filmstrip-absent/nested/out.mp4")

      val success = exportOf(compositionOf(MediaSource.Bytes(makeClip())), sink)

      success.output shouldBe sink
    }

  @Test
  fun untouchedExportCopiesPacketsRatherThanReencoding() =
    runTest {
      val bytes = makeClip()
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), ExportSpec())

      val sourcePackets = readEncodedVideoPackets(MediaSource.Bytes(bytes))
      val copiedPackets = readEncodedVideoPackets(outputOf(success))

      withClue("the copy carried a different packet count") { copiedPackets.size shouldBe sourcePackets.size }
      sourcePackets.zip(copiedPackets).forEachIndexed { index, (expected, actual) ->
        withClue("packet $index differed between the source and the copy") {
          expected.contentEquals(actual) shouldBe true
        }
      }
    }

  // On a copy, ExportPlanner reports the source's own codec rather than the ladder's pick, and
  // VideoCodec has entries this backend never encodes. Planning and exporting a VP8 or AV1 source
  // must still copy it rather than throw building an encoder config.
  @Test
  fun untouchedVp8ExportCopiesPacketsRatherThanReencoding() =
    runTest {
      val bytes = makeClipInCodec("vp8")
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), ExportSpec())

      val sourcePackets = readEncodedVideoPackets(MediaSource.Bytes(bytes))
      val copiedPackets = readEncodedVideoPackets(outputOf(success))

      withClue("the copy carried a different packet count") { copiedPackets.size shouldBe sourcePackets.size }
      sourcePackets.zip(copiedPackets).forEachIndexed { index, (expected, actual) ->
        withClue("packet $index differed between the source and the copy") {
          expected.contentEquals(actual) shouldBe true
        }
      }
    }

  @Test
  fun untouchedAv1ExportCopiesPacketsRatherThanReencoding() =
    runTest {
      val bytes = makeClipInCodec("av1")
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), ExportSpec())

      val sourcePackets = readEncodedVideoPackets(MediaSource.Bytes(bytes))
      val copiedPackets = readEncodedVideoPackets(outputOf(success))

      withClue("the copy carried a different packet count") { copiedPackets.size shouldBe sourcePackets.size }
      sourcePackets.zip(copiedPackets).forEachIndexed { index, (expected, actual) ->
        withClue("packet $index differed between the source and the copy") {
          expected.contentEquals(actual) shouldBe true
        }
      }
    }

  // A copy is the one path that carries sound: it takes the source's compressed audio packets
  // across untouched, the same way it does video.
  @Test
  fun untouchedExportCarriesAudioThrough() =
    runTest {
      val bytes = makeClipWithAudio()
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), ExportSpec())

      val audio =
        withClue("the copy produced no readable audio track") {
          decodeAudio(outputOf(success)).shouldNotBeNull()
        }
      withClue("the copy's audio track decoded no samples") { (audio.sampleCount > 0) shouldBe true }
      withClue("the copy's audio track had no duration") { (audio.durationUs > 0) shouldBe true }
    }

  // A trim forces the encode path rather than a copy, which is the one path that decodes, mixes and
  // re-encodes audio rather than carrying compressed packets across untouched.
  @Test
  fun aTranscodedExportCarriesAudibleAudio() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val bytes = makeClipWithAudio(frames = 60, frameRate = 30)
      val composition =
        Composition(
          tracks =
            listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), trim = TimeRange(TRIM_START, TRIM_END))))),
          audio = AudioSpec.Keep,
        )
      val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
      val success = exportOf(composition, MediaSink.Uri(""), spec)

      val audio =
        withClue("the export produced no readable audio track") {
          decodeAudio(outputOf(success)).shouldNotBeNull()
        }
      withClue("the export's audio track had no duration") { (audio.durationUs > 0) shouldBe true }
      withClue("the export's audio peaked at ${audio.peakAmplitude}, expected an audible tone") {
        (audio.peakAmplitude > SILENCE_THRESHOLD) shouldBe true
      }
    }

  // The second-track refusal covers a video-carrying track only, which is what lets a bed reach
  // the mix at all.
  @Test
  fun aMusicBedOnASecondAudioOnlyTrackReachesTheOutput() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val video = makeClip(frames = 30, frameRate = 30)
      val bed = makeClipWithAudio(frames = 30, frameRate = 30)
      val composition =
        Composition(
          tracks =
            listOf(
              Track(listOf(Clip(MediaSource.Bytes(video)))),
              Track(listOf(Clip(MediaSource.Bytes(bed))), content = TrackContent.Audio),
            ),
          audio = AudioSpec.Keep,
        )
      val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
      val success = exportOf(composition, MediaSink.Uri(""), spec)

      val audio =
        withClue("the export produced no readable audio track") {
          decodeAudio(outputOf(success)).shouldNotBeNull()
        }
      withClue("the bed's tone did not reach the output, peak was ${audio.peakAmplitude}") {
        (audio.peakAmplitude > SILENCE_THRESHOLD) shouldBe true
      }
    }

  // Proves ResolvedClip.gain reaches the mix end to end, rather than only in BrowserAudioMixTest's
  // isolated graph.
  @Test
  fun aMutedClipEncodesSilence() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val bytes = makeClipWithAudio(frames = 30, frameRate = 30)
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes)).withAudio(AudioLevel.Mute)))),
          audio = AudioSpec.Keep,
        )
      val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
      val success = exportOf(composition, MediaSink.Uri(""), spec)

      val audio =
        withClue("the export produced no readable audio track") {
          decodeAudio(outputOf(success)).shouldNotBeNull()
        }
      withClue("a muted clip's export peaked at ${audio.peakAmplitude}, expected silence") {
        (audio.peakAmplitude < SILENCE_THRESHOLD) shouldBe true
      }
    }

  // AudioSpec.AudioOnly asks for a file with an audio track and no video track, so the video
  // pipeline never runs and nothing writes a video track to the output.
  @Test
  fun anAudioOnlyExportWritesNoVideoTrack() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val success = exportOf(audioOnlyComposition(), MediaSink.Uri(""), aacSpec())

      withClue("an audio-only export wrote a video track") { hasVideoTrack(outputOf(success)) shouldBe false }
    }

  @Test
  fun anAudioOnlyExportCarriesAudibleAudio() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val success = exportOf(audioOnlyComposition(), MediaSink.Uri(""), aacSpec())

      val audio =
        withClue("the export produced no readable audio track") {
          decodeAudio(outputOf(success)).shouldNotBeNull()
        }
      withClue("the export's audio track decoded no samples") { (audio.sampleCount > 0) shouldBe true }
      withClue("the export's audio peaked at ${audio.peakAmplitude}, expected an audible tone") {
        (audio.peakAmplitude > SILENCE_THRESHOLD) shouldBe true
      }
    }

  @Test
  fun anAudioOnlyExportReportsAnAudioTrackAndNoVideoOne() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val success = exportOf(audioOnlyComposition(), MediaSink.Uri(""), aacSpec())

      withClue("an audio-only export reported a video track") { success.info.video.shouldBeNull() }
      val audio = withClue("an audio-only export reported no audio track") { success.info.audio.shouldNotBeNull() }
      withClue("the reported track ran at ${audio.sampleRate} hertz") { (audio.sampleRate > 0) shouldBe true }
      withClue("the reported track carried ${audio.channelCount} channels") { (audio.channelCount > 0) shouldBe true }

      val drift = (success.info.duration - AUDIO_ONLY_DURATION).absoluteValue
      withClue("the export ran ${success.info.duration}, the composition runs 1s") {
        (drift < DURATION_TOLERANCE) shouldBe true
      }
    }

  // The mirror of the audio-only case. A render that writes video writes the mix into the same
  // file, so the info has to name both tracks rather than only the one the path is named after.
  @Test
  fun anExportCarryingBothTracksReportsBothOfThem() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val bytes = makeClipWithAudio(frames = 30, frameRate = 30)
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), aacSpec())

      withClue("an export carrying both tracks reported no video track") { success.info.video.shouldNotBeNull() }
      val audio =
        withClue("an export carrying both tracks reported no audio track") {
          success.info.audio.shouldNotBeNull()
        }
      withClue("the reported track ran at ${audio.sampleRate} hertz") { (audio.sampleRate > 0) shouldBe true }
      withClue("the reported track carried ${audio.channelCount} channels") { (audio.channelCount > 0) shouldBe true }
    }

  // A trim from the middle of the clip, cut so its audio runs on past the last video sample.
  @Test
  fun anExportWithAudioOutlastingItsVideoReportsTheDurationAProbeOfTheFileReads() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val bytes = makeClipWithAudio(frames = 60, frameRate = 30)
      val composition =
        Composition(
          tracks =
            listOf(
              Track(listOf(Clip(MediaSource.Bytes(bytes), TimeRange(500.milliseconds, 1500.milliseconds)))),
            ),
          audio = AudioSpec.Keep,
        )
      val success = exportOf(composition, MediaSink.Uri(""), aacSpec())
      val output = outputOf(success)
      val lastFrame = decodeFrames(output).last()

      val probed = BrowserProber().probe(output).shouldBeInstanceOf<ProbeResult.Success>().info
      (probed.duration.toDouble(DurationUnit.MICROSECONDS) > lastFrame.timestampUs + lastFrame.durationUs) shouldBe true
      success.info.duration shouldBe probed.duration
      success.info.video
        .shouldNotBeNull()
        .colorSpace shouldBe ColorSpace.Bt709
    }

  @Test
  fun anAudioOnlyExportLandsAsM4a() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val success = exportOf(audioOnlyComposition(), MediaSink.Temporary, aacSpec())

      val output = success.output.shouldBeInstanceOf<MediaSink.Path>()
      withClue("an audio-only export resolved to ${output.path}") { output.path.endsWith(".m4a") shouldBe true }
    }

  // Length and pitch are the contract at a speed. The level is read as RMS because a stretch that overlaps grains may
  // move a single tone's peak, and the other backends do too. What the encoder adds to a file's length is read off the
  // same clip at speed 1, so the comparison is between two trims and not against the encoder.
  @Test
  fun aSpedClipKeepsItsPitchAndLengthThroughAnAudioOnlyExport() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      listOf(1.0 / 32.0 to 500.milliseconds, 0.7 to 1500.milliseconds, 1.5 to 2.seconds, 32.0 to 16.seconds)
        .forEach { (speed, trim) ->
          val bytes = makeClipWithAudio(frames = (trim.toDouble(DurationUnit.SECONDS) * 30).roundToInt() + 3)
          val spec = aacSpec()
          val unity = exportedAudio(bytes, trim, 1.0, AudioSpec.AudioOnly, spec)

          val audio = exportedAudio(bytes, trim, speed, AudioSpec.AudioOnly, spec)

          withClue("at speed $speed") {
            audio.assertLengthAgainst(unity, trim, speed)
            audio.samples.dominantHz(audio.sampleRate) shouldBe (TONE_HZ plusOrMinus DOMINANT_STEP_HZ)
            audio.middleRms().shouldBeWithinPercentageOf(unity.middleRms(), RMS_PERCENT)
          }
        }
    }

  @Test
  fun aSpedClipWritesTheAudioAndFrameCountTheSharedFunctionsName() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      listOf(0.7, 1.5).forEach { speed ->
        val bytes = makeClipWithAudio(frames = 60, frameRate = 30)
        val trim = TRIM_END - TRIM_START
        val spec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)
        val unity = exportedAudio(bytes, trim, 1.0, AudioSpec.Keep, spec, from = TRIM_START)

        val composition = speedComposition(bytes, trim, speed, AudioSpec.Keep, from = TRIM_START)
        val output = outputOf(exportOf(composition, MediaSink.Uri(""), spec))

        val audio = decodeAudioSamples(output)
        withClue("at speed $speed") {
          audio.assertLengthAgainst(unity, trim, speed)
          audio.samples.dominantHz(audio.sampleRate) shouldBe (TONE_HZ plusOrMinus DOMINANT_STEP_HZ)
          val laid = TimeRange.of(Duration.ZERO, outputLengthOf(trim, speed))
          decodeFrames(output).size shouldBe outputSlotsOf(laid, 30).count()
        }
      }
    }

  // The middle of the clip tells a one to one walk from the shared one, where the opening frame would agree.
  @Test
  fun aSpedClipWritesTheSourceFrameTheSharedFunctionsName() =
    runTest {
      listOf(0.7, 1.5).forEach { speed ->
        val bytes = makeClip(width = 64, height = 64, frames = 60, frameRate = 30) { index, _ -> speedRamp(index) }
        val composition =
          Composition(
            tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), speed = speed)))),
            audio = AudioSpec.Remove,
          )

        val frames = decodeFrames(outputOf(exportOf(composition, MediaSink.Uri(""))))

        val middle = frames[frames.size / 2]
        val source = sourceOffsetAt(middle.timestampUs.microseconds, speed).toDouble(DurationUnit.SECONDS)
        val expected = speedRamp((source * 30).roundToInt())
        withClue("at speed $speed the frame at ${middle.timestampUs} drew ${middle.at(0.5, 0.5)} and not $expected") {
          middle.at(x = 0.5, y = 0.5).isNear(expected) shouldBe true
        }
      }
    }

  private fun speedRamp(index: Int): Rgb = Rgb(20 + index * 3, 20 + index, 255 - index * 3)

  private suspend fun exportedAudio(
    bytes: ByteArray,
    trim: Duration,
    speed: Double,
    audio: AudioSpec,
    spec: ExportSpec,
    from: Duration = Duration.ZERO,
  ): DecodedSamples =
    decodeAudioSamples(outputOf(exportOf(speedComposition(bytes, trim, speed, audio, from), MediaSink.Uri(""), spec)))

  private fun speedComposition(
    bytes: ByteArray,
    trim: Duration,
    speed: Double,
    audio: AudioSpec,
    from: Duration = Duration.ZERO,
  ): Composition =
    Composition(
      tracks =
        listOf(
          Track(listOf(Clip(MediaSource.Bytes(bytes), trim = TimeRange(from, from + trim), speed = speed))),
        ),
      audio = audio,
    )

  private fun DecodedSamples.assertLengthAgainst(
    unity: DecodedSamples,
    trim: Duration,
    speed: Double,
  ) {
    val added = unity.durationUs - trim.toDouble(DurationUnit.MICROSECONDS)
    val expected = outputLengthOf(trim, speed).toDouble(DurationUnit.MICROSECONDS) + added
    durationUs shouldBe (expected plusOrMinus AAC_SLACK_US)
  }

  private fun DecodedSamples.middleRms(): Double = samples.rms(samples.size / 4, samples.size * 3 / 4)

  @Test
  fun compositorsAreHandedBack() {
    // A browser keeps around sixteen live WebGL contexts, but headless Chrome evicts the oldest
    // rather than refusing the next, so what this holds is only that the release path runs cleanly
    // however many exports a page makes.
    repeat(CONTEXT_CHURN) { BrowserCompositor.create(64, 64, Fill.Black).release() }
  }

  @Test
  fun cancellingStopsBeforeSuccess() =
    runTest {
      val filmstrip = webCodecsFilmstrip()
      val plan = planOf(filmstrip, compositionOf(MediaSource.Bytes(makeClip(frames = 60))))

      val statuses =
        filmstrip
          .export(plan, MediaSink.Uri(""))
          .takeWhile { it !is ExportStatus.Progress }
          .toList()

      withClue("a cancelled export still finished: ${statuses.map { it::class.simpleName }}") {
        statuses.none { it is ExportStatus.Success } shouldBe true
      }
    }

  /**
   * The engine on its own, for a test that reads what the shared negotiator resolved to rather than
   * only what the facade published. One engine plans, resolves and exports, so all three answers
   * come from the same negotiation.
   */
  private fun browserEngine(): BrowserExportEngine =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    )

  /**
   * Two seconds of a red ramp with a sync sample every three quarters of a second, so a cut at
   * [TRIM_START] lands between two of them and the frame the copy opens on says which.
   */
  private suspend fun rampClip(): ByteArray =
    makeClip(
      frames = SNAPPED_FRAMES,
      frameRate = SNAPPED_FRAME_RATE,
      keyFrameSeconds = SNAPPED_KEY_FRAME_SECONDS,
    ) { index, _ ->
      Rgb(index * RAMP_STEP, 0, 0)
    }

  private fun snappedComposition(
    bytes: ByteArray,
    snapWithin: Duration,
  ): Composition =
    Composition(
      tracks =
        listOf(
          Track(
            listOf(
              Clip(MediaSource.Bytes(bytes), trim = TimeRange(TRIM_START, TRIM_END), snapWithin = snapWithin),
            ),
          ),
        ),
      audio = AudioSpec.Keep,
    )

  private suspend fun resolvedClipOf(
    engine: BrowserExportEngine,
    composition: Composition,
  ): ResolvedClip =
    engine
      .resolve(composition, ExportSpec())
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition.tracks
      .first()
      .clips
      .single()

  private suspend fun planWith(
    engine: BrowserExportEngine,
    composition: Composition,
    spec: ExportSpec = ExportSpec(),
  ): ExportPlan =
    when (val verdict = engine.plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
    }

  private suspend fun exportWith(
    engine: BrowserExportEngine,
    plan: ExportPlan,
  ): ExportStatus.Success {
    val statuses = engine.export(plan, MediaSink.Uri("")).toList()
    val failure = statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()
    if (failure != null) throw AssertionError("the export failed: ${failure.error.message}")
    return withClue("statuses were ${statuses.map { it::class.simpleName }}") {
      (statuses.filterIsInstance<ExportStatus.Success>().singleOrNull()).shouldNotBeNull()
    }
  }

  /**
   * Which frame of the ramp fixture presents at [at].
   */
  private fun frameIndexAt(at: Duration): Int = (at.toDouble(DurationUnit.SECONDS) * SNAPPED_FRAME_RATE).roundToInt()

  /**
   * The whole ramp fixture after one more trip through the encoder, which is what a frame the export
   * re-encoded has to be matched against.
   *
   * Every trip through the encoder can move a color by a level or two, so a re-encoded frame sits
   * one trip further from the painted value than the fixture does. Naming a frame rate sends the
   * whole clip through the encoder without dropping or moving a frame.
   */
  private suspend fun reencodedRampOf(
    engine: BrowserExportEngine,
    bytes: ByteArray,
  ): Ramp {
    val spec = ExportSpec(bitrate = MEASURING_BITRATE, frameRate = SNAPPED_FRAME_RATE)
    val plan = planWith(engine, compositionOf(MediaSource.Bytes(bytes)), spec)
    withClue("the reference ramp was meant to be re-encoded") { plan.path shouldBe ExportPath.Transcode }
    return rampOf(outputOf(exportWith(engine, plan)))
  }

  /**
   * A ramp fixture decoded back, so an exported frame is matched against the source through the
   * same color round trip rather than against the values it was painted with.
   */
  private suspend fun rampOf(source: MediaSource): Ramp = Ramp(decodeFrames(source).map { it.at(x = 0.5, y = 0.5).red })

  private fun List<OutputFrame>.nearestTo(timestampUs: Double): OutputFrame =
    minBy { abs(it.timestampUs - timestampUs) }

  private fun micros(duration: Duration): Double = duration.toDouble(DurationUnit.MICROSECONDS)

  private fun compositionOf(source: MediaSource): Composition =
    Composition(
      tracks = listOf(Track(listOf(Clip(source)))),
      audio = AudioSpec.Remove,
    )

  /**
   * The same two-clip letterbox fixture as [letterboxBarsAreBlackAndTheClipIsNotCropped], with a
   * caller-chosen fill so the bars can be asserted against something other than the default black,
   * and an optional composition-level effect chain.
   */
  private suspend fun letterboxComposition(
    fill: Fill,
    effects: List<EffectSpec> = emptyList(),
  ): Composition {
    val filler = makeClip(color = Rgb.Green)
    val wide = makeClip(width = 64, height = 32) { _, y -> if (y < WIDE_RED_ROWS) Rgb.Red else Rgb.Blue }
    return Composition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(filler)), Clip(MediaSource.Bytes(wide))))),
      effects = effects,
      audio = AudioSpec.Remove,
      fill = fill,
    )
  }

  /**
   * One second of video with a tone under it, asked for as audio and nothing else.
   */
  private suspend fun audioOnlyComposition(): Composition {
    val bytes = makeClipWithAudio(frames = 30, frameRate = 30)
    return Composition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))))),
      audio = AudioSpec.AudioOnly,
    )
  }

  private fun aacSpec(): ExportSpec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac)

  private companion object {
    const val TONE_HZ = 440.0
    const val RMS_PERCENT = 20.0

    // AAC writes whole frames of 1024 samples, so two lengths can land one of them apart at 48kHz either way.
    const val AAC_SLACK_US = 22_000.0
    const val HALF = 32
    const val HALF_BRIGHTNESS = 0.5f
    const val STRETCHED_CONTRAST = 1.5f

    // One channel under mid grey, one over it and one on it, so a contrast has to push two apart
    // and leave the third where it was.
    val PIVOT_SPREAD = Rgb(80, 200, 128)
    const val WIDE_RED_ROWS = 8
    const val FLAT_TOLERANCE = 20
    const val HALF_DIM_TOLERANCE = 24
    const val PURPLE_ARGB = 0xFFA060C8.toInt()
    val PURPLE_RGB = Rgb(0xA0, 0x60, 0xC8)

    // Both starts sit ahead of a clip a second long at thirty frames a second. The first is on the
    // frame grid and the second falls between two slots.
    val ON_GRID_START = 600.milliseconds
    val OFF_GRID_START = 510.milliseconds
    const val LATE_CLIP_FRAMES = 30
    const val LATE_FRAME_RATE = 30

    // A slot runs forty milliseconds at twenty five frames a second. A start of twenty is exactly half
    // of one, and a clip of 1.010 or 1.030 seconds ends a quarter of a slot either side of half way.
    const val GRID_FRAME_RATE = 25
    const val GRID_CLIP_FRAMES = 50
    val HALF_SLOT_START = 20.milliseconds
    val OFF_GRID_LENGTHS = listOf(1_010.milliseconds, 1_030.milliseconds)

    // Twenty six frames at twenty five a second run to 1.040 seconds, and a trim of 1.030 ends inside
    // the last of them.
    const val PAST_END_FRAMES = 26
    val PAST_END_TRIM = 1_030.milliseconds

    // Three seconds at twenty five frames a second, under a primary of one.
    const val BED_FRAMES = 75

    // Every held slot goes through the encoder again, so the same picture comes back a level or two apart from one
    // frame to the next.
    const val HELD_TOLERANCE = 8

    // The encoder takes seconds and the container writes them on its own timescale, a decoded frame
    // carries whole microseconds, and the shared grid reads a slot in whole microseconds rounded down,
    // so a written time comes back a hair off the slot it was handed.
    const val TIMESTAMP_TOLERANCE_US = 1.0
    const val RATE_TOLERANCE = 2f
    const val RAMP_STEP = 4
    val TRIM_START = 1000.milliseconds
    val TRIM_END = 1500.milliseconds
    const val TRIM_START_FRAME = 30

    // Wide enough to reach the sync sample before the cut whichever frame the encoder rounded it
    // onto. Which one it actually reached is read off the plan, never off this.
    val SNAP_WITHIN = 1.seconds

    // Two seconds at thirty frames a second with a sync sample every three quarters of one, so the
    // trim above opens between two of them. A cut that already sits on a sync sample snaps nowhere
    // and would pass whether the snap works or not.
    const val SNAPPED_FRAMES = 60
    const val SNAPPED_FRAME_RATE = 30
    const val SNAPPED_KEY_FRAME_SECONDS = 0.75

    // A copy carries whole packets, and an audio packet is a few milliseconds either way of where
    // a frame boundary falls.
    const val TRACK_SKEW_TOLERANCE_US = 60_000.0
    const val CONTEXT_CHURN = 24
    const val SILENCE_THRESHOLD = 0.01f
    val AUDIO_ONLY_DURATION = 1.seconds

    // An AAC encoder pads the last frame out to its own block size, so the file runs a little past
    // the composition however exact the mix was.
    val DURATION_TOLERANCE = 250.milliseconds
  }
}

/**
 * The red channel of every frame of the ramp fixture, decoded back.
 *
 * A frame is named by whichever of these its color sits closest to, so a shift the color
 * conversion applies to the export applies to the reference too and cancels. That only holds while
 * both have been through the encoder the same number of times.
 */
private class Ramp(
  private val reds: List<Int>,
) {
  fun frameOf(color: Rgb): Int = reds.indices.minBy { abs(reds[it] - color.red) }
}
