package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.lowering.FFMPEG_ENCODERS
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ffmpegEncoders
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.ENCODE_DEADLINE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.ffprobe
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The end-to-end pass: real clips, a real ffmpeg, a real file, verified with ffprobe.
 *
 * Covers encoders, probing, codecs, progress and cancellation. The other export suites run the same pass by subject.
 */
class FfmpegExportTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `reports every encoder the build carries, by name`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val result = fixture.filmstrip.capabilities()
      result.shouldBeInstanceOf<CapabilitiesResult.Success>()
      val video = result.capabilities.video
      withClue("expected at least one video encoder") { video.isNotEmpty().shouldBeTrue() }
      withClue("every ffmpeg encoder is named") { video.all { it.encoderName != null }.shouldBeTrue() }
      // Whether this holds depends on what the build carries, so the assertion reads the same
      // encoder table the probe reads rather than pinning the answer either way. The claim is about
      // the encoder an HDR export actually opens, which is the first ranked one for the codec a
      // kept grade is pinned to, so a build that carries a 10-bit encoder further down the ranking
      // is still expected to answer false.
      val reached =
        DEFAULT_HDR_LADDER.firstNotNullOfOrNull { codec -> video.firstOrNull { it.codec == codec } }
      val encodesHdr =
        FFMPEG_ENCODERS.values.flatten().any { it.name == reached?.encoderName && it.hdrPixelFormat != null }
      result.capabilities.supportsHdrEncoding shouldBe encodesHdr

      // The ranking is this backend's, so what capabilities() resolves is the table's order filtered
      // by what this build has, not whichever encoder answered the probe first.
      VideoCodec.entries.forEach { codec ->
        val ranked = codec.ffmpegEncoders().map { it.name }.filter { name -> video.any { it.encoderName == name } }
        result.capabilities.encoderFor(codec)?.encoderName shouldBe ranked.firstOrNull()
      }
    }

  // libx264 outruns h264_videotoolbox on this machine and hevc_videotoolbox outruns libx265, so the
  // two codecs resolve to opposite sides of the hardware flag. The tag matters as much as the
  // encoder: QuickTime refuses the hev1 an untagged HEVC stream is written as.
  @Test
  fun `encodes HEVC with the encoder the device ranked first`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-hevc", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(fixture.landscape.absolutePath)) }
      val spec = ExportSpec(heightPx = 240, videoCodec = VideoCodec.Hevc)

      val capabilities =
        fixture.filmstrip
          .capabilities()
          .shouldBeInstanceOf<CapabilitiesResult.Success>()
          .capabilities
      val expected = capabilities.encoderFor(VideoCodec.Hevc).shouldNotBeNull().encoderName

      val statuses = fixture.filmstrip.export(composition, spec, MediaSink.of(output.absolutePath)).toList()
      val finished = statuses.last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      ffprobe(output.absolutePath).endsWith("hevc aac") shouldBe true
      probeField(output.absolutePath, "codec_tag_string") shouldBe "hvc1"
      // ffmpeg writes the encoder it ran into the stream, so this is the file disagreeing with
      // capabilities() rather than the table being asked twice.
      withClue("the file was written by something other than $expected") {
        probeStreamTag(output.absolutePath, "encoder").endsWith(expected!!).shouldBeTrue()
      }

      output.delete()
    }

  @Test
  fun `probes a source through ffprobe`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val result =
        fixture.filmstrip
          .probe(
            MediaSource.of(fixture.landscape.absolutePath),
          ).shouldBeInstanceOf<ProbeResult.Success>()
      val video = result.info.video.shouldNotBeNull()
      video.displaySize shouldBe Size(640, 360)
      video.frameRate!!.roundToInt() shouldBe 30
      result.info.audio
        .shouldNotBeNull()
        .sampleRate shouldBe 48_000
      withClue("duration was ${result.info.duration}") {
        (result.info.duration in 1_800.milliseconds..2_300.milliseconds).shouldBeTrue()
      }
    }

  // The prober reads files, and there is nothing on the desktop that reads a container out of a
  // ByteArray without writing it down first. Core's own prober is consulted after this one and
  // declines too, so the reported failure is the one that names why.
  @Test
  fun `declines bytes it cannot open`() =
    runTest(timeout = TIMEOUT) {
      val result = fixture.filmstrip.probe(MediaSource.ofBytes(byteArrayOf(1, 2, 3)))
      val failure = result.shouldBeInstanceOf<ProbeResult.Failure>()
      failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
    }

  @Test
  fun `reports a missing toolchain rather than a missing artifact`() =
    runTest(timeout = TIMEOUT) {
      val absent =
        Filmstrip {
          ffmpegBackend(FfmpegConfig(executablePath = "/nonexistent/ffmpeg"))
        }

      val failure =
        absent
          .probe(
            MediaSource.of(fixture.landscape.absolutePath),
          ).shouldBeInstanceOf<ProbeResult.Failure>()
      failure.error.shouldBeInstanceOf<FilmstripError.ToolchainMissing>()
    }

  @Test
  fun `exports two clips into one reframed file`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-export", ".mp4").also { it.delete() }
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture.landscape.absolutePath)) {
            trim(TimeRange.of(0.milliseconds, 1_000.milliseconds))
          }
          clip(MediaSource.of(fixture.portrait.absolutePath)) {
            trim(TimeRange.of(0.milliseconds, 1_000.milliseconds))
            effects { rotate(180) }
          }
          effects { crop(AspectRatio.Portrait) }
        }

      val spec = ExportSpec(heightPx = 480)
      val verdict = fixture.filmstrip.plan(composition, spec)
      val plan =
        when (verdict) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
        }

      plan.output.size.height shouldBe 480
      plan.output.size.width shouldBe 268

      val statuses = fixture.filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList()
      val finished = statuses.last()
      if (finished is ExportStatus.Failure) error(finished.error.message)

      statuses.first().shouldBeInstanceOf<ExportStatus.Started>()
      withClue("expected progress") { statuses.any { it is ExportStatus.Progress }.shouldBeTrue() }

      val success = finished.shouldBeInstanceOf<ExportStatus.Success>()
      success.info.video!!.displaySize shouldBe plan.output.size
      success.info.audio!!.sampleRate shouldBe 48_000
      // Two one second clips, laid end to end.
      withClue("duration was ${success.info.duration}") {
        (success.info.duration in 1_800.milliseconds..2_300.milliseconds).shouldBeTrue()
      }

      // Verified independently of the code under test.
      val probe = ffprobe(output.absolutePath)
      probe shouldBe "268x480 30/1 h264 aac"

      output.delete()
    }

  // The probe looks for libvpx before allowing VP9. Nothing else in the pipeline is VP9-aware, so
  // the check is that a whole export survives it.
  @Test
  fun `exports VP9 on a build that carries libvpx`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val capabilities =
        fixture.filmstrip
          .capabilities()
          .shouldBeInstanceOf<CapabilitiesResult.Success>()
          .capabilities
      if (capabilities.encoderFor(VideoCodec.Vp9) == null) return@runTest

      val output = File.createTempFile("filmstrip-vp9", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(fixture.landscape.absolutePath)) }
      // Half of the fixture's 640x360, so nothing rounds and the verdict stays Capable.
      val spec = ExportSpec(heightPx = 180, videoCodec = VideoCodec.Vp9)

      val verdict = fixture.filmstrip.plan(composition, spec)
      verdict
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.output.videoCodec shouldBe VideoCodec.Vp9

      val finished =
        fixture.filmstrip
          .export(verdict.plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      ffprobe(output.absolutePath).endsWith("vp9 aac") shouldBe true

      output.delete()
    }

  // ALAC has never had a CodecKind of its own, so the written file's audio track reads back as
  // one drift() cannot name. Proving the export still succeeds is what keeps that gap from
  // failing a file that matches the plan exactly.
  @Test
  fun `exports ALAC on a build that carries it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val capabilities =
        fixture.filmstrip
          .capabilities()
          .shouldBeInstanceOf<CapabilitiesResult.Success>()
          .capabilities
      if (capabilities.audio.none { it.codec == AudioCodec.Alac }) return@runTest

      val output = File.createTempFile("filmstrip-alac", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(fixture.landscape.absolutePath)) }
      val spec = ExportSpec(heightPx = 180, audioCodec = AudioCodec.Alac)

      val verdict = fixture.filmstrip.plan(composition, spec)
      verdict
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.output.audioCodec shouldBe AudioCodec.Alac

      val finished =
        fixture.filmstrip
          .export(verdict.plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      ffprobe(output.absolutePath).endsWith("h264 alac") shouldBe true

      output.delete()
    }

  @Test
  fun `streams progress while the encode runs`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.long.isFile) return@runTest

      val output = File.createTempFile("filmstrip-progress", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(fixture.long.absolutePath)) }
      val statuses =
        fixture.filmstrip
          .export(composition, ExportSpec(heightPx = 720), MediaSink.of(output.absolutePath))
          .toList()

      val progress = statuses.filterIsInstance<ExportStatus.Progress>()
      withClue("expected more than one progress report, got ${progress.size}") { (progress.size > 1).shouldBeTrue() }
      progress.zipWithNext().forEach { (first, second) ->
        withClue("progress went backwards") { (second.fraction >= first.fraction).shouldBeTrue() }
      }
      progress.last().fraction shouldBe 1f
      statuses.last().shouldBeInstanceOf<ExportStatus.Success>()

      output.delete()
    }

  // Cancelling the collector cancels the export. ffmpeg is asked to stop with `q` rather than
  // killed, so what it has written is a readable file rather than a truncated one.
  @Test
  fun `cancelling the collector leaves a readable file`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.long.isFile) return@runTest

      val output = File.createTempFile("filmstrip-cancel", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(fixture.long.absolutePath)) }

      withContext(Dispatchers.Default) {
        val flow =
          fixture.filmstrip.export(composition, ExportSpec(heightPx = 720), MediaSink.of(output.absolutePath))
        val encoding = CompletableDeferred<Unit>()
        // Cancelled once a report carries encoded output rather than after a fixed wait. A runner
        // slow enough to still be starting ffmpeg at the deadline leaves a graceful stop nothing to
        // close, and the readable file this is about only exists once frames have reached the muxer.
        val collector =
          launch {
            flow.collect { status ->
              if (status is ExportStatus.Progress && status.fraction > 0f) encoding.complete(Unit)
            }
          }

        withTimeoutOrNull(ENCODE_DEADLINE) { encoding.await() }
        withClue("the export finished before it could be cancelled") { collector.isActive.shouldBeTrue() }
        collector.cancelAndJoin()
      }

      withClue("nothing was written before the cancel") { (output.length() > 0).shouldBeTrue() }
      ffprobe(output.absolutePath).startsWith("1280x720") shouldBe true

      output.delete()
    }

  @Test
  fun `refuses text and offers the same edit without it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-export", ".mp4").also { it.delete() }
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture.landscape.absolutePath)) {
            trim(TimeRange.of(0.milliseconds, 500.milliseconds))
          }
          effects { textOverlay("caption") }
        }

      val verdict = fixture.filmstrip.plan(composition, ExportSpec(heightPx = 240))
      val incapable = verdict.shouldBeInstanceOf<Verdict.Incapable>()
      incapable.reasons.single().shouldBeInstanceOf<FilmstripError.UnsupportedEffect>()
      val fallback = incapable.withoutUnsupported!!
      fallback.plan.effectOrder
        .none { it.spec.id == EffectIds.TEXT_OVERLAY } shouldBe true

      // The engine runs the plan without the text, so only the plan can say it was dropped.
      val finished =
        fixture.filmstrip
          .export(fallback.plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.reasons.joinToString { it.message })
      finished
        .shouldBeInstanceOf<ExportStatus.Success>()
        .adjustments
        .filter { it.kind == AdjustmentKind.EffectDropped }
        .map { it.requested } shouldBe listOf(EffectIds.TEXT_OVERLAY)

      output.delete()
    }

  @Test
  fun `reports a missing binary as a value`() =
    runTest(timeout = TIMEOUT) {
      val absent =
        Filmstrip {
          ffmpegBackend(FfmpegConfig(executablePath = "/nonexistent/ffmpeg"))
        }

      val result = absent.capabilities()
      val failure = result.shouldBeInstanceOf<CapabilitiesResult.Failure>()
      val error = failure.error.shouldBeInstanceOf<FilmstripError.ToolchainMissing>()
      error.tool shouldBe "ffmpeg"
      error.foundVersion shouldBe null
    }

  private fun probeStreamTag(
    path: String,
    tag: String,
  ): String = probeEntries(path, "stream_tags=$tag")

  private fun probeField(
    path: String,
    field: String,
  ): String = probeEntries(path, "stream=$field")

  private fun probeEntries(
    path: String,
    entries: String,
  ): String {
    val process =
      ProcessBuilder(
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "v",
        "-show_entries",
        entries,
        "-of",
        "csv=p=0",
        path,
      ).start()
    val output = process.inputReader().readText()
    process.waitFor()
    return output.trim()
  }
}
