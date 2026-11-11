package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.BRIGHTER
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.ffprobe
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.hostFfprobe
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The copy path and snapped cuts, checked against the sync samples and bytes of the written file.
 */
class FfmpegCopyMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `copies an untouched clip instead of re-encoding it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val output = File.createTempFile("filmstrip-copy", ".mp4").also { it.delete() }
      val composition = compositionOf { clip(MediaSource.of(fixture.landscape.absolutePath)) }

      val verdict = fixture.filmstrip.plan(composition, ExportSpec())
      val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
      plan.path shouldBe ExportPath.Copy
      plan.output.videoCodec shouldBe VideoCodec.H264
      plan.output.audioCodec shouldBe AudioCodec.Aac

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      // This fixture's own codec is H264, which is also the ladder's first pick, so a re-encode
      // would land on H264 too and the codec alone would not catch a silent fall back to it. File
      // size does: a copy remuxes the container without touching an encoded byte, so it lands
      // within the muxer's own padding of the source, while re-encoding writes a file of a
      // visibly different one.
      ffprobe(output.absolutePath).endsWith("h264 aac") shouldBe true
      val drift = abs(output.length() - fixture.landscape.length())
      withClue("the copy wrote ${output.length()} bytes against the source's ${fixture.landscape.length()}") {
        (drift <= CONTAINER_DRIFT_BYTES).shouldBeTrue()
      }

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
      val negotiated =
        fixture
          .plannedLowering(composition, ExportSpec(), listOf(source))
          .export.composition
          .shouldNotBeNull()
      val opensAt =
        negotiated.tracks
          .single()
          .clips
          .single()
          .start
      val keyFrame = keyFrameAtOrBefore(fixture.long.absolutePath, TRIM_START)
      withClue("the window was meant to open between two sync samples") { (keyFrame < TRIM_START).shouldBeTrue() }
      opensAt shouldBe keyFrame

      val plan =
        fixture.filmstrip
          .plan(composition, ExportSpec())
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan
      plan.path shouldBe ExportPath.Copy
      plan.copyBlockedBy shouldBe emptyList()

      fixture.exported(plan, output)

      // Nothing was encoded, so the codecs are the source's, and the file is a fraction of it
      // rather than the whole container remuxed.
      ffprobe(output.absolutePath).endsWith("h264 aac") shouldBe true
      withClue("the copy wrote ${output.length()} bytes against the source's ${fixture.long.length()}") {
        (output.length() < fixture.long.length() / 2).shouldBeTrue()
      }

      // Where the output opens, read as pixels rather than as a timestamp: a length alone would
      // pass just as well for a copy that bounded its tail and ignored the seek entirely.
      withClue("the copy did not open on the frame at $opensAt that the plan resolved to") {
        fixture
          .frameAt(output.absolutePath, Duration.ZERO)
          .contentEquals(fixture.frameAt(fixture.long.absolutePath, opensAt))
          .shouldBeTrue()
      }

      // Counted on the grid of the window the plan resolved, from where the copy opened rather than from the cut, so a
      // copy that ran on past it or stopped short of it writes a different count.
      val prefix =
        negotiated.copyPrefixOf(sourcePackets(fixture.long.absolutePath)).shouldBeInstanceOf<CopyPrefix.Packets>()
      videoPacketTimes(output.absolutePath).size shouldBe prefix.count

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

      withClue("the cut was meant to sit between two sync samples") {
        (keyFrameAtOrBefore(fixture.long.absolutePath, TRIM_START) < TRIM_START).shouldBeTrue()
      }
      val negotiated =
        fixture
          .plannedLowering(composition, ExportSpec(), listOf(source))
          .export.composition
          .shouldNotBeNull()
      negotiated.tracks
        .single()
        .clips
        .single()
        .start shouldBe TRIM_START

      val plan =
        fixture.filmstrip
          .plan(composition, ExportSpec())
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan
      plan.path shouldBe ExportPath.Transcode
      plan.copyBlockedBy shouldContain CopyBlocker.TrimNotOnSyncSample

      fixture.exported(plan, output)

      // The snapped window runs longer than the asked one by the whole group of pictures, so the frame count on the
      // asked window's grid is enough to say the cut never moved.
      videoPacketTimes(output.absolutePath).size.toLong() shouldBe negotiated.videoSlots

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

      val plan =
        fixture.filmstrip
          .plan(composition, ExportSpec())
          .shouldBeInstanceOf<Verdict.Capable>()
          .plan

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
      val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
      plan.path shouldBe ExportPath.Copy

      val finished =
        fixture.filmstrip
          .export(plan, MediaSink.of(output.absolutePath))
          .toList()
          .last()
      if (finished is ExportStatus.Failure) error(finished.error.message)
      finished.shouldBeInstanceOf<ExportStatus.Success>()

      ffprobe(output.absolutePath) shouldBe "640x360 30/1 h264"

      output.delete()
      silent.delete()
    }

  // The long fixture reorders two B-frames behind each reference. The first 30 slots end on a whole reorder group, so
  // the copy writes the grid and nothing past it, where an input bound alone wrote the three frames presented after.
  @Test
  fun `writes exactly the grid when the window ends on a whole reorder group`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val written = copiedWindow(WHOLE_GROUP_END)

      withClue("the copy wrote ${written.packets} video packets on a grid of ${written.slots}") {
        written.packets shouldBe written.slots
      }
      written.slotsCovered shouldBe (0 until written.slots).toList()
      withClue("the audio runs ${written.audio}, short of the ${written.gridEnd} grid") {
        (written.audio >= written.gridEnd).shouldBeTrue()
      }
    }

  // Here the grid ends inside a reorder group, so its last frame decodes after references presented past it. Those
  // are the residue, and it is bounded by the reorder depth the source itself reports.
  @Test
  fun `writes no more than the source's reorder depth past a grid ending inside a reorder group`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val written = copiedWindow(SPLIT_GROUP_END)
      val residue = written.packets - written.slots
      val depth = reorderDepth(fixture.long.absolutePath)

      withClue("the window was meant to end inside a reorder group") { (residue > 0).shouldBeTrue() }
      withClue("the copy wrote $residue frames past the grid, where the source reorders $depth") {
        (residue <= depth).shouldBeTrue()
      }
      written.slotsCovered.take(written.slots) shouldBe (0 until written.slots).toList()
      withClue("the audio runs ${written.audio}, short of the ${written.gridEnd} grid") {
        (written.audio >= written.gridEnd).shouldBeTrue()
      }
    }

  // A window of a frame or two leaves a track with too few packets to time its last one from the one before, so each
  // stream's length is read against the grid.
  @Test
  fun `a copy of a frame or two runs as long as its grid`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      SHORT_ENDS.forEach { end ->
        val written = copiedWindow(end)
        val frame = outputSlotTime(1, written.rate)

        withClue(
          "the copy to $end wrote ${written.packets} packets on ${written.slots} slots, video ${written.video}, " +
            "audio ${written.audio}, reported ${written.duration}, grid ${written.gridEnd}",
        ) {
          (written.packets >= written.slots).shouldBeTrue()
          if (written.packets == written.slots) {
            (written.video - written.gridEnd).absoluteValue shouldBeLessThan CONTAINER_TICK
          }
          (written.video >= written.gridEnd - CONTAINER_TICK).shouldBeTrue()
          // The audio is bound by the window rather than the grid, and a window can end inside its last slot.
          (written.audio >= end - CONTAINER_TICK).shouldBeTrue()
          (written.audio < end + frame).shouldBeTrue()
          val longest = maxOf(written.video, written.audio)
          (written.duration - longest).absoluteValue shouldBeLessThan frame
        }
      }
    }

  /**
   * Copies the long fixture from its start to [end] and reads back what the file holds against the plan's grid.
   */
  private suspend fun copiedWindow(end: Duration): CopiedWindow {
    val output = File.createTempFile("filmstrip-copy-grid", ".mp4").also { it.delete() }
    val source = MediaSource.of(fixture.long.absolutePath)
    val composition = compositionOf { clip(source) { trim(TimeRange.of(Duration.ZERO, end)) } }

    val negotiated =
      fixture
        .plannedLowering(composition, ExportSpec(), listOf(source))
        .export.composition
        .shouldNotBeNull()
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

    val rate = negotiated.output.frameRate
    val presented = videoPacketTimes(output.absolutePath)
    val written =
      CopiedWindow(
        slots = negotiated.videoSlots.toInt(),
        packets = presented.size,
        slotsCovered = presented.sorted().map { outputSlotOf(it, rate).toInt() },
        gridEnd = outputSlotTime(negotiated.videoSlots, rate),
        audio = audioDuration(output.absolutePath),
        video = probed(output.absolutePath, "v:0", "stream=duration").trim().toDouble().seconds,
        duration = finished.info.duration,
        rate = rate,
      )
    output.delete()
    return written
  }

  /**
   * What a copy wrote, read with ffprobe rather than through anything under test.
   *
   * @property slots How many slots the plan's grid holds.
   * @property packets How many video packets the file holds.
   * @property slotsCovered The slot each written frame is presented on, in presentation order.
   * @property gridEnd Where the grid's last slot ends.
   * @property audio How long the audio stream runs.
   * @property video How long the video stream runs.
   * @property duration How long the finished export reports the file runs.
   * @property rate The plan's frame rate.
   */
  private class CopiedWindow(
    val slots: Int,
    val packets: Int,
    val slotsCovered: List<Int>,
    val gridEnd: Duration,
    val audio: Duration,
    val video: Duration,
    val duration: Duration,
    val rate: Int,
  )

  private fun videoPacketTimes(path: String): List<Duration> =
    probed(path, "v:0", "packet=pts_time")
      .lineSequence()
      .mapNotNull { it.substringBefore(',').trim().toDoubleOrNull() }
      .map { it.seconds }
      .toList()

  // The source's video packets in decode order, read with ffprobe rather than through the backend's own read.
  private fun sourcePackets(path: String): List<CopyPacket> =
    probed(path, "v:0", "packet=pts_time,flags")
      .lineSequence()
      .filter { it.isNotBlank() }
      .map { line ->
        CopyPacket(
          line
            .substringBefore(',')
            .trim()
            .toDouble()
            .seconds,
          line.substringAfter(',').startsWith('K'),
        )
      }.toList()

  private fun audioDuration(path: String): Duration = probed(path, "a:0", "stream=duration").trim().toDouble().seconds

  // How many frames the source's decoder holds back to put them in presentation order.
  private fun reorderDepth(path: String): Int = probed(path, "v:0", "stream=has_b_frames").trim().toInt()

  private fun probed(
    path: String,
    stream: String,
    entries: String,
  ): String {
    val process =
      ProcessBuilder(
        hostFfprobe,
        "-v",
        "error",
        "-select_streams",
        stream,
        "-show_entries",
        entries,
        "-of",
        "csv=p=0",
        path,
      ).start()
    val output = process.inputReader().readText()
    process.waitFor()
    return output
  }

  /**
   * A copy of [source] with its audio stream dropped, made with real ffmpeg since nothing in this
   * backend writes files of its own to derive one from.
   */
  private fun silencedCopyOf(source: File): File {
    val target = File.createTempFile("filmstrip-silent-source", ".mp4")
    ProcessBuilder(
      hostFfmpeg,
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
        hostFfprobe,
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

    // How far a remux may land from the source it copied. The mp4 muxer sizes its own padding, and
    // builds differ by a byte or so in it. A re-encode of this fixture misses by
    // tens of kilobytes, so the band separates the two readings without pinning a muxer's spelling.
    const val CONTAINER_DRIFT_BYTES = 64L

    // Two copy windows from the long fixture's start. Thirty slots close on a whole reorder group, and thirty one close
    // with the frame on the last of them decoding behind two references presented past it.
    // From under one audio access unit to two slots, the second of which decodes behind references past the grid.
    val SHORT_ENDS = listOf(21.milliseconds, 22.milliseconds, 40.milliseconds, 67.milliseconds)

    // The coarsest a container records a stream's length to.
    val CONTAINER_TICK = 1.milliseconds

    val WHOLE_GROUP_END = 1_010.milliseconds
    val SPLIT_GROUP_END = 1_040.milliseconds
  }
}
