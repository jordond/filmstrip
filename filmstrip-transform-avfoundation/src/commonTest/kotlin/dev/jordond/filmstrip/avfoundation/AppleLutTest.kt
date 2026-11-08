package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Where a [Lut] lands in a real Apple export: what it reaches at each scope, and what stays clean.
 *
 * Reads here are of what survives an encoder, held against [CubeLut.at] of the same read with no [Lut]. How close each
 * pixel lands is read off the kernel's own output in the filters module.
 */
class AppleLutTest {
  private val filmstrip =
    Filmstrip {
      avFoundationBackend()
      filters()
    }

  private val table = curvedCube()

  @Test
  fun aCompositionLutReadsLikeTheTable() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()

      assertLanded(source, EditComposition(listOf(Track(listOf(Clip(source)))), listOf(Lut(table))))
    }

  @Test
  fun aClipLutReadsLikeTheTable() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()

      assertLanded(source, EditComposition(listOf(Track(listOf(Clip(source, effects = listOf(Lut(table))))))))
    }

  @Test
  fun aCompositionLutReachesTheBars() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatClip()
      val edit = { effects: List<EffectSpec> ->
        EditComposition(listOf(Track(listOf(Clip(source)))), effects, fill = Fill.Solid(BAR_FILL))
      }

      val plain = export(edit(listOf(crop)), "bar-plain")
      val looked = export(edit(listOf(crop, Lut(table))), "bar-lut")

      val under = codesOf(plain).average(BAR_FX, CENTRE)
      val read = codesOf(looked).average(BAR_FX, CENTRE)
      val want =
        table.at(
          under.first.toFloat() / MAX_CODE,
          under.second.toFloat() / MAX_CODE,
          under.third.toFloat() / MAX_CODE,
        )
      withClue("the table has to move the bar past the encoder, it read ${read.first} over ${under.first}") {
        abs(read.first.toDouble() - under.first.toDouble()) shouldBeGreaterThan WRITTEN_COLOUR_CODES
      }
      abs(read.first.toDouble() - want[0] * MAX_CODE) shouldBeLessThan WRITTEN_COLOUR_CODES
      remove(plain)
      remove(looked)
    }

  @Test
  fun aClipLutLeavesACanvasBarAsItsFill() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = flatClip()
      val edit = { effects: List<EffectSpec> ->
        EditComposition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = Fill.Solid(BAR_FILL))
      }

      val plain = export(edit(listOf(crop)), "canvas-plain")
      val looked = export(edit(listOf(crop, Lut(table))), "canvas-lut")

      val read = frameOf(looked).average(BAR_FX, CENTRE).first.toDouble()
      val wanted = frameOf(plain).average(BAR_FX, CENTRE).first.toDouble()
      withClue("the bar read $read against $wanted") { abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
      remove(plain)
      remove(looked)
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()
      val clean = export(EditComposition(listOf(Track(listOf(Clip(source)))), listOf(swatch())), "overlay-plain")
      val looked =
        export(EditComposition(listOf(Track(listOf(Clip(source)))), listOf(Lut(table), swatch())), "overlay-lut")

      val read = frameOf(looked).average(SWATCH_FX, SWATCH_FY).first.toDouble()
      val wanted = frameOf(clean).average(SWATCH_FX, SWATCH_FY).first.toDouble()
      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
      remove(clean)
      remove(looked)
    }

  @Test
  fun aToneMappedExportReadsLikeTheTable() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(MediaSource.of(hdrFixture(HdrTransfer.Pq)))
      val plain = export(EditComposition(listOf(Track(listOf(clip)))), "hdr-plain", TONE_MAP)
      val looked = export(EditComposition(listOf(Track(listOf(clip))), listOf(Lut(table))), "hdr-lut", TONE_MAP)

      val under = codesOf(plain).average(CENTRE, CENTRE)
      val read = codesOf(looked).average(CENTRE, CENTRE)
      val want =
        table.at(
          under.first.toFloat() / MAX_CODE,
          under.second.toFloat() / MAX_CODE,
          under.third.toFloat() / MAX_CODE,
        )
      withClue("the red read ${read.first} against ${want[0] * MAX_CODE}") {
        abs(read.first.toDouble() - want[0] * MAX_CODE) shouldBeLessThan WRITTEN_COLOUR_CODES
      }
      remove(plain)
      remove(looked)
    }

  @Test
  fun anHdrSourceIsToneMappedAndReportedUnderALut() =
    runTest(timeout = TIMEOUT) {
      val edit =
        EditComposition(listOf(Track(listOf(Clip(MediaSource.of(hdrFixture(HdrTransfer.Pq)))))), listOf(Lut(table)))

      val verdict = filmstrip.plan(edit, ExportSpec(heightPx = HDR_HEIGHT)).shouldBeInstanceOf<Verdict.Degraded>()

      verdict.adjustments.map { it.kind } shouldContain AdjustmentKind.HdrToneMapped
    }

  private suspend fun assertLanded(
    source: MediaSource,
    edit: EditComposition,
  ) {
    val looked = export(edit, "table-lut")
    val plain = export(EditComposition(listOf(Track(listOf(Clip(source))))), "table-plain")

    val under = codesOf(plain).average(CENTRE, CENTRE)
    val read = codesOf(looked).average(CENTRE, CENTRE)
    val want =
      table.at(
        under.first.toFloat() / MAX_CODE,
        under.second.toFloat() / MAX_CODE,
        under.third.toFloat() / MAX_CODE,
      )

    withClue("the table has to clear what an encoder moves, it read ${read.first} over ${under.first}") {
      abs(want[0] * MAX_CODE - under.first.toDouble()) shouldBeGreaterThan WRITTEN_COLOUR_CODES
    }
    withClue("red read ${read.first} against ${want[0] * MAX_CODE}") {
      abs(read.first.toDouble() - want[0] * MAX_CODE) shouldBeLessThan WRITTEN_COLOUR_CODES
    }
    withClue("green read ${read.second} against ${want[1] * MAX_CODE}") {
      abs(read.second.toDouble() - want[1] * MAX_CODE) shouldBeLessThan WRITTEN_COLOUR_CODES
    }
    remove(looked)
    remove(plain)
  }

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(solidPng(SWATCH_PX, SWATCH_PX, Triple(SWATCH_CODE, SWATCH_CODE, SWATCH_CODE))),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  private suspend fun flatClip(): MediaSource {
    val path = NSTemporaryDirectory() + "filmstrip-lut-flat.mp4"
    remove(path)
    val primaries = AVVideoColorPrimaries_ITU_R_709_2.orEmpty()
    val matrix = AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty()
    withContext(
      Dispatchers.Default,
    ) { writeTaggedBars(path, FRAMES, primaries, matrix, listOf(Triple(MID, MID, MID))) }
    return MediaSource.of(path)
  }

  private suspend fun export(
    edit: EditComposition,
    name: String,
    spec: ExportSpec = ExportSpec(),
  ): String {
    val path = NSTemporaryDirectory() + "filmstrip-lut-$name.mp4"
    remove(path)
    val status =
      withContext(Dispatchers.Default) { filmstrip.export(edit, spec, MediaSink.of(path)).toList().last() }
    status.shouldBeInstanceOf<ExportStatus.Success>()
    return path
  }

  @OptIn(ExperimentalForeignApi::class)
  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, null)
  }

  private fun curvedCube(): CubeLut =
    CubeLut.parse(
      buildString {
        append("LUT_3D_SIZE 5\n")
        for (blue in 0..4) {
          for (green in 0..4) {
            for (red in 0..4) {
              val r = red / 4.0
              val g = green / 4.0
              val b = blue / 4.0
              append(r * r * 0.7 + b * 0.2).append(' ')
              append(g * (1.0 - r * 0.4) + b * b * 0.1).append(' ')
              append((1.0 - b) * 0.5 + g * r * 0.4).append('\n')
            }
          }
        }
      },
    )

  private companion object {
    val TIMEOUT = 3.minutes
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val FRAMES = 15
    const val CENTRE = 0.5f
    const val BAR_FX = 0.1f
    const val BAR_FILL = 0xFF2F4F6F.toInt()
    const val MID = 120
    const val MAX_CODE = 255f
    const val HDR_HEIGHT = 720
    val TONE_MAP = ExportSpec(heightPx = HDR_HEIGHT, hdr = HdrMode.ToneMapToSdr)

    const val SWATCH_CODE = 204
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f
  }
}
