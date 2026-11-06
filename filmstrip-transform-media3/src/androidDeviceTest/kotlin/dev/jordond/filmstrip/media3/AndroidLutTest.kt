package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.assumeDecodesTenBitHevc
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/**
 * Where a [Lut] lands in a real export: what it reaches at each scope, and what stays clean.
 *
 * Each read is held against [CubeLut.at] of the same picture written with no [Lut], so the fixture's encoder is in the
 * wanted value. How close a pixel lands with no encoder is read off the shader's own output in the filters module.
 */
@OptIn(InternalFilmstripApi::class)
class AndroidLutTest {
  private val probe = Media3ExportProbe { filters() }

  @Test
  fun aCompositionLutReadsLikeTheTable() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip())
      assertOnTheTable(EditComposition(listOf(Track(listOf(clip))), listOf(Lut(CURVED))), clip, CURVED)
    }

  @Test
  fun aClipLutReadsLikeTheTable() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()
      val looked = Clip(source, effects = listOf(Lut(CURVED)))
      assertOnTheTable(EditComposition(listOf(Track(listOf(looked)))), Clip(source), CURVED)
    }

  @Test
  fun aMidIntensityReadsLikeTheBlendedTable() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip())
      val edit = EditComposition(listOf(Track(listOf(clip))), listOf(Lut(CURVED, MID_INTENSITY)))
      assertOnTheTable(edit, clip, CURVED.blended(MID_INTENSITY))
    }

  @Test
  fun aSmallerExportReadsTheSameTable() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip())
      val edit = EditComposition(listOf(Track(listOf(clip))), listOf(Lut(CURVED)))
      assertOnTheTable(edit, clip, CURVED, ExportSpec(heightPx = SMALL_HEIGHT))
    }

  @Test
  fun aClipLutLeavesACanvasBarAsItsFill() =
    runTest(timeout = TIMEOUT) {
      assertBarClean(Fill.Solid(BAR_FILL), Fill.Solid(BAR_FILL))
    }

  // A blurred fill turns a canvas's bars black, so the same crop over a black fill is what they should read.
  @Test
  fun aClipLutLeavesABlurredFillsCanvasBarsBlack() =
    runTest(timeout = TIMEOUT) {
      assertBarClean(Fill.Blurred(), Fill.Solid(BLACK))
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest(timeout = TIMEOUT) {
      val source = flatClip()
      val swatch = swatchOverlay()
      val looked = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(swatch, Lut(CURVED)))
      val plain = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(swatch))

      val read = redOfSwatch(looked)
      val wanted = redOfSwatch(plain)

      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeLessThan ENCODER_CODES }
    }

  @Test
  fun aClipOverlayUnderACompositionLutIsBurned() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(flatClip(), effects = listOf(swatchOverlay()))
      val looked = EditComposition(listOf(Track(listOf(clip))), listOf(Lut(CURVED)))
      val plain = EditComposition(listOf(Track(listOf(clip))))

      val read = redOfSwatch(looked)
      val wanted = redOfSwatch(plain)

      withClue("the swatch read $read against $wanted") { abs(read - wanted) shouldBeGreaterThan ENCODER_CODES }
    }

  // LutEffect refuses a frame processor in HDR by name, so a finished export says the frame processor got useHdr=false.
  @Test
  fun aLutOverAToneMappedClipRunsInSdr() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      assumeDecodesTenBitHevc()
      val spec = ExportSpec(heightPx = HDR_HEIGHT, hdr = HdrMode.ToneMapToSdr)
      val looked = EditComposition(listOf(Track(listOf(Clip(source, effects = listOf(Lut(CURVED)))))))

      val finished = probe.finish(probe.plan(looked, spec))

      val path = finished.output.shouldBeInstanceOf<MediaSink.Path>().path
      withClue("the export kept an HDR transfer") {
        probe
          .infoOf(MediaSource.of(path))
          .video
          .shouldNotBeNull()
          .hdrTransfer
          .shouldBeNull()
      }
      val plain = probe.written(EditComposition(listOf(Track(listOf(Clip(source))))), spec).rgbAt(READ_FX, READ_FY)
      assertReads(probe.writtenBy(finished).rgbAt(READ_FX, READ_FY), plain, CURVED)
    }

  private suspend fun assertOnTheTable(
    edit: EditComposition,
    clip: Clip,
    table: CubeLut,
    spec: ExportSpec = ExportSpec(),
  ) {
    val plain = probe.written(EditComposition(listOf(Track(listOf(clip)))), spec).rgbAt(READ_FX, READ_FY)
    val want = table.at(plain.first / FULL, plain.second / FULL, plain.third / FULL)
    withClue("the table has to clear what an encoder moves for this to tell, ${want.toList()} over $plain") {
      abs(want[0] * FULL - plain.first).toDouble() shouldBeGreaterThan TABLE_CODES
    }
    assertReads(probe.written(edit, spec).rgbAt(READ_FX, READ_FY), plain, table)
  }

  private fun assertReads(
    read: Triple<Int, Int, Int>,
    plain: Triple<Int, Int, Int>,
    table: CubeLut,
  ) {
    val want = table.at(plain.first / FULL, plain.second / FULL, plain.third / FULL)
    withClue("the read $read against ${want.toList()} of $plain") {
      abs(read.first - want[0] * FULL).toDouble() shouldBeLessThan TABLE_CODES
      abs(read.second - want[1] * FULL).toDouble() shouldBeLessThan TABLE_CODES
      abs(read.third - want[2] * FULL).toDouble() shouldBeLessThan TABLE_CODES
    }
  }

  private suspend fun assertBarClean(
    fill: Fill,
    plainFill: Fill,
  ) {
    val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
    val source = flatClip()
    val edit = { effects: List<EffectSpec>, over: Fill ->
      EditComposition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = over)
    }
    val spec = ExportSpec()
    val plan = probe.plan(edit(listOf(crop, Lut(CURVED)), fill), spec)
    val written = probe.writtenBy(probe.finish(plan))
    val plain = probe.writtenBy(probe.finish(probe.plan(edit(listOf(crop), plainFill), spec)))
    val rect = placementOf(SOURCE, listOf(listOf(crop)), plan.output.size).drawnRect

    val fx = rect.right + (1f - rect.right) / 2f
    val read = written.rgbAt(fx, 0.5f)
    val wanted = plain.rgbAt(fx, 0.5f)
    withClue("the bar read $read against $wanted") {
      abs(read.first - wanted.first).toDouble() shouldBeLessThan BAR_CODES
      abs(read.second - wanted.second).toDouble() shouldBeLessThan BAR_CODES
      abs(read.third - wanted.third).toDouble() shouldBeLessThan BAR_CODES
    }
  }

  private suspend fun flatClip(): MediaSource {
    val file = File(probe.context.cacheDir, "lut-flat.mp4")
    if (!file.exists()) probe.writeBarsClip(file, SOURCE.width, SOURCE.height, listOf(floatArrayOf(0.7f, 0.5f, 0.3f)))
    return MediaSource.of(file.path)
  }

  private suspend fun redOfSwatch(edit: EditComposition): Double =
    probe
      .written(edit, ExportSpec())
      .rgbAt(SWATCH_FX, SWATCH_FY)
      .first
      .toDouble()

  private fun swatchOverlay(): ImageOverlay {
    val file = File(probe.context.cacheDir, "lut-swatch.png")
    if (!file.exists()) {
      val bitmap = Bitmap.createBitmap(SWATCH_PX, SWATCH_PX, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(SWATCH_COLOR)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SWATCH_SCALE)
  }

  private companion object {
    val SOURCE = Size(640, 360)
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val FULL = 255f
    const val BLACK = 0xFF000000.toInt()
    const val BAR_FILL = 0xFF2F4F6F.toInt()
    const val SMALL_HEIGHT = 240
    const val HDR_HEIGHT = 360
    const val MID_INTENSITY = 0.5f
    const val READ_FX = 0.5f
    const val READ_FY = 0.5f

    const val SWATCH_COLOR = 0xFFCCCCCC.toInt()
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f

    // What an encoder moves a flat read by.
    const val ENCODER_CODES = 5.0
    const val BAR_CODES = 2.0

    // An encoder on each of the two exports a read compares.
    const val TABLE_CODES = 8.0

    // Cross-talk in every channel, so a read at mid-range tells trilinear from anything simpler.
    val CURVED =
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
  }
}
