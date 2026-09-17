package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.PREVIEW_LOOK_AHEAD
import dev.jordond.filmstrip.webcodecs.internal.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.toBrowserPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
          val frame = assertNotNull(preview.frameAt(frameTime(index)), "no preview frame at frame $index")
          assertEquals(Size(WIDTH, HEIGHT), frame.size)
          assertEquals(frameTime(index), frame.presentationTime)

          val drawn = frame.at(x = 0.5, y = 0.5)
          val expected = ramp(index).graded(Brightness(BRIGHT))
          assertTrue(drawn.isNear(expected), "frame $index drew $drawn where the export writes $expected")
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
        val filled = assertNotNull(preview.frameAt(frameTime(GAP_PROBE)), "no preview frame in the gap")
        assertEquals(filled.presentationTime, preview.drawnAtUs?.microseconds)

        val drawn = assertNotNull(preview.frameAt(frameTime(LATE_SLOTS + CLIP_PROBE)), "no preview frame in the clip")
        assertEquals(drawn.presentationTime, preview.drawnAtUs?.microseconds)
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
        assertTrue(held > 0, "the look-ahead decoded nothing")

        // Far enough from the playhead that the window cannot answer, which is the case that has to
        // reach a decoder of its own rather than throw away what playback is holding.
        assertNotNull(preview.frameAt(frameTime(FRAMES - 1)))

        assertEquals(held, preview.buffered)
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

        assertTrue(FRAMES > PREVIEW_LOOK_AHEAD, "the fixture is too short to reach the bound")
        assertEquals(PREVIEW_LOOK_AHEAD, preview.buffered)
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
        assertTrue(preview.openedFrames > 0, "the look-ahead decoded nothing")

        preview.flush()

        assertEquals(0, preview.buffered)
        assertEquals(preview.openedFrames, preview.closedFrames)
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
      assertTrue(preview.openedFrames > 0, "the look-ahead decoded nothing")

      preview.release()

      assertEquals(0, preview.buffered)
      assertEquals(preview.openedFrames, preview.closedFrames)
      assertNull(preview.frameAt(Duration.ZERO), "a released preview still drew a frame")
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

      assertTrue(preview.openedFrames > 0, "the look-ahead decoded nothing")
      assertEquals(0, preview.buffered)
      assertEquals(preview.openedFrames, preview.closedFrames)
      assertNull(preview.frameAt(Duration.ZERO), "a released preview still drew a frame")
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

        assertTrue(preview.openedFrames > 0, "the look-ahead decoded nothing")
        assertEquals(0, preview.buffered)
        assertEquals(preview.openedFrames, preview.closedFrames)
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
        val before = assertNotNull(preview.frameAt(frameTime(PROBE_FRAMES.first())))
        val held = preview.buffered

        assertTrue(preview.updateParameters(resolve(bright), bright), "the swap was refused")

        val after = assertNotNull(preview.frameAt(frameTime(PROBE_FRAMES.first())))
        assertEquals(held, preview.buffered)
        assertTrue(
          after.at(x = 0.5, y = 0.5).red > before.at(x = 0.5, y = 0.5).red,
          "the grade change reached no pixel",
        )
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
        val filled = assertNotNull(preview.frameAt(gap), "no preview frame in the gap")
        assertEquals(gap, filled.presentationTime)
        assertTrue(filled.at(x = 0.5, y = 0.5).isNear(PURPLE_RGB), "the gap drew ${filled.at(0.5, 0.5)}")

        // Nothing is decoded in the gap, so a relaxed seek stays put and the look-ahead reads the
        // clip that comes next.
        assertEquals(gap, preview.syncSampleAt(gap))
        preview.fillAhead(gap)
        assertTrue(preview.buffered > 0, "the look-ahead decoded nothing ahead of the clip")

        val into = frameTime(LATE_SLOTS + CLIP_PROBE)
        val drawn = assertNotNull(preview.frameAt(into), "no preview frame after the start")
        assertEquals(into, drawn.presentationTime)
        assertTrue(
          drawn.at(x = 0.5, y = 0.5).isNear(ramp(CLIP_PROBE)),
          "the clip drew ${drawn.at(0.5, 0.5)} where its frame $CLIP_PROBE is ${ramp(CLIP_PROBE)}",
        )
      } finally {
        preview.release()
      }
    }

  // A start between two slots leaves the gap the export writes, fifteen slots here, so the grid slot
  // just short of the start is already the clip's opening frame. A relaxed seek into the clip lands on
  // that same frame rather than on the fill in front of it.
  @Test
  fun aPrimaryThatStartsBetweenTwoSlotsOpensWhereTheExportDoes() =
    runTest {
      val preview = previewOf(lateComposition(Fill.Solid(PURPLE_ARGB), start = OFF_GRID_START))

      try {
        val filled = assertNotNull(preview.frameAt(frameTime(GAP_PROBE)), "no preview frame in the gap")
        assertTrue(filled.at(x = 0.5, y = 0.5).isNear(PURPLE_RGB), "the gap drew ${filled.at(0.5, 0.5)}")

        // Ten milliseconds short of the start and a whole slot past the last gap frame.
        val shortOfStart = frameTime(OFF_GRID_LEAD)
        assertTrue(shortOfStart < OFF_GRID_START, "the probe at $shortOfStart is not short of $OFF_GRID_START")
        val opening = assertNotNull(preview.frameAt(shortOfStart), "no preview frame at $shortOfStart")
        assertTrue(
          opening.at(x = 0.5, y = 0.5).isNear(ramp(0)),
          "$shortOfStart drew ${opening.at(0.5, 0.5)} where the clip opens on ${ramp(0)}",
        )
        // The clip's own sync sample sits after this position, so a relaxed seek has nowhere earlier
        // to go and stays put.
        val shortSeek = preview.syncSampleAt(shortOfStart)
        assertTrue(shortSeek <= shortOfStart, "a relaxed seek to $shortOfStart moved forward to $shortSeek")

        val inClip = OFF_GRID_START + frameTime(CLIP_PROBE)
        val seek = preview.syncSampleAt(inClip)
        assertTrue(seek <= inClip, "a relaxed seek to $inClip moved forward to $seek")
        val landed = assertNotNull(preview.frameAt(seek), "no preview frame at $seek")
        assertTrue(
          landed.at(x = 0.5, y = 0.5).isNear(ramp(0)),
          "a relaxed seek landed on $seek and drew ${landed.at(0.5, 0.5)} where the clip opens on ${ramp(0)}",
        )
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

        assertEquals(opening, seek)
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
        makeClip(width = WIDTH, height = HEIGHT / 2, frames = SHORT_FRAMES, frameRate = FRAME_RATE, colour = Rgb.Red)
      val preview = previewOf(lateComposition(Fill.Blur, clips = listOf(filler, wide)))

      try {
        val letterboxed = assertNotNull(preview.frameAt(frameTime(LATE_SLOTS + SHORT_FRAMES + WIDE_PROBE)))
        val bar = letterboxed.at(x = 0.5, y = BAR)
        assertTrue(!bar.isNear(Rgb.Black), "the bar read $bar, so the background passes never ran")

        val filled = assertNotNull(preview.frameAt(frameTime(GAP_PROBE)))
        assertTrue(filled.at(x = 0.5, y = 0.5).isNear(Rgb.Black), "the gap drew ${filled.at(0.5, 0.5)}")
      } finally {
        preview.release()
      }
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
    assertIs<ResolveResult.Resolved>(
      browserExportEngine(
        components = ComponentRegistry.Builder().add(BuiltInEffectResolver()).build(),
        prober = BrowserProber(),
      ).resolve(composition, ExportSpec(videoCodec = VideoCodec.H264)),
    ).composition

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
   * A clip whose every frame is a different flat colour, so a frame drawn for the wrong time is a
   * different colour rather than an indistinguishable one.
   */
  private suspend fun rampClip(): ByteArray =
    makeClip(width = WIDTH, height = HEIGHT, frames = FRAMES, frameRate = FRAME_RATE) { index, _ -> ramp(index) }

  /**
   * A clip long enough to carry the sync sample the fixtures write every second, painted in a step
   * of its own so a frame past that second is still a colour a channel can hold.
   */
  private suspend fun secondKeyFrameClip(): ByteArray =
    makeClip(width = WIDTH, height = HEIGHT, frames = KEY_FRAME_FRAMES, frameRate = FRAME_RATE) { index, _ ->
      Rgb(RAMP_BASE + index, RAMP_BASE + index, MAX_CHANNEL - index)
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

    // Fifteen slots and three tenths of another, which leaves fifteen slots of gap.
    val OFF_GRID_START = 510.milliseconds
    const val OFF_GRID_LEAD = 15

    // Two short clips, the second half as tall as the output, probed in its middle and in its top bar.
    const val SHORT_FRAMES = 12
    const val WIDE_PROBE = 6
    const val BAR = 0.1

    const val PURPLE_ARGB = 0xFFA060C8.toInt()
    val PURPLE_RGB = Rgb(0xA0, 0x60, 0xC8)

    // Half a second past the sync sample the fixtures write on the second, so a seek has somewhere
    // to come back from.
    const val KEY_FRAME_FRAMES = 45

    const val RAMP_STEP = 6
    const val RAMP_BASE = 20
    const val MAX_CHANNEL = 255

    fun ramp(index: Int): Rgb = Rgb(RAMP_BASE + index * RAMP_STEP, RAMP_BASE + index, MAX_CHANNEL - index * RAMP_STEP)
  }
}

/**
 * The colour at a point given as fractions of the frame, measured from the top left.
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
