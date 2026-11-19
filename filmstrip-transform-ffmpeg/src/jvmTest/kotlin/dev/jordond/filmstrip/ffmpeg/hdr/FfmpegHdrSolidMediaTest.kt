package dev.jordond.filmstrip.ffmpeg.hdr

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.CompositionBuilder
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_HDR_SPEC
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.ExportedOverlay
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.pointIn
import dev.jordond.filmstrip.ffmpeg.support.tenBitCodesAt
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREY
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Solids ahead of an HDR clip on a kept grade, read back as ten-bit codes against where the shared HDR light puts
 * their color.
 *
 * Every expected code comes from [authoredHdrNits], [signalFromNits] and [tenBitCodesFromSignal].
 */
class FfmpegHdrSolidMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `solids ahead of a PQ clip land where the shared light puts them`() =
    runTest(timeout = TIMEOUT) { assertSolidLandings(HdrTransfer.Pq) }

  @Test
  fun `solids ahead of an HLG clip land where the shared light puts them`() =
    runTest(timeout = TIMEOUT) { assertSolidLandings(HdrTransfer.Hlg) }

  private suspend fun assertSolidLandings(transfer: HdrTransfer) {
    val exported =
      exported(transfer) {
        solid(MID_GREY, SLOT)
        solid(MID_GREEN, SLOT)
      }
    val centre = pointIn(HALF, HALF, exported.plan.output.size)
    val misses =
      listOf(MID_GREY to FIRST_AT, MID_GREEN to SECOND_AT).mapNotNull { (color, at) ->
        val expected = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(color)))
        missOf(tenBitCodesAt(exported.file, centre, at), expected, "$transfer ${color.toUInt().toString(16)}")
      }
    exported.file.delete()

    withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
  }

  private fun missOf(
    read: IntArray,
    expected: IntArray,
    what: String,
  ): String? =
    "$what read ${read.toList()}, expected ${expected.toList()}"
      .takeUnless { read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE } }

  // The solids lead and a slot of the HDR fixture follows, so the kept grade comes from the clip alone.
  private suspend fun exported(
    transfer: HdrTransfer,
    solids: CompositionBuilder.() -> Unit,
  ): ExportedOverlay {
    val source = if (transfer == HdrTransfer.Pq) fixture.hdr else fixture.hdrHlg
    val output = File.createTempFile("filmstrip-hdr-solid", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        solids()
        clip(MediaSource.of(source.absolutePath)) { trim(Duration.ZERO, SLOT) }
        frame(OutputFrame.Fixed(FRAME))
      }
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, COLOR_READ_HDR_SPEC)) {
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
    finished.shouldBeInstanceOf<ExportStatus.Success>()
    return ExportedOverlay(output, plan)
  }

  private companion object {
    val SLOT = 200.milliseconds
    val FIRST_AT = SLOT / 2
    val SECOND_AT = SLOT + SLOT / 2
    val FRAME = Size(320, 180)
  }
}
