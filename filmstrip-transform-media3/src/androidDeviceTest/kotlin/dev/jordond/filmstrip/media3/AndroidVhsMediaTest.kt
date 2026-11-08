package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.CODE_PATCH
import dev.jordond.filmstrip.test.firstEightBitFrame
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/**
 * Where a [Vhs] lands in a real export: what it reaches at each scope, and what stays clean.
 *
 * The figures read here are large enough to survive an encoder. How close each pixel lands to the signal is read off
 * the shader's own output in the filters module, with no encoder in the way.
 *
 * A tone mapped HDR source cannot run on the emulator, which has no ten-bit HEVC decoder. That test says so and skips
 * there, and runs on a device that has one.
 */
class AndroidVhsMediaTest {
  private val probe = Media3ExportProbe { filters() }

  @Test
  fun aCompositionVhsReachesTheBars() =
    runTest(timeout = TIMEOUT) {
      val vhs = Vhs()
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)

      val taped = greyOfBar(barsEdit(Clip(probe.fixture()), listOf(crop, vhs)))
      val plain = greyOfBar(barsEdit(Clip(probe.fixture()), listOf(crop)))

      taped - plain shouldBeGreaterThan halfLift(vhs)
    }

  @Test
  fun aClipVhsLeavesTheCompositionBarsAlone() =
    runTest(timeout = TIMEOUT) {
      val vhs = Vhs()
      val crop = Crop(AspectRatio.Square, fit = Fit.Contain)

      val taped = greyOfBar(barsEdit(Clip(probe.fixture(), effects = listOf(vhs)), listOf(crop)))
      val plain = greyOfBar(barsEdit(Clip(probe.fixture()), listOf(crop)))

      abs(taped - plain) shouldBeLessThan halfLift(vhs)
    }

  @Test
  fun aClipVhsMovesThePictureIntoACanvasBarAndLeavesTheFillAlone() =
    runTest(timeout = TIMEOUT) {
      assertCanvasRead(Fill.Solid(BAR_FILL), Fill.Solid(BAR_FILL))
    }

  // A blurred fill turns a canvas's bars black, so the same crop over a black fill is what they should read.
  @Test
  fun aClipVhsLeavesABlurredFillsCanvasBarsBlackAndMovesThePictureOverThem() =
    runTest(timeout = TIMEOUT) {
      assertCanvasRead(Fill.Blurred(), Fill.Solid(BLACK))
    }

  @Test
  fun anOverlayAtTheSameScopeStaysClean() =
    runTest(timeout = TIMEOUT) {
      val vhs = Vhs()
      val swatch = swatchOverlay()
      val taped = EditComposition(listOf(Track(listOf(Clip(probe.fixture())))), listOf(swatch, vhs))
      val plain = EditComposition(listOf(Track(listOf(Clip(probe.fixture())))), listOf(swatch))

      abs(greyOfSwatch(taped) - greyOfSwatch(plain)) shouldBeLessThan halfLift(vhs)
    }

  @Test
  fun aClipOverlayUnderACompositionVhsIsBurned() =
    runTest(timeout = TIMEOUT) {
      val vhs = Vhs()
      val swatch = swatchOverlay()
      val clip = Clip(probe.fixture(), effects = listOf(swatch))
      val taped = EditComposition(listOf(Track(listOf(clip))), listOf(vhs))
      val plain = EditComposition(listOf(Track(listOf(clip))))

      abs(greyOfSwatch(taped) - greyOfSwatch(plain)) shouldBeGreaterThan halfLift(vhs)
    }

  @Test
  fun aToneMappedHdrSourceExportsUnderAVhs() =
    runTest(timeout = TIMEOUT) {
      assumeTrue("this device has no ten-bit HEVC decoder, which is every emulator", decodesTenBitHevc())
      val edit = EditComposition(listOf(Track(listOf(Clip(probe.fixture(HDR_CLIP))))), listOf(Vhs()))

      val finished = probe.finish(probe.plan(edit))

      finished.adjustments.any { it.kind == AdjustmentKind.HdrToneMapped } shouldBe true
    }

  // The SD head runs ahead of the clip's chain, so the tape reads the clip's corrected colour at either scope. Reads
  // average a grid across the middle of the frame, which an encoder moves less than it does one patch.
  @Test
  fun aVhsOnAnSdClipReadsTheSignalOverTheClipsCorrectedColourAtBothScopes() =
    runTest(timeout = TIMEOUT) {
      val source = File(probe.context.cacheDir, "vhs-sd.mp4")
      probe.writeBarsClip(source, SOURCE.width, SOURCE.height, SD_FLAT, MediaFormat.COLOR_STANDARD_BT601_NTSC)
      val stored = Written(firstEightBitFrame(source), SMPTE_C_PRIMARIES, fullRange = false).rgbAt(0.5f, 0.5f).signal()
      val shown = sdSourceDisplayed(stored, SMPTE_C_PRIMARIES)
      withClue("the correction has to be wider than the tolerance, ${stored.toList()} to ${shown.toList()}") {
        abs(shown[0] - stored[0]) * MAX_CODE shouldBeGreaterThan SD_CODES
      }
      val vhs = Vhs(intensity = 0.5f)
      val clip = Clip(MediaSource.of(source.path))
      mapOf(
        "clip" to EditComposition(listOf(Track(listOf(Clip(MediaSource.of(source.path), effects = listOf(vhs)))))),
        "composition" to EditComposition(listOf(Track(listOf(clip))), listOf(vhs)),
      ).forEach { (scope, edit) ->
        val plan = probe.plan(edit, ExportSpec())
        val finished = probe.finish(plan)
        val written = probe.writtenBy(finished)
        val size = plan.output.size
        val primaries = written.primariesFrom(finished)
        withClue("$scope scope writes BT.709 or reports the retag, file matrix ${written.matrix}") {
          primaries shouldBe BT709
        }
        val points = SD_GRID.flatMap { fx -> SD_GRID.map { fy -> fx to fy } }
        val read =
          points
            .map { (fx, fy) ->
              sdSourceDisplayed(written.rgbAt(fx, fy).signal(), primaries)
            }.meanOfChannels()
        val wanted =
          points
            .map { (fx, fy) ->
              val row = VhsSignal.virtualRow((size.height * fy).toInt(), size.height)
              val x = ((size.width * fx).toInt() + 0.5) / size.width
              val tick = VhsSignal.tickAt(0.0)
              VhsSignal.pixelAt(x, row, tick, vhs.seed, size.width, VhsParams.of(vhs)) { shown }
            }.meanOfChannels()
        read.indices.forEach { channel ->
          withClue("API ${Build.VERSION.SDK_INT} $scope scope read ${read.toList()} against ${wanted.toList()}") {
            abs(read[channel] - wanted[channel]) * MAX_CODE shouldBeLessThan SD_CODES
          }
        }
      }
    }

  private fun Triple<Int, Int, Int>.signal(): DoubleArray =
    doubleArrayOf(first / MAX_CODE, second / MAX_CODE, third / MAX_CODE)

  private fun List<DoubleArray>.meanOfChannels(): DoubleArray =
    DoubleArray(first().size) { c -> map { it[c] }.average() }

  private fun barsEdit(
    clip: Clip,
    effects: List<EffectSpec>,
  ) = EditComposition(listOf(Track(listOf(clip))), effects, fill = Fill.Solid(BLACK))

  // Reads the canvas [fill] leaves under a clip Vhs against the same crop over [plainFill] with no Vhs.
  private suspend fun assertCanvasRead(
    fill: Fill,
    plainFill: Fill,
  ) {
    val vhs = Vhs(intensity = 0.5f)
    val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
    val edit = { effects: List<EffectSpec>, over: Fill ->
      EditComposition(listOf(Track(listOf(Clip(probe.fixture(), effects = effects)))), fill = over)
    }
    // The frame's own height, since the head band is a few rows on a shorter one and the probe's default is shorter.
    val spec = ExportSpec()
    val plan = probe.plan(edit(listOf(crop, vhs), fill), spec)
    val written = probe.writtenBy(probe.finish(plan))
    val plain = probe.writtenBy(probe.finish(probe.plan(edit(listOf(crop), plainFill), spec)))
    val content = placementOf(SOURCE, listOf(listOf(crop)), plan.output.size).drawnRect
    // A read averages a small patch at the foot of the frame, and the head band's shift grows down it, so a column is
    // picked to hold for every row in it.
    val height = plan.output.size.height
    val y = 1f - (CODE_PATCH / 2).toFloat() / height
    val shiftAt = { fromBottom: Int ->
      val row = VhsSignal.virtualRow(height - fromBottom, height)
      VhsSignal.rowShift(vhs.seed, VhsSignal.tickAt(0.0), row, VhsParams.of(vhs)).toFloat()
    }
    val least = shiftAt(CODE_PATCH)
    val most = shiftAt(1)
    withClue("the head band has to reach into the bar for this to tell, $least on a $height row frame") {
      (least > MIN_SHIFT) shouldBe true
    }

    val movedIn = written.rgbAt(content.right + least / 2, y)
    val movedPlain = plain.rgbAt(content.right + least / 2, y)
    withClue("picture moved into the right bar read $movedIn against $movedPlain") {
      movedIn.widestGapTo(movedPlain) shouldBeGreaterThan PICTURE_APART
    }
    val clear =
      mapOf(
        "past the moved picture" to Pair(content.right + most + MIN_SHIFT, y),
        "the start of the picture's own rect" to Pair(content.left + least / 2, y),
        "a bar on a row with no head band" to Pair(content.right + least / 2, 0.5f),
      )
    clear.forEach { (what, at) ->
      val read = written.rgbAt(at.first, at.second)
      // The same bar's read with no Vhs, on the same row, since the plain frame still holds its picture at the rect.
      val wanted = plain.rgbAt(content.right + least / 2, at.second)
      withClue("$what read $read against $wanted") {
        abs(read.mean() - wanted.mean()) shouldBeLessThan halfLift(vhs)
      }
    }
  }

  private suspend fun greyOfBar(edit: EditComposition): Double = probe.written(edit).rgbAt(0.5f, BAR_MIDDLE).mean()

  private suspend fun greyOfSwatch(edit: EditComposition): Double =
    probe.written(edit).rgbAt(SWATCH_FX, SWATCH_FY).mean()

  // A read tells lifted from clean when it lands closer to its own side of this.
  private fun halfLift(vhs: Vhs): Double = VhsParams.of(vhs).lift * MAX_CODE / 2

  // Chroma noise moves any one channel by more than the lift a read looks for.
  private fun Triple<Int, Int, Int>.mean(): Double = (first + second + third) / 3.0

  private fun Triple<Int, Int, Int>.widestGapTo(other: Triple<Int, Int, Int>): Int =
    maxOf(abs(first - other.first), abs(second - other.second), abs(third - other.third))

  private fun swatchOverlay(): ImageOverlay {
    val file = File(probe.context.cacheDir, "vhs-swatch.png")
    if (!file.exists()) {
      val bitmap = Bitmap.createBitmap(SWATCH_PX, SWATCH_PX, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(SWATCH_COLOR)
      file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return ImageOverlay(ImageSource.of(file.path), Corner.TopStart, scale = SWATCH_SCALE)
  }

  private fun decodesTenBitHevc(): Boolean {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720)
    format.setInteger(MediaFormat.KEY_PROFILE, HEVC_MAIN_10)
    return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) != null
  }

  private companion object {
    const val BLACK = 0xFF000000.toInt()
    const val SWATCH_COLOR = 0xFF202020.toInt()
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f
    const val SWATCH_FX = 0.15f
    const val SWATCH_FY = 0.2f
    const val BAR_MIDDLE = 0.1f
    const val HEVC_MAIN_10 = 2
    val WIDE_CANVAS = AspectRatio(3, 1)
    const val BAR_FILL = 0xFF2F4F6F.toInt()
    const val MIN_SHIFT = 0.01f
    val SOURCE = Size(640, 360)

    const val MAX_CODE = 255.0
    const val BT709 = 1

    // Two H.264 encodes of one frame move a colour by a few codes on their own.
    const val SD_CODES = 4.0
    val SD_GRID = listOf(0.4f, 0.45f, 0.5f, 0.55f, 0.6f)

    // Mid-range, with green holding the frame up, which is where SMPTE C and BT.709 are furthest apart.
    val SD_FLAT = listOf(floatArrayOf(0.2f, 0.65f, 0.25f))

    // Picture moved over a flat bar, which is far past what the encoder does to the bar on its own.
    const val PICTURE_APART = 16
  }
}
