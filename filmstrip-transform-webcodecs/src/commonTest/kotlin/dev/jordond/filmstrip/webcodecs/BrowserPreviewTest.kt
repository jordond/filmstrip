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
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.videoSlots
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PREVIEW_LOOK_AHEAD
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the browser preview draws, and what it does with the frames it decoded to draw it.
 *
 * Two things are worth proving here and nowhere else. The preview lands on the frame the export
 * writes for the same composition time, which is the whole parity claim on this backend. And every
 * decoded frame is closed exactly once, because a `VideoFrame` is not promptly collected and a
 * preview holds them across event-loop turns where the export never does.
 */
class BrowserPreviewTest {
  @Test
  fun aFrameIsTheOneTheExportWritesAtTheSameTime() =
    runTest {
      val source = MediaSource.Bytes(rampClip())
      val composition = compositionOf(source, Brightness(BRIGHT))
      val preview = previewOf(composition)

      try {
        for (index in PROBE_FRAMES) {
          val frame =
            withClue("no preview frame at frame $index") {
              preview.frameAt(frameTime(index)).shouldNotBeNull()
            }
          frame.size shouldBe Size(WIDTH, HEIGHT)
          frame.presentationTime shouldBe frameTime(index)

          val drawn = frame.at(x = 0.5, y = 0.5)
          val expected = ramp(index).graded(Brightness(BRIGHT))
          withClue("frame $index drew $drawn where the export writes $expected") {
            drawn.isNear(expected) shouldBe true
          }
        }
      } finally {
        preview.release()
      }
    }

  // A pass whose uniforms vary with time reads the slot's composition time at the draw, so the draw
  // has to be told where the slot sits rather than deriving it again. The gap and the clip are both
  // read, since they reach the compositor through different entry points.
  @Test
  fun everyDrawnSlotTellsTheCompositorWhereItSits() =
    runTest {
      val preview = previewOf(lateComposition(Fill.Black))

      try {
        val filled = withClue("no preview frame in the gap") { preview.frameAt(frameTime(GAP_PROBE)).shouldNotBeNull() }
        preview.drawnAtUs?.microseconds shouldBe filled.presentationTime

        val drawn =
          withClue("no preview frame in the clip") {
            preview.frameAt(frameTime(LATE_SLOTS + CLIP_PROBE)).shouldNotBeNull()
          }
        preview.drawnAtUs?.microseconds shouldBe drawn.presentationTime
      } finally {
        preview.release()
      }
    }

  @Test
  fun aReadBackFrameLeavesTheLookAheadWhereItWas() =
    runTest {
      val preview = previewOf(compositionOf(MediaSource.Bytes(rampClip())))

      try {
        preview.fillAhead(Duration.ZERO)
        val held = preview.buffered
        withClue("the look-ahead decoded nothing") { (held > 0) shouldBe true }

        // Far enough from the playhead that the window cannot answer, which is the case that has to
        // reach a decoder of its own rather than throw away what playback is holding.
        preview.frameAt(frameTime(FRAMES - 1)).shouldNotBeNull()

        preview.buffered shouldBe held
      } finally {
        preview.release()
      }
    }

  @Test
  fun theLookAheadIsBoundedByFrameCount() =
    runTest {
      val preview = previewOf(compositionOf(MediaSource.Bytes(rampClip())))

      try {
        preview.fillAhead(Duration.ZERO)

        withClue("the fixture is too short to reach the bound") { (FRAMES > PREVIEW_LOOK_AHEAD) shouldBe true }
        preview.buffered shouldBe PREVIEW_LOOK_AHEAD
      } finally {
        preview.release()
      }
    }

  @Test
  fun everyDecodedFrameIsClosedOnAFlush() =
    runTest {
      val preview = previewOf(compositionOf(MediaSource.Bytes(rampClip())))

      try {
        preview.fillAhead(Duration.ZERO)
        withClue("the look-ahead decoded nothing") { (preview.openedFrames > 0) shouldBe true }

        preview.flush()

        preview.buffered shouldBe 0
        preview.closedFrames shouldBe preview.openedFrames
      } finally {
        preview.release()
      }
    }

  @Test
  fun everyDecodedFrameIsClosedOnRelease() =
    runTest {
      val preview = previewOf(compositionOf(MediaSource.Bytes(rampClip())))

      preview.fillAhead(Duration.ZERO)
      preview.fillAhead(frameTime(PROBE_FRAMES.last()))
      withClue("the look-ahead decoded nothing") { (preview.openedFrames > 0) shouldBe true }

      preview.release()

      preview.buffered shouldBe 0
      preview.closedFrames shouldBe preview.openedFrames
      withClue("a released preview still drew a frame") { preview.frameAt(Duration.ZERO).shouldBeNull() }
    }

  // The pump decodes ahead while a release can land at any moment, so a release arriving mid-decode
  // has to wait for the decode rather than close the decoder out from under it.
  @Test
  fun aReleaseStagedDuringAFillClosesEveryFrame() =
    runTest {
      val preview = previewOf(compositionOf(MediaSource.Bytes(rampClip())))

      val filling = launch { preview.fillAhead(Duration.ZERO) }
      yield()
      preview.release()
      filling.join()

      withClue("the look-ahead decoded nothing") { (preview.openedFrames > 0) shouldBe true }
      preview.buffered shouldBe 0
      preview.closedFrames shouldBe preview.openedFrames
      withClue("a released preview still drew a frame") { preview.frameAt(Duration.ZERO).shouldBeNull() }
    }

  // A seek flushes on the same footing, and lands on a window the in-flight decode is still filling.
  @Test
  fun aFlushStagedDuringAFillClosesEveryFrame() =
    runTest {
      val preview = previewOf(compositionOf(MediaSource.Bytes(rampClip())))

      try {
        val filling = launch { preview.fillAhead(Duration.ZERO) }
        yield()
        preview.flush()
        filling.join()

        withClue("the look-ahead decoded nothing") { (preview.openedFrames > 0) shouldBe true }
        preview.buffered shouldBe 0
        preview.closedFrames shouldBe preview.openedFrames
      } finally {
        preview.release()
      }
    }

  @Test
  fun aParameterSwapChangesThePixelsAndKeepsTheDecodedFrames() =
    runTest {
      val source = MediaSource.Bytes(rampClip())
      val dim = compositionOf(source, Brightness(DIM))
      val bright = compositionOf(source, Brightness(BRIGHT))
      val preview = previewOf(dim)

      try {
        preview.fillAhead(Duration.ZERO)
        val before = preview.frameAt(frameTime(PROBE_FRAMES.first())).shouldNotBeNull()
        val held = preview.buffered

        withClue("the swap was refused") { preview.updateParameters(resolve(bright), bright) shouldBe true }

        val after = preview.frameAt(frameTime(PROBE_FRAMES.first())).shouldNotBeNull()
        preview.buffered shouldBe held
        withClue("the grade change reached no pixel") {
          (after.at(x = 0.5, y = 0.5).red > before.at(x = 0.5, y = 0.5).red) shouldBe true
        }
      } finally {
        preview.release()
      }
    }

  // The export writes the fill until a late primary starts, so the preview draws it there too, and
  // a frame after the start is the one that far into the clip rather than that far into the
  // composition. Both are read in the middle of their run.
  @Test
  fun aPrimaryThatStartsLateShowsTheFillUntilItDoes() =
    runTest {
      val preview = previewOf(lateComposition(Fill.Solid(PURPLE_ARGB)))

      try {
        val gap = frameTime(GAP_PROBE)
        val filled = withClue("no preview frame in the gap") { preview.frameAt(gap).shouldNotBeNull() }
        filled.presentationTime shouldBe gap
        withClue("the gap drew ${filled.at(0.5, 0.5)}") { filled.at(x = 0.5, y = 0.5).isNear(PURPLE_RGB) shouldBe true }

        // Nothing is decoded in the gap, so a relaxed seek stays put and the look-ahead reads the
        // clip that comes next.
        preview.syncSampleAt(gap) shouldBe gap
        preview.fillAhead(gap)
        withClue("the look-ahead decoded nothing ahead of the clip") { (preview.buffered > 0) shouldBe true }

        val into = frameTime(LATE_SLOTS + CLIP_PROBE)
        val drawn = withClue("no preview frame after the start") { preview.frameAt(into).shouldNotBeNull() }
        drawn.presentationTime shouldBe into
        withClue("the clip drew ${drawn.at(0.5, 0.5)} where its frame $CLIP_PROBE is ${ramp(CLIP_PROBE)}") {
          drawn.at(x = 0.5, y = 0.5).isNear(ramp(CLIP_PROBE)) shouldBe true
        }
      } finally {
        preview.release()
      }
    }

  // A start between two slots leaves the gap the export writes, fifteen slots here, so the grid slot
  // just short of the start is already the clip's opening frame, and every frame of the clip reports
  // the slot the export writes it on rather than the time the clip was laid at. A relaxed seek into
  // the clip lands on that same opening slot rather than on the fill in front of it.
  @Test
  fun aPrimaryThatStartsBetweenTwoSlotsOpensWhereTheExportDoes() =
    runTest {
      val preview = previewOf(lateComposition(Fill.Solid(PURPLE_ARGB), start = OFF_GRID_START))
      val lead = outputSlotOf(OFF_GRID_START, FRAME_RATE)

      try {
        val filled = withClue("no preview frame in the gap") { preview.frameAt(frameTime(GAP_PROBE)).shouldNotBeNull() }
        withClue("the gap drew ${filled.at(0.5, 0.5)}") { filled.at(x = 0.5, y = 0.5).isNear(PURPLE_RGB) shouldBe true }

        // Ten milliseconds short of the start and a whole slot past the last gap frame.
        val shortOfStart = outputSlotTime(lead, FRAME_RATE)
        withClue("the probe at $shortOfStart is not short of $OFF_GRID_START") {
          (shortOfStart < OFF_GRID_START) shouldBe true
        }
        val opening = withClue("no preview frame at $shortOfStart") { preview.frameAt(shortOfStart).shouldNotBeNull() }
        opening.presentationTime shouldBe shortOfStart
        withClue("$shortOfStart drew ${opening.at(0.5, 0.5)} where the clip opens on ${ramp(0)}") {
          opening.at(x = 0.5, y = 0.5).isNear(ramp(0)) shouldBe true
        }
        // The clip's own sync sample sits after this position, so a relaxed seek has nowhere earlier
        // to go and stays put.
        val shortSeek = preview.syncSampleAt(shortOfStart)
        withClue("a relaxed seek to $shortOfStart moved forward to $shortSeek") {
          (shortSeek <= shortOfStart) shouldBe true
        }

        val inClip = OFF_GRID_START + frameTime(CLIP_PROBE)
        val onGrid = outputSlotOf(inClip, FRAME_RATE)
        val drawn = withClue("no preview frame at $inClip") { preview.frameAt(inClip).shouldNotBeNull() }
        drawn.presentationTime shouldBe outputSlotTime(onGrid, FRAME_RATE)
        val into = (onGrid - lead).toInt()
        withClue("$inClip drew ${drawn.at(0.5, 0.5)} where the clip's frame $into is ${ramp(into)}") {
          drawn.at(x = 0.5, y = 0.5).isNear(ramp(into)) shouldBe true
        }

        val seek = preview.syncSampleAt(inClip)
        withClue("a relaxed seek to $inClip missed the slot the clip opens on") {
          seek shouldBe opening.presentationTime
        }
        val landed = withClue("no preview frame at $seek") { preview.frameAt(seek).shouldNotBeNull() }
        withClue("a relaxed seek landed on $seek and drew ${landed.at(0.5, 0.5)} where the clip opens on ${ramp(0)}") {
          landed.at(x = 0.5, y = 0.5).isNear(ramp(0)) shouldBe true
        }
      } finally {
        preview.release()
      }
    }

  // A first clip trimmed between two slots gives up the slot its end rounds past, so the frame just
  // after its end is already the next clip's first. Both frames either side of the seam report the
  // shared grid's own time for their slot, and the one before it sits between two whole microseconds.
  @Test
  fun aSeamBetweenTwoSlotsDrawsTheNextClipOnTheSlotTheGridGivesIt() =
    runTest {
      val first =
        makeClip(
          width = WIDTH,
          height = HEIGHT,
          frames = SEAM_SOURCE_FRAMES,
          frameRate = FRAME_RATE,
          color = Rgb.Green,
        )
      val composition =
        EditComposition(
          tracks =
            listOf(
              Track(
                listOf(
                  Clip(MediaSource.Bytes(first), trim = TimeRange(Duration.ZERO, OFF_GRID_SEAM)),
                  Clip(MediaSource.Bytes(rampClip())),
                ),
              ),
            ),
          audio = AudioSpec.Remove,
        )
      val preview = previewOf(composition)
      val seam = outputSlotOf(OFF_GRID_SEAM, FRAME_RATE)

      try {
        val opening =
          withClue("no preview frame at $OFF_GRID_SEAM") {
            preview.frameAt(OFF_GRID_SEAM).shouldNotBeNull()
          }
        opening.presentationTime shouldBe outputSlotTime(seam, FRAME_RATE)
        withClue("$OFF_GRID_SEAM drew ${opening.at(0.5, 0.5)} where the second clip opens on ${ramp(0)}") {
          opening.at(x = 0.5, y = 0.5).isNear(ramp(0)) shouldBe true
        }

        val lastOfFirst = outputSlotTime(seam - 1, FRAME_RATE)
        val closing = withClue("no preview frame at $lastOfFirst") { preview.frameAt(lastOfFirst).shouldNotBeNull() }
        closing.presentationTime shouldBe lastOfFirst
        withClue("$lastOfFirst drew ${closing.at(0.5, 0.5)} where the first clip is ${Rgb.Green}") {
          closing.at(x = 0.5, y = 0.5).isNear(Rgb.Green) shouldBe true
        }
      } finally {
        preview.release()
      }
    }

  // A sync sample on a whole second is a grid slot the preview can draw, and the floor that puts a
  // relaxed seek back on the grid used to land a slot short of it, because a slot at thirty frames
  // a second is not a whole number of microseconds.
  @Test
  fun aRelaxedSeekLandsOnASyncSampleThatSitsOnAWholeSecond() =
    runTest {
      val preview = previewOf(compositionOf(MediaSource.Bytes(secondKeyFrameClip())))

      try {
        val opening = frameTime(FRAME_RATE)
        val seek = preview.syncSampleAt(opening + frameTime(CLIP_PROBE))

        seek shouldBe opening
      } finally {
        preview.release()
      }
    }

  // A trim between two source frames puts the sync sample on the second between two output slots. A
  // relaxed seek lands on the slot the grid puts that sample on, which is the slot that draws it,
  // rather than the one before it, which draws the last frame of the group in front.
  @Test
  fun aRelaxedSeekLandsOnTheSlotTheGridPutsItsSyncSampleOn() =
    runTest {
      val trimmed = Clip(MediaSource.Bytes(secondKeyFrameClip()), trim = TimeRange.from(OFF_FRAME_TRIM))
      val preview = previewOf(EditComposition(tracks = listOf(Track(listOf(trimmed))), audio = AudioSpec.Remove))

      try {
        val laid =
          preview.resolved.tracks
            .first()
            .clips
            .single()
        val keyAt = laid.span.start + frameTime(FRAME_RATE) - OFF_FRAME_TRIM
        val position = laid.span.start + frameTime(PAST_THE_KEY)

        val seek = preview.syncSampleAt(position)

        seek shouldBe outputSlotTime(outputSlotOf(keyAt, FRAME_RATE), FRAME_RATE)
      } finally {
        preview.release()
      }
    }

  // A clip trimmed past its own sync sample decodes from one that belongs in front of its trim. A
  // relaxed seek into it settles on the slot the clip opens on rather than reaching back into the clip
  // before it.
  @Test
  fun aRelaxedSeekIntoAClipTrimmedPastItsSyncSampleStaysInThatClip() =
    runTest {
      val trimmed = Clip(MediaSource.Bytes(secondKeyFrameClip()), trim = TimeRange.from(frameTime(MID_GOP_FRAME)))
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(rampClip())), trimmed))),
          audio = AudioSpec.Remove,
        )
      val preview = previewOf(composition)

      try {
        val laid =
          preview.resolved.tracks
            .first()
            .clips
            .last()
        val opens = outputSlotTime(outputSlotOf(laid.span.start, FRAME_RATE), FRAME_RATE)
        val inClip = laid.span.start + frameTime(GAP_PROBE)

        val seek = preview.syncSampleAt(inClip)

        withClue("a relaxed seek to $inClip left the clip that opens at $opens") { seek shouldBe opens }
        val landed = withClue("no preview frame at $seek") { preview.frameAt(seek).shouldNotBeNull() }
        withClue(
          "a relaxed seek landed on $seek and drew ${landed.at(0.5, 0.5)} where the clip opens on " +
            "${keyed(MID_GOP_FRAME)}",
        ) {
          landed.at(x = 0.5, y = 0.5).isNear(keyed(MID_GOP_FRAME)) shouldBe true
        }
      } finally {
        preview.release()
      }
    }

  // A blurred fill has nothing to blur in the gap, so the gap is its plain black even straight after
  // a letterboxed frame ran the background passes through the same compositor.
  @Test
  fun aBlurredFillLeavesTheGapBlack() =
    runTest {
      val filler = makeClip(width = WIDTH, height = HEIGHT, frames = SHORT_FRAMES, frameRate = FRAME_RATE)
      val wide =
        makeClip(width = WIDTH, height = HEIGHT / 2, frames = SHORT_FRAMES, frameRate = FRAME_RATE, color = Rgb.Red)
      val preview = previewOf(lateComposition(Fill.Blur, clips = listOf(filler, wide)))

      try {
        val letterboxed = preview.frameAt(frameTime(LATE_SLOTS + SHORT_FRAMES + WIDE_PROBE)).shouldNotBeNull()
        val bar = letterboxed.at(x = 0.5, y = BAR)
        withClue("the bar read $bar, so the background passes never ran") { !bar.isNear(Rgb.Black) shouldBe true }

        val filled = preview.frameAt(frameTime(GAP_PROBE)).shouldNotBeNull()
        withClue("the gap drew ${filled.at(0.5, 0.5)}") { filled.at(x = 0.5, y = 0.5).isNear(Rgb.Black) shouldBe true }
      } finally {
        preview.release()
      }
    }

  // A bed that outlasts the primary leaves a tail the export holds the primary's last frame over. A position in its
  // middle draws that frame at the last covered slot's time, as the export does, and reports the slot it was asked
  // for, which is where the export writes it. The playhead sitting on the duration reports the last slot the file has.
  @Test
  fun aSlotInTheHeldTailDrawsThePrimarysLastFrameWhereTheExportWritesIt() =
    runTest {
      val preview = previewOf(heldTailComposition())

      try {
        val tail = preview.resolved.heldTail
        withClue("a bed of $BED_FRAMES frames under a shorter primary left no tail") { tail.isEmpty() shouldBe false }
        val lastCovered = outputSlotTime(tail.first - 1, FRAME_RATE)
        val middle = outputSlotTime((tail.first + tail.last) / 2, FRAME_RATE)

        val held = withClue("no preview frame at $middle") { preview.frameAt(middle).shouldNotBeNull() }
        held.presentationTime shouldBe middle
        preview.drawnAtUs?.microseconds shouldBe lastCovered
        withClue("$middle drew ${held.at(0.5, 0.5)} where the primary closes on ${ramp(FRAMES - 1)}") {
          held.at(x = 0.5, y = 0.5).isNear(ramp(FRAMES - 1)) shouldBe true
        }

        val end =
          withClue("no preview frame at the duration") {
            preview.frameAt(preview.resolved.duration).shouldNotBeNull()
          }
        end.presentationTime shouldBe outputSlotTime(preview.resolved.videoSlots - 1, FRAME_RATE)
      } finally {
        preview.release()
      }
    }

  // Every slot of the held tail draws the same held frame, so a relaxed seek into it has nothing to decode and lands on
  // the slot it was asked for. Either side of a slot in the middle, the tail's first slot and the duration are read.
  @Test
  fun aRelaxedSeekIntoTheHeldTailLandsOnItsOwnSlot() =
    runTest {
      val preview = previewOf(heldTailComposition())

      try {
        val tail = preview.resolved.heldTail
        withClue("a bed of $BED_FRAMES frames under a shorter primary left no tail") { tail.isEmpty() shouldBe false }
        val middle = (tail.first + tail.last) / 2
        val third = frameTime(1) / 3

        val roundsDown = outputSlotTime(middle, FRAME_RATE) + third
        val down = preview.syncSampleAt(roundsDown)
        withClue("a relaxed seek to $roundsDown left slot $middle") { down shouldBe outputSlotTime(middle, FRAME_RATE) }
        val landed = withClue("no preview frame at $down") { preview.frameAt(down).shouldNotBeNull() }
        landed.presentationTime shouldBe outputSlotTime(middle, FRAME_RATE)

        val roundsUp = outputSlotTime(middle, FRAME_RATE) - third
        withClue("the probe at $roundsUp is not on slot $middle") { outputSlotOf(roundsUp, FRAME_RATE) shouldBe middle }
        val up = preview.syncSampleAt(roundsUp)
        withClue("a relaxed seek to $roundsUp moved forward to $up") { (up <= roundsUp) shouldBe true }
        withClue("a relaxed seek to $roundsUp left slot $middle") { outputSlotOf(up, FRAME_RATE) shouldBe middle }

        val opening = outputSlotTime(tail.first, FRAME_RATE)
        withClue("a relaxed seek to the tail's first slot moved") { preview.syncSampleAt(opening) shouldBe opening }

        val end = preview.syncSampleAt(preview.resolved.duration)
        withClue("a relaxed seek to the duration left the tail") { end shouldBe outputSlotTime(tail.last, FRAME_RATE) }
      } finally {
        preview.release()
      }
    }

  // The primary's last covered slot is a decoded frame like any other, so a relaxed seek there still goes back to the
  // sync sample the ramp opens on, however long the tail after it runs.
  @Test
  fun aRelaxedSeekToThePrimarysLastCoveredSlotLandsOnItsSyncSample() =
    runTest {
      val preview = previewOf(heldTailComposition())

      try {
        val tail = preview.resolved.heldTail
        withClue("a bed of $BED_FRAMES frames under a shorter primary left no tail") { tail.isEmpty() shouldBe false }
        val laid =
          preview.resolved.tracks
            .first()
            .clips
            .single()
        val opens = outputSlotTime(outputSlotOf(laid.span.start, FRAME_RATE), FRAME_RATE)
        val lastCovered = outputSlotTime(tail.first - 1, FRAME_RATE)

        withClue("a relaxed seek to $lastCovered missed the sync sample") {
          preview.syncSampleAt(lastCovered) shouldBe opens
        }
      } finally {
        preview.release()
      }
    }

  private suspend fun heldTailComposition(): EditComposition {
    val bed = makeClip(width = WIDTH, height = HEIGHT, frames = BED_FRAMES, frameRate = FRAME_RATE)
    return EditComposition(
      tracks =
        listOf(
          Track(listOf(Clip(MediaSource.Bytes(rampClip())))),
          Track(listOf(Clip(MediaSource.Bytes(bed))), content = TrackContent.Audio),
        ),
      audio = AudioSpec.Remove,
    )
  }

  private suspend fun lateComposition(
    fill: Fill,
    start: Duration = frameTime(LATE_SLOTS),
    clips: List<ByteArray>? = null,
  ): EditComposition =
    EditComposition(
      tracks = listOf(Track((clips ?: listOf(rampClip())).map { Clip(MediaSource.Bytes(it)) }, start = start)),
      audio = AudioSpec.Remove,
      fill = fill,
    )

  private suspend fun previewOf(composition: EditComposition): BrowserPreview =
    resolve(composition).toBrowserPreview(composition)

  private suspend fun resolve(composition: EditComposition) =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    ).resolve(
      composition,
      ExportSpec(videoCodec = VideoCodec.H264),
    ).shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition

  private fun compositionOf(
    source: MediaSource,
    vararg effects: EffectSpec,
  ): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(source)))),
      effects = effects.toList(),
      audio = AudioSpec.Remove,
    )

  /**
   * A clip whose every frame is a different flat color, so a frame drawn for the wrong time is a
   * different color rather than an indistinguishable one.
   */
  private suspend fun rampClip(): ByteArray =
    makeClip(width = WIDTH, height = HEIGHT, frames = FRAMES, frameRate = FRAME_RATE) { index, _ -> ramp(index) }

  /**
   * A clip long enough to carry the sync sample the fixtures write every second, painted in a step
   * of its own so a frame past that second is still a color a channel can hold.
   */
  private suspend fun secondKeyFrameClip(): ByteArray =
    makeClip(width = WIDTH, height = HEIGHT, frames = KEY_FRAME_FRAMES, frameRate = FRAME_RATE) { index, _ ->
      keyed(index)
    }

  private fun frameTime(index: Int): Duration = (index * MILLIS_PER_SECOND / FRAME_RATE).milliseconds

  private companion object {
    const val WIDTH = 64
    const val HEIGHT = 64
    const val FRAME_RATE = 30
    const val FRAMES = 30
    const val MILLIS_PER_SECOND = 1_000

    const val DIM = 0.4f
    const val BRIGHT = 1.6f

    // One inside the look-ahead, one well past it, so both the buffered path and the sampler path
    // are compared against the export rather than only whichever the window happened to serve.
    val PROBE_FRAMES = listOf(3, 21)

    // A late primary starts eighteen slots in, and each run is probed at its middle.
    const val LATE_SLOTS = 18
    const val GAP_PROBE = 9
    const val CLIP_PROBE = 15

    // Fifteen slots and three tenths of another.
    val OFF_GRID_START = 510.milliseconds

    // Thirty slots and three tenths of another, cut from a clip long enough to carry it.
    val OFF_GRID_SEAM = 1_010.milliseconds
    const val SEAM_SOURCE_FRAMES = 45

    // Two short clips, the second half as tall as the output, probed in its middle and in its top bar.
    const val SHORT_FRAMES = 12

    // Two seconds, under the one second primary the ramp fixture is.
    const val BED_FRAMES = 60
    const val WIDE_PROBE = 6
    const val BAR = 0.1

    const val PURPLE_ARGB = 0xFFA060C8.toInt()
    val PURPLE_RGB = Rgb(0xA0, 0x60, 0xC8)

    // Half a second past the sync sample the fixtures write on the second, so a seek has somewhere
    // to come back from.
    const val KEY_FRAME_FRAMES = 45

    // Half way between the two sync samples that clip carries.
    const val MID_GOP_FRAME = 15

    // Between that clip's frames at fifteen and sixteen, so its sync sample on the second lands
    // between two output slots once the trim is taken off, and a slot a little past that sample.
    val OFF_FRAME_TRIM = 510.milliseconds
    const val PAST_THE_KEY = 18

    const val RAMP_STEP = 6
    const val RAMP_BASE = 20
    const val MAX_CHANNEL = 255

    fun ramp(index: Int): Rgb = Rgb(RAMP_BASE + index * RAMP_STEP, RAMP_BASE + index, MAX_CHANNEL - index * RAMP_STEP)

    fun keyed(index: Int): Rgb = Rgb(RAMP_BASE + index, RAMP_BASE + index, MAX_CHANNEL - index)
  }
}

/**
 * The color at a point given as fractions of the frame, measured from the top left.
 */
private fun PreviewFrame.at(
  x: Double,
  y: Double,
): Rgb {
  val column = (x * size.width).toInt().coerceIn(0, size.width - 1)
  val row = (y * size.height).toInt().coerceIn(0, size.height - 1)
  val offset = (row * size.width + column) * RGBA_CHANNELS
  return Rgb(
    pixels[offset].toInt() and BYTE_MASK,
    pixels[offset + 1].toInt() and BYTE_MASK,
    pixels[offset + 2].toInt() and BYTE_MASK,
  )
}

private const val RGBA_CHANNELS = 4
private const val BYTE_MASK = 0xFF
