package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.ffmpeg.ExportConstants.BRIGHTER
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The copy path and snapped cuts, checked against the sync samples and bytes of the written file.
 */
class FfmpegCopyTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `copies an untouched clip instead of re-encoding it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-copy", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(fixture.landscape.absolutePath)) }

      val verdict = fixture.filmstrip.plan(composition, ExportSpec())
      val plan = assertIs<Verdict.Capable>(verdict).plan
      plan.path shouldBe ExportPath.Transmux
      plan.output.videoCodec shouldBe VideoCodec.H264
      plan.output.audioCodec shouldBe AudioCodec.Aac

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      assertIs<ExportStatus.Success>(finished)

      // This fixture's own codec is H264, which is also the ladder's first pick, so a re-encode
      // would land on H264 too and the codec alone would not catch a silent fall back to it. File
      // size does: a copy remuxes the container without touching an encoded byte, so it lands
      // within the muxer's own padding of the source, while re-encoding writes a file of a
      // visibly different one.
      ffprobe(output.absolutePath).endsWith("h264 aac") shouldBe true
      val drift = abs(output.length() - fixture.landscape.length())
      assertTrue(
        drift <= CONTAINER_DRIFT_BYTES,
        "the copy wrote ${output.length()} bytes against the source's ${fixture.landscape.length()}",
      )

      output.delete()
    }

  // A trim used to force a re-encode on every backend. A snapWithin wide enough to reach the sync
  // sample before the cut leaves an otherwise untouched clip on the copy path.
  //
  // The long fixture carries a sync sample about every second and the window opens between two of
  // them, so a cut that snapped and one that never moved land on visibly different frames.
  @Test
  fun `opens a copied window on the sync sample the planner resolved`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-snapped-trim", ".mp4").also { it.delete() }
      val source = MediaSource.of(fixture.long.absolutePath)
      val composition =
        compositionOf {
          clip(source) {
            trim(TimeRange.of(TRIM_START, TRIM_END))
            snapWithin(SNAP_WITHIN)
          }
        }

      // Where the planner moved the cut to. Read back off the resolved clip, since that one answer
      // is what every backend lays, and cross-checked against the container's own sample table.
      val opensAt =
        fixture
          .plannedTracks(composition, ExportSpec(), listOf(source))
          .single()
          .clips
          .single()
          .start
      val keyFrame = keyFrameAtOrBefore(fixture.long.absolutePath, TRIM_START)
      assertTrue(keyFrame < TRIM_START, "the window was meant to open between two sync samples")
      opensAt shouldBe keyFrame

      val plan = assertIs<Verdict.Capable>(fixture.filmstrip.plan(composition, ExportSpec())).plan
      plan.path shouldBe ExportPath.Transmux
      plan.copyBlockedBy shouldBe emptyList()

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      val written = assertIs<ExportStatus.Success>(finished).info

      // Nothing was encoded, so the codecs are the source's, and the file is a fraction of it
      // rather than the whole container remuxed.
      ffprobe(output.absolutePath).endsWith("h264 aac") shouldBe true
      assertTrue(
        output.length() < fixture.long.length() / 2,
        "the copy wrote ${output.length()} bytes against the source's ${fixture.long.length()}",
      )

      // Where the output opens, read as pixels rather than as a timestamp: a length alone would
      // pass just as well for a copy that bounded its tail and ignored the seek entirely.
      assertTrue(
        fixture
          .frameAt(output.absolutePath, Duration.ZERO)
          .contentEquals(fixture.frameAt(fixture.long.absolutePath, opensAt)),
        "the copy did not open on the frame at $opensAt that the plan resolved to",
      )

      // Measured from where the copy opened rather than from the cut, since that is the window the
      // plan resolved and the one every backend lays.
      val window = TRIM_END - opensAt
      assertTrue(
        written.duration in (window - COPY_SLOP)..(window + COPY_SLOP),
        "the copy runs for ${written.duration}, where the resolved window is $window",
      )

      output.delete()
    }

  // Zero is the default, and it means the cut lands where it was asked. No stream copy can promise
  // that on a cut which is not already a sync sample, so the export re-encodes and the plan names
  // the term that cost it the copy.
  @Test
  fun `leaves a cut at the default snap where it was asked and re-encodes it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-unsnapped-trim", ".mp4").also { it.delete() }
      val source = MediaSource.of(fixture.long.absolutePath)
      val composition = compositionOf { clip(source) { trim(TimeRange.of(TRIM_START, TRIM_END)) } }

      assertTrue(
        keyFrameAtOrBefore(fixture.long.absolutePath, TRIM_START) < TRIM_START,
        "the cut was meant to sit between two sync samples",
      )
      fixture
        .plannedTracks(composition, ExportSpec(), listOf(source))
        .single()
        .clips
        .single()
        .start shouldBe TRIM_START

      val plan = assertIs<Verdict.Capable>(fixture.filmstrip.plan(composition, ExportSpec())).plan
      plan.path shouldBe ExportPath.Transcode
      plan.copyBlockedBy shouldContain CopyBlocker.TrimNotOnSyncSample

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      val written = assertIs<ExportStatus.Success>(finished).info

      // The snapped window runs longer than the asked one by the whole group of pictures, so a
      // length is enough to say the cut never moved.
      val window = TRIM_END - TRIM_START
      assertTrue(
        written.duration in (window - COPY_SLOP)..(window + COPY_SLOP),
        "the export runs for ${written.duration}, where the asked window is $window",
      )

      output.delete()
    }

  // Snapping only saves the work it costs accuracy for on a copy. An export with an effect on it
  // decodes the leading group of pictures anyway, so the cut lands where it was asked and the plan
  // names the effect rather than the trim as what cost the copy.
  @Test
  fun `keeps a snapped cut precise once an effect forces a re-encode`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val source = MediaSource.of(fixture.long.absolutePath)
      val composition =
        compositionOf {
          clip(source) {
            trim(TimeRange.of(TRIM_START, TRIM_END))
            snapWithin(SNAP_WITHIN)
            effects { brightness(BRIGHTER) }
          }
        }

      val plan = assertIs<Verdict.Capable>(fixture.filmstrip.plan(composition, ExportSpec())).plan

      plan.path shouldBe ExportPath.Transcode
      plan.copyBlockedBy shouldContain CopyBlocker.ClipHasEffects
      fixture
        .plannedTracks(composition, ExportSpec(), listOf(source))
        .single()
        .clips
        .single()
        .start shouldBe TRIM_START
    }

  // ffmpeg refuses a map that matches no stream, and a video-only source has no audio stream for
  // the copy path's `0:a` to match.
  @Test
  fun `copies a video-only clip without refusing the export over the missing audio stream`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val silent = silencedCopyOf(fixture.landscape)
      val output = File.createTempFile("filmstrip-copy-silent", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(silent.absolutePath)) }

      val verdict = fixture.filmstrip.plan(composition, ExportSpec())
      val plan = assertIs<Verdict.Capable>(verdict).plan
      plan.path shouldBe ExportPath.Transmux

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      assertIs<ExportStatus.Success>(finished)

      ffprobe(output.absolutePath) shouldBe "640x360 30/1 h264"

      output.delete()
      silent.delete()
    }

  /**
   * A copy of [source] with its audio stream dropped, made with real ffmpeg since nothing in this
   * backend writes files of its own to derive one from.
   */
  private fun silencedCopyOf(source: File): File {
    val target = File.createTempFile("filmstrip-silent-source", ".mp4")
    ProcessBuilder(
      "ffmpeg",
      "-y",
      "-hide_banner",
      "-loglevel",
      "error",
      "-i",
      source.absolutePath,
      "-an",
      "-c",
      "copy",
      target.absolutePath,
    ).start().waitFor()
    return target
  }

  /**
   * The timestamp of the sync sample at or before [at], read out of the container's own sample
   * table rather than worked out from the interval the fixtures were encoded with.
   *
   * Verified independently of the code under test, the same way [ffprobe] is.
   */
  private fun keyFrameAtOrBefore(
    path: String,
    at: Duration,
  ): Duration {
    val process =
      ProcessBuilder(
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-skip_frame",
        "nokey",
        "-show_entries",
        "frame=pts_time",
        "-of",
        "csv=p=0",
        path,
      ).start()
    val output = process.inputReader().readText()
    process.waitFor()
    return output
      .lineSequence()
      .mapNotNull { it.substringBefore(',').trim().toDoubleOrNull() }
      .map { it.seconds }
      .filter { it <= at }
      .max()
  }

  private companion object {
    // A window inside the long fixture's twelve seconds, opening between two of its sync samples
    // rather than on one, since a cut that already sits on a sync sample snaps nowhere and would
    // pass whether the snap works or not.
    val TRIM_START = 4_500.milliseconds
    val TRIM_END = 7_250.milliseconds

    // Wider than the fixture's own one-second sync interval, so the sample before the cut is
    // reachable with room to spare rather than only just.
    val SNAP_WITHIN = 2.seconds

    // How far a written run may sit from the window the plan resolved. A bounded copy ends on a
    // whole packet rather than on the length it was given, and this build overshoots by around
    // 80ms of it. Still well short of the group of pictures a cut that snapped when it should not
    // have, or did not when it should have, would move by.
    val COPY_SLOP = 200.milliseconds

    // How far a remux may land from the source it copied. The mp4 muxer sizes its own padding, and
    // ffmpeg 6 writes a byte more of it than later builds do. A re-encode of this fixture misses by
    // tens of kilobytes, so the band separates the two readings without pinning a muxer's spelling.
    const val CONTAINER_DRIFT_BYTES = 64L
  }
}
