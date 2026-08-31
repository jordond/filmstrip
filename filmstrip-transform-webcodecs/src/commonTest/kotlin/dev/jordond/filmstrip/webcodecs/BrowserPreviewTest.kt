package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.VideoCodec
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
          val expected = ramp(index).brightened(BRIGHT)
          assertTrue(drawn.isNear(expected), "frame $index drew $drawn where the export writes $expected")
        }
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
          "the brightness change reached no pixel",
        )
      } finally {
        preview.release()
      }
    }

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

/**
 * This colour with the compositor's brightness applied, clamped the way the shader clamps it.
 */
private fun Rgb.brightened(factor: Float): Rgb =
  Rgb(
    (red * factor).toInt().coerceIn(0, BYTE_MASK),
    (green * factor).toInt().coerceIn(0, BYTE_MASK),
    (blue * factor).toInt().coerceIn(0, BYTE_MASK),
  )

private const val RGBA_CHANNELS = 4
private const val BYTE_MASK = 0xFF
