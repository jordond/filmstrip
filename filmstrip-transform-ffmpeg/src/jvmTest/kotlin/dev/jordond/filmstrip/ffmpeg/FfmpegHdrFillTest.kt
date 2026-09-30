package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.ExportConstants.MICROS_PER_SECOND
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREY
import dev.jordond.filmstrip.transform.internal.tenBitCodesFromSignal
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Solid fills on a kept HDR grade, read back as ten-bit codes against where the shared HDR light puts the colour.
 *
 * The HDR fixture is contained in a portrait frame and starts late, so one export carries both a letterbox bar and a
 * leading gap. Every expected code comes from [authoredHdrNits], [signalFromNits] and [tenBitCodesFromSignal].
 */
class FfmpegHdrFillTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a solid fill on a kept PQ grade lands in the bar and the gap where the shared light puts it`() =
    runTest(timeout = TIMEOUT) { assertFillLandings(HdrTransfer.Pq) }

  @Test
  fun `a solid fill on a kept HLG grade lands in the bar and the gap where the shared light puts it`() =
    runTest(timeout = TIMEOUT) { assertFillLandings(HdrTransfer.Hlg) }

  @Test
  fun `a looped PQ fill holds every frame of the export on the grid at the shared light`() =
    runTest(timeout = TIMEOUT) { assertLoopedFill(HdrTransfer.Pq, passes = 1) }

  @Test
  fun `a PQ fill after a gap holds every frame of two and three joined passes on the grid`() =
    runTest(timeout = TIMEOUT) { JOINED_PASSES.forEach { assertLoopedFill(HdrTransfer.Pq, it) } }

  @Test
  fun `an HLG fill after a gap holds every frame of two and three joined passes on the grid`() =
    runTest(timeout = TIMEOUT) { JOINED_PASSES.forEach { assertLoopedFill(HdrTransfer.Hlg, it) } }

  private suspend fun assertLoopedFill(
    transfer: HdrTransfer,
    passes: Int,
  ) {
    if (!fixture.encodesHdr()) return
    val exported = exportedWithGap(transfer, MID_GREY, passes)
    val plan = exported.plan
    val rate = assertNotNull(plan.output.frameRate, "the plan resolved no frame rate")
    val slots = outputSlotOf(plan.duration, rate)
    val what = "a $transfer fill over $passes passes and ${plan.duration}"

    val frames = writtenFrames(exported.file.absolutePath)
    assertOnTheGrid(frames, slots, rate, what)
    val written = writtenVideoLength(exported.file)
    val halfFrame = 1.seconds / rate / 2
    assertTrue(
      (written - plan.duration).absoluteValue <= halfFrame,
      "$what: the video stream runs $written where the plan runs ${plan.duration}",
    )

    val expected = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(MID_GREY)))
    val bar = lowerBarOf(plan.output.size)
    val misses =
      listOf(0, slots.toInt() / 2, slots.toInt() - 1).mapNotNull { index ->
        missOf(tenBitCodesAt(exported.file, bar, opensOn(index, plan)), expected, "$what: bar on frame $index")
      }
    exported.file.delete()
    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  private suspend fun assertFillLandings(transfer: HdrTransfer) {
    if (!fixture.encodesHdr()) return
    val misses =
      listOf(MID_GREY, MID_GREEN).flatMap { colour ->
        val exported = exportedWithGap(transfer, colour)
        val size = exported.plan.output.size
        val expected = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(colour)))
        val bar = tenBitCodesAt(exported.file, lowerBarOf(size), BAR_AT)
        val gap = tenBitCodesAt(exported.file, pointIn(HALF, HALF, size))
        exported.file.delete()

        val name = "$transfer ${colour.toUInt().toString(16)}"
        listOfNotNull(
          missOf(bar, expected, "$name bar"),
          missOf(gap, expected, "$name gap"),
        )
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  private fun missOf(
    read: IntArray,
    expected: IntArray,
    what: String,
  ): String? =
    "$what read ${read.toList()}, expected ${expected.toList()}"
      .takeUnless { read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE } }

  private fun lowerBarOf(size: Size): Pair<Int, Int> = pointIn(HALF, 1f, size).let { (x, y) -> x to y - 2 * PROBE_SIDE }

  private fun writtenVideoLength(file: File): Duration {
    val process =
      ProcessBuilder(
        "ffprobe",
        "-v",
        "error",
        "-select_streams",
        "v:0",
        "-show_entries",
        "stream=duration",
        "-of",
        "csv=p=0",
        file.absolutePath,
      ).start()
    val seconds =
      process
        .inputReader()
        .readText()
        .trim()
        .toDouble()
    process.waitFor()
    return (seconds * MICROS_PER_SECOND).roundToLong().microseconds
  }

  private suspend fun exportedWithGap(
    transfer: HdrTransfer,
    fill: Int,
    passes: Int = 1,
  ): ExportedOverlay {
    val source = if (transfer == HdrTransfer.Pq) fixture.hdr else fixture.hdrHlg
    val output = File.createTempFile("filmstrip-hdr-fill", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        track {
          repeat(passes) { clip(MediaSource.of(source.absolutePath)) }
          startAt(GAP_LEAD)
        }
        effects { crop(AspectRatio.Portrait, fit = Fit.Contain) }
        fill(Fill.Solid(fill))
      }
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, ExportSpec(hdr = HdrMode.KeepHdr))) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }
    val finished =
      fixture.filmstrip
        .export(plan, MediaSink.of(output.absolutePath))
        .toList()
        .last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    assertIs<ExportStatus.Success>(finished)
    return ExportedOverlay(output, plan)
  }

  private companion object {
    // The ten-bit tolerance the Apple and media3 HDR fill suites hold a flat colour to, which leaves room for the
    // encoder's rounding on a flat block and nothing more.
    const val CODE_TOLERANCE = 2

    // The fixture runs two seconds, so the gap and the picture after it both hold a run of frames.
    val GAP_LEAD = 500.milliseconds
    val BAR_AT = 1500.milliseconds

    // Passes of the fixture joined after the gap, so the fill runs under a concat as well as a single clip.
    val JOINED_PASSES = listOf(2, 3)
  }
}
