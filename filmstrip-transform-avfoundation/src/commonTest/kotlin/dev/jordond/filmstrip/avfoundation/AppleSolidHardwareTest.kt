package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Solid colours exported by AVFoundation, read back from the first frame in the codes the file holds.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSolidHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `only solids export at a fixed frame in their colour`() =
    runTest(timeout = TIMEOUT) {
      val output = temporaryPath("solid-only")
      val edit =
        compositionOf {
          solid(AuthoredColors.MID_GREEN, 1.seconds)
          frame(OutputFrame.Fixed(FIXED))
        }

      val success = exported(edit, output)

      success.info.video?.displaySize shouldBe FIXED
      assertSolid(codesOf(output), AuthoredColors.channelsOf(AuthoredColors.MID_GREEN), "the solid")
      remove(output)
    }

  @Test
  fun `a leading solid covers the frame of the clip after it`() =
    runTest(timeout = TIMEOUT) {
      val output = temporaryPath("solid-lead")
      val edit =
        compositionOf {
          solid(AuthoredColors.MID_GREY, 1.seconds)
          clip(MediaSource.of(fixture("apple_export_a.mp4"))) { trim(0.seconds, 1.seconds) }
        }

      exported(edit, output)

      assertSolid(codesOf(output), AuthoredColors.channelsOf(AuthoredColors.MID_GREY), "the leading solid")
      remove(output)
    }

  // A translucent solid on the primary track is composited over the fill, the same as a translucent still.
  @Test
  fun `a translucent solid shows the fill through it`() =
    runTest(timeout = TIMEOUT) {
      val output = temporaryPath("solid-alpha")
      val edit =
        compositionOf {
          solid(HALF_WHITE, 1.seconds)
          fill(Fill.Solid(BLACK))
          frame(OutputFrame.Fixed(FIXED))
        }

      exported(edit, output)

      assertSolid(codesOf(output), listOf(128, 128, 128), "half white over black")
      remove(output)
    }

  @Test
  fun `solids ahead of an HDR clip land where the shared light puts them on both transfers`() =
    runTest(timeout = TIMEOUT) {
      HdrTransfer.entries.forEach { transfer ->
        val output = temporaryPath("solid-hdr-$transfer")
        val edit =
          compositionOf {
            solid(AuthoredColors.MID_GREY, 1.seconds)
            solid(AuthoredColors.MID_GREEN, 1.seconds)
            clip(MediaSource.of(hdrFixture(transfer)))
          }

        exported(edit, output, HDR_SPEC)

        listOf(AuthoredColors.MID_GREY to FIRST_AT, AuthoredColors.MID_GREEN to SECOND_AT).forEach { (color, at) ->
          val expected = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(color))).toList()
          assertCodes(output, at, expected, "$transfer solid ${color.toUInt().toString(16)}")
        }
        remove(output)
      }
    }

  private fun assertCodes(
    path: String,
    at: Duration,
    expected: List<Int>,
    what: String,
  ) {
    val read = (hdrFrameOf(path, at) ?: error("could not decode $path at $at")).codesAt(0.5f, 0.5f)
    withClue("$what read $read, expected $expected") {
      read.zip(expected).all { (got, want) -> abs(got - want) <= FILL_CODE_TOLERANCE }.shouldBeTrue()
    }
  }

  private fun assertSolid(
    frame: FrameProbe,
    expected: List<Int>,
    what: String,
  ) {
    listOf(0.5f to 0.5f, CORNER to CORNER, 1f - CORNER to 1f - CORNER).forEach { (x, y) ->
      val read = frame.average(x, y).toList()
      withClue("$what read $read at $x,$y, expected $expected") {
        read.zip(expected).all { (got, want) -> abs(got - want) <= AuthoredColors.TOLERANCE }.shouldBeTrue()
      }
    }
  }

  private suspend fun exported(
    composition: Composition,
    output: String,
    spec: ExportSpec = ExportSpec(),
  ): ExportStatus.Success =
    withContext(Dispatchers.Default) {
      val statuses = filmstrip.export(composition, spec, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
      }
    }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    val FIXED = Size(320, 240)
    const val CORNER = 0.05f
    const val HALF_WHITE = 0x80FFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    val HDR_SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.KeepHdr, bitrate = COLOR_READ_BITRATE)
    val FIRST_AT = 500.milliseconds
    val SECOND_AT = 1500.milliseconds
  }
}
