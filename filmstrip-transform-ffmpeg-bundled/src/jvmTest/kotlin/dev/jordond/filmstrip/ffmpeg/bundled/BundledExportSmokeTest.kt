package dev.jordond.filmstrip.ffmpeg.bundled

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.frameAt
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacpp.Loader
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exports and previews one clip with nothing but this artifact's ffmpeg, the way an app that only added the
 * dependency does.
 */
class BundledExportSmokeTest {
  private val scratch = Files.createTempDirectory("filmstrip-bundled-smoke").toFile()
  private val toolchains = mutableListOf<DiagnosticEvent>()
  private val filmstrip =
    Filmstrip {
      addDiagnosticListener {
        if (it.name ==
          DiagnosticEvent.TOOLCHAIN
        ) {
          toolchains += it
        }
      }
    }

  @AfterTest
  fun delete() {
    scratch.deleteRecursively()
  }

  @Test
  fun `an edit exports to H264 through the carried ffmpeg`() =
    runTest(timeout = TIMEOUT) {
      val output = File(scratch, "out.mp4")
      val plan =
        when (val verdict = filmstrip.plan(composition(), ExportSpec())) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> fail("the edit cannot run: ${verdict.reasons.map { it.message }}")
        }

      val finished = filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList().last()

      if (finished is ExportStatus.Failure) fail(finished.error.message)
      val info = finished.shouldBeInstanceOf<ExportStatus.Success>().info
      val video = info.video.shouldNotBeNull()
      video.codec.kind shouldBe CodecKind.H264
      video.displaySize shouldBe Size(CLIP_WIDTH, CLIP_HEIGHT)
      info.audio.shouldNotBeNull()
      info.duration shouldBe CLIP_LENGTH
      ranTheCarriedFfmpeg()
    }

  // The LGPL build has no libx264, so H264 lands on VideoToolbox where there is one and on openh264 everywhere else.
  @Test
  fun `H264 resolves to the encoder the carried build has for this machine`() =
    runTest(timeout = TIMEOUT) {
      val capabilities = filmstrip.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities

      val expected = if (IS_MAC) "h264_videotoolbox" else "libopenh264"
      capabilities.encoderFor(VideoCodec.H264).shouldNotBeNull().encoderName shouldBe expected
    }

  @Test
  fun `a preview draws a frame through the carried ffmpeg`() =
    runTest(timeout = TIMEOUT) {
      filmstrip.preview(composition()).use { player ->
        val frame =
          withContext(
            Dispatchers.Default,
          ) { withTimeout(FRAME_WAIT) { firstFrame { player.readback.frameAt(Duration.ZERO) } } }

        frame.size shouldBe Size(CLIP_WIDTH, CLIP_HEIGHT)
        frame.pixels.count { it.toInt() and 0xFF > LIT } shouldBeGreaterThan frame.pixels.size / 4
      }
      ranTheCarriedFfmpeg()
    }

  private suspend fun firstFrame(read: suspend () -> ReadbackResult): ReadbackFrame {
    while (true) {
      (read() as? ReadbackResult.Success)?.let { return it.frame }
      delay(POLL)
    }
  }

  private suspend fun composition(): Composition {
    val clip = bundledClip(scratch)
    val source = MediaSource.of(clip.absolutePath)
    return Composition(tracks = listOf(Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, CLIP_LENGTH))))))
  }

  private fun ranTheCarriedFfmpeg() {
    toolchains.shouldNotBeEmpty()
    toolchains.forEach { it.detail.getValue(DiagnosticEvent.Detail.FFMPEG) shouldStartWith Loader.getCacheDir().path }
  }

  private companion object {
    val CLIP_LENGTH = 1.seconds
    val TIMEOUT = 2.seconds * 60
    val FRAME_WAIT = 60.seconds
    val POLL = 100.milliseconds

    // A channel value the test pattern is well above and a frame that never drew is not.
    const val LIT = 32
  }
}
