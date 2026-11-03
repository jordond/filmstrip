package dev.jordond.filmstrip.ffmpeg.hdr

import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.lowering.toneMapRoute
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.encodesHdr
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test

/**
 * HDR sources through a re-encode, a tone map to Rec. 709 and a copy, checked by the color tags the written file
 * carries.
 */
class FfmpegHdrTest {
  private val fixture = FfmpegExportFixture()

  /**
   * Whether this build can take either tone-map route, read off the same toolchain the backend
   * gates on.
   */
  private suspend fun canToneMap(): Boolean {
    val result = ToolchainLocator(FfmpegConfig(), ProcessRunner()).toolchain()
    return result is ToolchainResult.Available && toneMapRoute(result.toolchain) != null
  }

  // The grade is what an HDR export is for, and neither the plan nor a Success status says whether
  // it reached the file. ffprobe does.
  @Test
  fun `keeps an hdr grade through a re-encode`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val output = File.createTempFile("filmstrip-hdr", ".mp4").also { it.delete() }
      // A rotate takes the clip off the copy path, so this is the encoder writing the grade rather
      // than the muxer carrying the source's bytes across.
      val composition = compositionOf { clip(MediaSource.of(fixture.hdr.absolutePath)) { effects { rotate(90) } } }
      val spec = ExportSpec(hdr = HdrMode.KeepHdr)

      val plan =
        fixture.filmstrip
          .plan(composition, spec)
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan
      plan.path shouldBe ExportPath.Transcode
      plan.output.videoCodec shouldBe VideoCodec.Hevc

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      colorTags(output.absolutePath) shouldBe "yuv420p10le bt2020 smpte2084 bt2020nc"

      output.delete()
    }

  // Bringing a grade down correctly wants zscale (--enable-libzimg) or libplacebo, and neither is
  // in the common prebuilt packages, so this asks the toolchain the same question the planner does
  // rather than assuming either answer.
  @Test
  fun `tone-maps an hdr grade down to rec709 when the build can`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val composition = compositionOf { clip(MediaSource.of(fixture.hdr.absolutePath)) { effects { rotate(90) } } }
      val verdict = fixture.filmstrip.plan(composition, ExportSpec(hdr = HdrMode.ToneMapToSdr))

      if (!canToneMap()) {
        withClue("a build with no zscale or libplacebo has no honest tone map to offer") {
          verdict.shouldBeInstanceOf<Verdict.Incapable>()
        }
        return@runTest
      }

      val output = File.createTempFile("filmstrip-sdr", ".mp4").also { it.delete() }
      val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      colorTags(output.absolutePath) shouldBe "yuv420p bt709 bt709 bt709"

      output.delete()
    }

  // A copy runs no encoder, so this holds whatever the build can encode: the grade rides across in
  // the source's own packets and the tags ride with it.
  @Test
  fun `copies an hdr clip with its grade intact`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-hdr-copy", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(fixture.hdr.absolutePath)) }

      val plan =
        fixture.filmstrip
          .plan(composition, ExportSpec())
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan
      plan.path shouldBe ExportPath.Copy

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      colorTags(output.absolutePath) shouldBe colorTags(fixture.hdr.absolutePath)

      output.delete()
    }

  /**
   * The color tags and the pixel format, the four fields that decide whether a file is HDR.
   *
   * Reported together because a 10-bit frame with Rec.709 tags and an 8-bit frame with BT.2020 tags
   * are both wrong, and either one alone reads as a pass.
   */
  private fun colorTags(path: String): String {
    val process =
      ProcessBuilder(
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-show_entries",
        "stream=pix_fmt,color_primaries,color_transfer,color_space",
        "-of",
        "default=noprint_wrappers=1",
        path,
      ).start()
    val output = process.inputReader().readText()
    process.waitFor()

    // ffprobe prints these in its own order rather than the order they were asked for, so they are
    // read by key and put back in one this test can pin.
    val fields =
      output
        .lineSequence()
        .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
        .associate { it[0].trim() to it[1].trim() }

    return listOf("pix_fmt", "color_primaries", "color_transfer", "color_space")
      .joinToString(" ") { fields[it] ?: "unknown" }
  }
}
