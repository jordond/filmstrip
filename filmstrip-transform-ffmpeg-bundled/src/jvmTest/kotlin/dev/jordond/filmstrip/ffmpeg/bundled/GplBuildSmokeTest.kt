package dev.jordond.filmstrip.ffmpeg.bundled

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.FfmpegBinariesResult
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacpp.Loader
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What an app gets by adding bytedeco's GPL ffmpeg beside this artifact, run with the real GPL jar for this machine
 * on the classpath.
 */
class GplBuildSmokeTest {
  private val scratch = Files.createTempDirectory("filmstrip-bundled-gpl").toFile()

  @AfterTest
  fun delete() {
    scratch.deleteRecursively()
  }

  @Test
  fun `the gpl build is the one that runs`() =
    runTest(timeout = TIMEOUT) {
      File(gplFfmpeg()).parentFile.name shouldBe Loader.getPlatform() + BundledBinaries.GPL_SUFFIX
    }

  @Test
  fun `the gpl build brings libx264 and libx265 and HDR export with them`() =
    runTest(timeout = TIMEOUT) {
      val capabilities = Filmstrip().capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities

      capabilities.video.mapNotNull { it.encoderName } shouldContainAll listOf("libx264", "libx265")
      capabilities.encoderFor(VideoCodec.H264).shouldNotBeNull().encoderName shouldBe "libx264"
      capabilities.encoderFor(VideoCodec.Hevc).shouldNotBeNull().encoderName shouldBe
        if (IS_MAC) "hevc_videotoolbox" else "libx265"
      capabilities.supportsHdrEncoding shouldBe true
    }

  @Test
  fun `a PQ clip exports to ten bit HEVC that is still PQ`() =
    runTest(timeout = TIMEOUT) {
      val filmstrip = Filmstrip()
      val source = MediaSource.of(pqClip().absolutePath)
      val composition = Composition(tracks = listOf(Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, LENGTH))))))
      val plan =
        when (val verdict = filmstrip.plan(composition, ExportSpec(videoCodec = VideoCodec.Hevc))) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> fail("the edit is degraded: ${verdict.plan}")
          is Verdict.Incapable -> fail("the edit cannot run: ${verdict.reasons.map { it.message }}")
        }

      val finished = filmstrip.export(plan, MediaSink.of(File(scratch, "out.mp4").absolutePath)).toList().last()

      if (finished is ExportStatus.Failure) fail(finished.error.message)
      val video =
        finished
          .shouldBeInstanceOf<ExportStatus.Success>()
          .info.video
          .shouldNotBeNull()
      video.codec.kind shouldBe CodecKind.Hevc
      video.bitDepth shouldBe 10
      video.hdrTransfer shouldBe HdrTransfer.Pq
    }

  private suspend fun gplFfmpeg(): String =
    BundledBinaries.Default
      .locate()
      .shouldBeInstanceOf<FfmpegBinariesResult.Found>()
      .ffmpeg

  // One second of test pattern as ten bit PQ, written by the GPL build's own libx265.
  private suspend fun pqClip(): File {
    val clip = File(scratch, "pq.mp4")
    val log = File(scratch, "pq.log")
    val command =
      listOf(
        gplFfmpeg(),
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-f",
        "lavfi",
        "-i",
        "testsrc2=s=${CLIP_WIDTH}x$CLIP_HEIGHT:r=30:d=1",
        "-c:v",
        "libx265",
        "-pix_fmt",
        "yuv420p10le",
        "-tag:v",
        "hvc1",
        "-color_primaries",
        "bt2020",
        "-color_trc",
        "smpte2084",
        "-colorspace",
        "bt2020nc",
        "-x265-params",
        "colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc",
        clip.absolutePath,
      )
    val exit =
      ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(log)
        .start()
        .waitFor()
    withClue(log.readText()) { exit shouldBe 0 }
    return clip
  }

  private companion object {
    val LENGTH = 1.seconds
    val TIMEOUT = 4.minutes
  }
}
