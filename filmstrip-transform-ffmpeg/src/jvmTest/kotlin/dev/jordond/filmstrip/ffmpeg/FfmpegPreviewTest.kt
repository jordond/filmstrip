package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The pump, against a real ffmpeg and real clips.
 *
 * The first test is the parity claim on this backend at the level a caller reaches it: one edit,
 * two entry points, one filter graph on both command lines. [PreviewInvocationTest] pins the same
 * claim one layer down, where a second graph builder written "just for preview" would show up.
 *
 * Nothing here skips. The fixtures are a task dependency of `jvmTest` and the jvm lanes carry
 * ffmpeg, so a missing one is a broken build rather than a reason to report green.
 */
class FfmpegPreviewTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")
  private val portrait = File(fixtures, "export_portrait.mp4")

  private val commands = mutableListOf<DiagnosticEvent>()

  private val components =
    ComponentRegistry
      .Builder()
      .add(BuiltInEffectResolver())
      .add(DiagnosticListener { event -> synchronized(commands) { commands += event } })
      .build()

  private val engine = FfmpegExportEngine(components, FfmpegRuntime.of(FfmpegConfig()))

  @Test
  fun `the preview and the export run the same filter graph`() =
    runTest(timeout = TIMEOUT) {
      val composition = graded()
      val output = File.createTempFile("filmstrip-preview-parity", ".mp4").also { it.delete() }

      val verdict = engine.plan(composition, ExportSpec())
      val plan = assertIs<Verdict.Capable>(verdict).plan
      val finished = engine.export(plan, MediaSink.of(output.absolutePath)).toList().last()
      if (finished is ExportStatus.Failure) fail("the export refused the fixture: ${finished.error.message}")

      val opened = assertIs<PreviewStreamResult.Opened>(engine.openPreview(composition, ExportSpec()))
      opened.stream.close()
      output.delete()

      graphOf("invocation") shouldBe graphOf("preview")
    }

  // Reading forward from the head and seeking straight there have to reach the same frame, or the
  // seek is landing on a different part of the clip than playback would.
  @Test
  fun `an input seek lands on the frame reading forward reaches`() =
    runTest(timeout = TIMEOUT) {
      val composition = graded()

      val read = open(composition, Duration.ZERO)
      val forward =
        try {
          repeat(PROBE_FRAME) { assertNotNull(read.next(), "the pump ran out before frame $PROBE_FRAME") }
          assertNotNull(read.next(), "the pump ran out before frame $PROBE_FRAME")
        } finally {
          read.close()
        }

      val seeked = open(composition, PROBE_POSITION)
      val landed =
        try {
          assertNotNull(seeked.next(), "the seeked pump delivered no frame")
        } finally {
          seeked.close()
        }

      seeked.startPosition shouldBe PROBE_POSITION
      assertTrue(forward.contentEquals(landed), "the seek landed on a different frame than reading forward did")
    }

  // An overlay image cannot be seeked into, so its branch is carried to the scrub instead. Without
  // that the animation restarts wherever the seek lands and the overlay is drawn at its opening
  // value. The probe is a fifth of the way into the fade, because both ends of a ramp read the same
  // under a restarted animation as under a carried one.
  @Test
  fun `a seek into an overlay animation draws what the export draws`() =
    runTest(timeout = TIMEOUT) {
      val faded = overlaid(watermark(animation = fadeIn(FIXTURE_LENGTH)))

      val read = open(faded, Duration.ZERO)
      val forward =
        try {
          repeat(PROBE_FRAME) { assertNotNull(read.next(), "the pump ran out before frame $PROBE_FRAME") }
          assertNotNull(read.next(), "the pump ran out before frame $PROBE_FRAME")
        } finally {
          read.close()
        }

      val landed = firstFrame(faded, PROBE_POSITION)
      assertTrue(forward.contentEquals(landed), "the scrubbed animation drew a different frame than playback did")

      // Both ends of the same fade, scrubbed to the same frame of the same clip, so the overlay is
      // all that differs between them. A fifth of the way up the ramp is neither.
      val hidden = firstFrame(overlaid(watermark(opacity = 0f)), PROBE_POSITION)
      val whole = firstFrame(overlaid(watermark(opacity = 1f)), PROBE_POSITION)
      assertFalse(hidden.contentEquals(whole), "the overlay changed nothing at the probe")
      assertFalse(landed.contentEquals(hidden), "the scrubbed animation drew no overlay at all")
      assertFalse(landed.contentEquals(whole), "the scrubbed animation drew the overlay whole")
    }

  // A command is only written where its own value moves, so a scrub landing after the last of them
  // has nothing to read unless the interval covering it says what the value settled on. The fade
  // settles at the authored opacity, which is not what the graph opens the node at, so a scrub that
  // read nothing would draw the overlay whole.
  @Test
  fun `a seek past the end of a ramp draws the value it settled on`() =
    runTest(timeout = TIMEOUT) {
      val settled = overlaid(watermark(opacity = SETTLED_OPACITY, animation = fadeIn(SHORT_FADE)))

      val read = open(settled, Duration.ZERO)
      val forward =
        try {
          repeat(SETTLED_FRAME) { assertNotNull(read.next(), "the pump ran out before frame $SETTLED_FRAME") }
          assertNotNull(read.next(), "the pump ran out before frame $SETTLED_FRAME")
        } finally {
          read.close()
        }

      val landed = firstFrame(settled, SETTLED_POSITION)
      val whole = firstFrame(overlaid(watermark()), SETTLED_POSITION)

      assertTrue(forward.contentEquals(landed), "the settled overlay was drawn differently than playback drew it")
      assertFalse(landed.contentEquals(whole), "the settled overlay was drawn at the opacity its node opens on")
    }

  // The seek delivers the first frame at or after where it was asked for, so a scrub landing inside
  // a frame period opens the clip on the frame after it and the overlay's branch has to be carried
  // onto that same frame. Every other probe here sits on the grid, where the two agree anyway.
  @Test
  fun `a seek inside a frame period carries the branch onto the frame it lands on`() =
    runTest(timeout = TIMEOUT) {
      val faded = overlaid(watermark(animation = fadeIn(OFF_GRID_FADE)))

      val read = open(faded, Duration.ZERO)
      val forward =
        try {
          repeat(OFF_GRID_FRAME) { assertNotNull(read.next(), "the pump ran out before frame $OFF_GRID_FRAME") }
          assertNotNull(read.next(), "the pump ran out before frame $OFF_GRID_FRAME")
        } finally {
          read.close()
        }

      val landed = firstFrame(faded, OFF_GRID_POSITION)

      assertTrue(forward.contentEquals(landed), "the branch was read a frame off the one the clip delivered")
    }

  // The window rides the overlay's own branch, which the scrub carries to the seek, so a frame
  // reached by seeking is drawn the way the same frame is when it is read forward. All three probes
  // land inside a frame period, where the seek opens the clip on the frame after the scrub.
  @Test
  fun `a scrubbed preview opens an overlay's window on time`() =
    runTest(timeout = TIMEOUT) {
      val windowed = overlaid(watermark(visibleDuring = WINDOW))

      val early = firstFrame(windowed, BEFORE_WINDOW)
      val open = firstFrame(windowed, INSIDE_WINDOW)
      val shut = firstFrame(windowed, AFTER_WINDOW)

      assertTrue(early.contentEquals(forwardFrame(windowed, BEFORE_FRAME)), "the window was open before its start")
      assertTrue(open.contentEquals(forwardFrame(windowed, INSIDE_FRAME)), "the window was shut at the scrub")
      assertTrue(shut.contentEquals(forwardFrame(windowed, AFTER_FRAME)), "the window was open past its own end")

      // The overlay is all the two references differ by, read at the same scrub, so a probe that
      // matched both would say nothing about the window.
      val always = overlaid(watermark())
      val hidden = overlaid(watermark(opacity = 0f))
      assertTrue(open.contentEquals(firstFrame(always, INSIDE_WINDOW)), "the overlay was drawn at some other value")
      assertFalse(open.contentEquals(firstFrame(hidden, INSIDE_WINDOW)), "the overlay changed nothing at the probe")
      assertTrue(early.contentEquals(firstFrame(hidden, BEFORE_WINDOW)), "the overlay was drawn before the window")
      assertTrue(shut.contentEquals(firstFrame(hidden, AFTER_WINDOW)), "the overlay was still drawn past the window")
    }

  @Test
  fun `a frame is the composition's own frame, tightly packed`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(graded(), Duration.ZERO)
      try {
        stream.size shouldBe FIXTURE_FRAME
        assertNotNull(stream.next()).size shouldBe FIXTURE_FRAME.width * FIXTURE_FRAME.height * CHANNELS
      } finally {
        stream.close()
      }
    }

  // A concat has branches the seek would move out from under it, so the only correct answer left is
  // to open at the head and let the caller read forward.
  @Test
  fun `a composition an input seek cannot window opens at the head`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(twoClips(), PROBE_POSITION)
      try {
        stream.startPosition shouldBe Duration.ZERO
      } finally {
        stream.close()
      }
    }

  // A pump that never ends is a coroutine that never returns and a process nobody closes, so the
  // last frame has to be followed by an end rather than by a wait.
  @Test
  fun `a preview run to the end of the composition ends rather than hanging`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(graded(), FIXTURE_LENGTH - FIXTURE_FRAME_STEP * TAIL_FRAMES)
      try {
        var frames = 0
        while (stream.next() != null) frames++
        frames shouldBe TAIL_FRAMES
      } finally {
        stream.close()
      }
    }

  // The child is spawned by filmstrip and reaped by filmstrip. A preview that outlives its stream
  // holds a decoder open and keeps writing into a pipe nobody reads.
  @Test
  fun `closing a stream leaves no process behind`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(graded(), Duration.ZERO)
      val pid = assertNotNull(stream.processId, "the pump reported no process id")
      assertNotNull(stream.next(), "the pump delivered no frame")

      stream.close()

      val handle = ProcessHandle.of(pid)
      (handle.isPresent && handle.get().isAlive) shouldBe false
    }

  private suspend fun open(
    composition: EditComposition,
    at: Duration,
  ): FfmpegPreviewStream {
    val result = engine.openPreview(composition, ExportSpec(), at = at)
    return assertIs<FfmpegPreviewStream>(assertIs<PreviewStreamResult.Opened>(result).stream)
  }

  private suspend fun firstFrame(
    composition: EditComposition,
    at: Duration,
  ): ByteArray = frames(composition, at, 1).single()

  // Frame [index] of a preview read from the head, which is the frame a scrub landing on the same
  // one of the grid has to agree with.
  private suspend fun forwardFrame(
    composition: EditComposition,
    index: Int,
  ): ByteArray = frames(composition, Duration.ZERO, index + 1).last()

  private suspend fun frames(
    composition: EditComposition,
    at: Duration,
    count: Int,
  ): List<ByteArray> {
    val stream = open(composition, at)
    return try {
      buildList {
        repeat(count) { index -> add(assertNotNull(stream.next(), "the pump ran out before frame $index at $at")) }
      }
    } finally {
      stream.close()
    }
  }

  private fun overlaid(overlay: ImageOverlay): EditComposition {
    assertTrue(landscape.isFile, "the fixture ${landscape.absolutePath} was not downloaded")
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of(landscape.absolutePath))))),
      effects = listOf(overlay),
    )
  }

  // A white square in the corner the frame starts at, drawn big enough that what it is drawn at
  // shows on the frame.
  private fun watermark(
    opacity: Float = 1f,
    visibleDuring: TimeRange? = null,
    animation: OverlayAnimation? = null,
  ): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(WHITE_SQUARE),
      corner = Corner.TopStart,
      margin = 0f,
      scale = OVERLAY_SCALE,
      opacity = opacity,
      visibleDuring = visibleDuring,
      animation = animation,
    )

  private fun graded(): EditComposition {
    assertTrue(landscape.isFile, "the fixture ${landscape.absolutePath} was not downloaded")
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of(landscape.absolutePath))))),
      effects = listOf(Brightness(BRIGHTNESS)),
    )
  }

  private fun twoClips(): EditComposition {
    assertTrue(landscape.isFile && portrait.isFile, "the fixtures were not downloaded")
    return EditComposition(
      tracks =
        listOf(
          Track(
            listOf(
              Clip(MediaSource.of(landscape.absolutePath), TimeRange.of(Duration.ZERO, 1.seconds)),
              Clip(MediaSource.of(portrait.absolutePath), TimeRange.of(Duration.ZERO, 1.seconds)),
            ),
          ),
        ),
      effects = listOf(Brightness(BRIGHTNESS)),
    )
  }

  // Read out of the command line each path actually spawned, rather than out of the lowering both
  // share, so a second graph builder written for the preview would fail this.
  private fun graphOf(name: String): String {
    val command =
      synchronized(commands) { commands.lastOrNull { it.name == name } }
        ?: fail("no $name command was reported")
    return command.detail
      .getValue("command")
      .substringAfter("-filter_complex ")
      .substringBefore(" -map ")
  }

  private companion object {
    val TIMEOUT = 2.minutes

    val FIXTURE_FRAME = Size(640, 360)
    const val CHANNELS = 4
    const val BRIGHTNESS = 1.4f

    // Frame 12 on the fixture's 30fps grid, in the middle of the clip rather than at either end.
    val PROBE_POSITION = 400.milliseconds
    const val PROBE_FRAME = 12

    val FIXTURE_LENGTH = 2.seconds
    val FIXTURE_FRAME_STEP = 1.seconds / 30
    const val TAIL_FRAMES = 3

    // A quarter of the frame width, so the overlay covers enough of the picture that the opacity it
    // is drawn at cannot round away.
    const val OVERLAY_SCALE = 0.25f
    const val OVERLAY_PIXELS = 64

    // A fade that is over well before the probe, and an authored opacity it settles on that the
    // colorchannelmixer the commands drive does not open at.
    val SHORT_FADE = 500.milliseconds
    val SETTLED_POSITION = 1.seconds
    const val SETTLED_FRAME = 30
    const val SETTLED_OPACITY = 0.5f

    // A third of the way into frame 15's period, so the clip opens on frame 16 and a branch left at
    // the scrub itself would be driven by frame 15. The fade is steep enough that one frame of it
    // moves the drawn pixels.
    val OFF_GRID_POSITION = 510.milliseconds
    val OFF_GRID_FADE = 1.seconds
    const val OFF_GRID_FRAME = 16

    // A window in the middle of the fixture, with a scrub landing short of it, one well inside it
    // and one well past it. Every scrub sits a third of the way into a frame period, so the seek
    // opens the clip on the frame after the one it names and the overlay's branch has to be carried
    // onto that frame too.
    val WINDOW = TimeRange.of(500.milliseconds, 1_500.milliseconds)
    val BEFORE_WINDOW = 210.milliseconds
    const val BEFORE_FRAME = 7
    val INSIDE_WINDOW = 810.milliseconds
    const val INSIDE_FRAME = 25
    val AFTER_WINDOW = 1_610.milliseconds
    const val AFTER_FRAME = 49

    // An animated overlay is sized off the picture's own header through ImageIO, so the bytes have
    // to be a format this process decodes.
    val WHITE_SQUARE: ByteArray =
      BufferedImage(OVERLAY_PIXELS, OVERLAY_PIXELS, BufferedImage.TYPE_INT_ARGB)
        .also { image ->
          image.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, OVERLAY_PIXELS, OVERLAY_PIXELS)
            dispose()
          }
        }.let { image -> ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray() }
  }
}
