package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
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
  }
}
