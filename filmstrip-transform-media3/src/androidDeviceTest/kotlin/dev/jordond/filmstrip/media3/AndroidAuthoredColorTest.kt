package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.AuthoredColors.COLORS
import dev.jordond.filmstrip.test.AuthoredColors.TOLERANCE
import dev.jordond.filmstrip.test.AuthoredColors.channelsOf
import dev.jordond.filmstrip.test.AuthoredColors.colorManaged
import dev.jordond.filmstrip.test.AuthoredColors.colorManagedFromFrame
import dev.jordond.filmstrip.test.writeAtomically
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/**
 * Authored colors, checked for landing in an SDR export as their own codes.
 *
 * A fill color, an overlay image's pixels and a text plate's color are each written with no color
 * management, so a channel authored at 128 reads back at 128. The colors and the tolerance come from
 * [dev.jordond.filmstrip.test.AuthoredColors], whose tolerance sits under half the distance between an
 * authored code and where color matching it in either direction would write it, so a backend doing
 * that match fails here.
 *
 * Each reading is the YCbCr the encoder wrote, turned into RGB through the matrix and range the file records, so an
 * encoder that converts through another matrix than the file records fails here. An export that reports the file was
 * tagged BT.601 for its encoder is checked against the tag the file carries, and an HEVC export is read the same way.
 *
 * Skipped when the fixtures are absent, as in [AndroidExportTest].
 */
class AndroidAuthoredColorTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aSolidFillLandsInABarAsItsOwnCodes() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      COLORS.forEach { argb ->
        val written = written(letterboxed(source, Fill.Solid(argb)))

        BAR_COLUMNS.forEach { x -> written.assertReads(argb, x, barMiddle, "the fill in the bar at $x") }
      }
    }

  @Test
  fun aSolidFillLandsInAnHevcBarAsItsOwnCodes() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val hevc = ExportSpec(heightPx = Media3ExportProbe.SPEC.heightPx, videoCodec = VideoCodec.Hevc)
      assumeTrue(
        "this device writes no HEVC",
        probe.plan(letterboxed(source, Fill.Solid(COLORS.first())), hevc).output.videoCodec == VideoCodec.Hevc,
      )

      COLORS.forEach { argb ->
        val written = written(letterboxed(source, Fill.Solid(argb)), hevc)

        written.assertReads(argb, BAR_COLUMNS[1], barMiddle, "the HEVC fill in the bar")
      }
    }

  @Test
  fun anOpaqueOverlayImageLandsAsItsOwnCodes() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      COLORS.forEach { argb ->
        val swatch = ImageOverlay(ImageSource.of(swatchFile(argb).path), Corner.BottomEnd, scale = SWATCH_SCALE)
        val written = written(plain(source, swatch))
        val frameSize = written.codes.size
        val rect = swatch.placedOn(frameSize, SWATCH).rectOn(frameSize)

        written.assertReads(argb, (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f, "the overlay image")
      }
    }

  @Test
  fun aTextPlateLandsAsItsOwnCodes() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      COLORS.forEach { argb ->
        // The glyphs share the plate's color, so anywhere inside the plate reads the authored color.
        val caption =
          TextOverlay(
            text = "HELLO",
            style = TextStyle(fontSize = FONT_SIZE, color = argb, backgroundColor = argb),
            anchor = Anchor.Center,
          )
        val written = written(plain(source, caption))

        // Text is placed by the same point in the block and the frame, so a centred block's middle
        // sits on the anchor whatever size the platform rasterised it at.
        written.assertReads(argb, caption.anchor.x, caption.anchor.y, "the text plate")
      }
    }

  // The codes the export of [composition] at [spec] wrote. The matrix and range a retag reports have to be the ones the
  // file records, so a BT.601 retag records matrix 5, and an export reporting none has to record BT.709 at limited
  // range.
  private suspend fun written(
    composition: EditComposition,
    spec: ExportSpec = Media3ExportProbe.SPEC,
  ): Written {
    val finished = probe.finish(probe.plan(composition, spec))
    val written = probe.writtenBy(finished)
    val reported = finished.adjustments.firstOrNull { it.kind == AdjustmentKind.ColorSpaceChanged }
    val space = if (written.matrix == BT601_PAL_MATRIX) ColorSpace.Bt601 else ColorSpace.Bt709
    val range = if (written.fullRange) "full" else "limited"
    withClue(
      "the export's retag against matrix ${written.matrix} and full range ${written.fullRange} its file records",
    ) {
      (reported?.resolved ?: UNTAGGED) shouldBe "${space.name} at $range range"
    }
    return written
  }

  private fun Written.assertReads(
    argb: Int,
    fx: Float,
    fy: Float,
    what: String,
  ) {
    val authored = channelsOf(argb)
    val stored = codes.codesAt(fx, fy).toList()
    val read = rgbAt(fx, fy).toList()

    authored.zip(read).forEach { (expected, actual) ->
      withClue(
        "$what read $read from $stored through matrix $matrix where $authored was authored, tolerance " +
          "$TOLERANCE. Color managed it would read ${authored.map { colorManaged(it) }} or " +
          "${authored.map { colorManagedFromFrame(it) }}",
      ) {
        (abs(actual - expected) < TOLERANCE) shouldBe true
      }
    }
  }

  // Halfway down the bar a square crop of the landscape clip leaves above the picture. Read once the fixture is copied.
  private val barMiddle by lazy {
    val clip = probe.frame(File(probe.context.cacheDir, CLIP))
    (1f - clip.height.toFloat() / clip.width) / 4f
  }

  private fun letterboxed(
    source: MediaSource,
    fill: Fill,
  ) = EditComposition(
    listOf(Track(listOf(Clip(source)))),
    listOf<EffectSpec>(Crop(AspectRatio.Square, fit = Fit.Contain)),
    fill = fill,
  )

  private fun plain(
    source: MediaSource,
    overlay: EffectSpec,
  ) = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(overlay))

  private fun swatchFile(argb: Int): File {
    val file = File(probe.context.cacheDir, "swatch-${Integer.toHexString(argb)}.png")
    if (file.exists()) return file
    val bitmap = Bitmap.createBitmap(SWATCH_PX, SWATCH_PX, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(argb)
    file.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    return file
  }

  private companion object {
    val BAR_COLUMNS = listOf(0.25f, 0.5f, 0.75f)

    const val SWATCH_PX = 64
    val SWATCH = Size(SWATCH_PX, SWATCH_PX)
    const val SWATCH_SCALE = 0.3f
    const val FONT_SIZE = 0.18f

    // The H.273 matrix COLOR_STANDARD_BT601_PAL reads through, the standard a BT.601 retag writes.
    const val BT601_PAL_MATRIX = 5
    val UNTAGGED = "${ColorSpace.Bt709.name} at limited range"
  }
}
