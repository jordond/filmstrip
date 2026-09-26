package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.AuthoredColours.COLOURS
import dev.jordond.filmstrip.test.AuthoredColours.TOLERANCE
import dev.jordond.filmstrip.test.AuthoredColours.channelsOf
import dev.jordond.filmstrip.test.AuthoredColours.colourManaged
import dev.jordond.filmstrip.test.AuthoredColours.colourManagedFromFrame
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Authored colours, checked for landing in an SDR export as their own codes.
 *
 * A fill colour, an overlay image's pixels and a text plate's colour are each written with no colour
 * management, so a channel authored at 128 reads back at 128. The colours and the tolerance come from
 * [dev.jordond.filmstrip.test.AuthoredColours], whose tolerance sits under half the distance between an
 * authored code and where colour matching it in either direction would write it, so a backend doing
 * that match fails here.
 *
 * Skipped when the fixtures are absent, as in [AndroidExportTest].
 */
class AndroidAuthoredColourTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aSolidFillLandsInABarAsItsOwnCodes() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val clip = probe.frame(File(probe.context.cacheDir, CLIP))
      // A square crop of a landscape clip leaves this much of the output above the picture.
      val bar = (1f - clip.height.toFloat() / clip.width) / 2f

      COLOURS.forEach { argb ->
        val written = probe.frame(probe.export(letterboxed(source, Fill.Solid(argb))))

        BAR_COLUMNS.forEach { x -> written.assertReads(argb, x, bar / 2f, "the fill in the bar at $x") }
      }
    }

  @Test
  fun anOpaqueOverlayImageLandsAsItsOwnCodes() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      COLOURS.forEach { argb ->
        val swatch = ImageOverlay(ImageSource.of(swatchFile(argb).path), Corner.BottomEnd, scale = SWATCH_SCALE)
        val written = probe.frame(probe.export(plain(source, swatch)))
        val frameSize = Size(written.width, written.height)
        val rect = swatch.placedOn(frameSize, SWATCH).rectOn(frameSize)

        written.assertReads(argb, (rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f, "the overlay image")
      }
    }

  @Test
  fun aTextPlateLandsAsItsOwnCodes() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()

      COLOURS.forEach { argb ->
        // The glyphs share the plate's colour, so anywhere inside the plate reads the authored colour.
        val caption =
          TextOverlay(
            text = "HELLO",
            style = TextStyle(fontSize = FONT_SIZE, color = argb, backgroundColor = argb),
            anchor = Anchor.Center,
          )
        val written = probe.frame(probe.export(plain(source, caption)))

        // Text is placed by the same point in the block and the frame, so a centred block's middle
        // sits on the anchor whatever size the platform rasterised it at.
        written.assertReads(argb, caption.anchor.x, caption.anchor.y, "the text plate")
      }
    }

  private fun Bitmap.assertReads(
    argb: Int,
    fx: Float,
    fy: Float,
    what: String,
  ) {
    val authored = channelsOf(argb)
    val read = averageAt(fx, fy).toList()

    authored.zip(read).forEach { (expected, actual) ->
      assertTrue(
        abs(actual - expected) < TOLERANCE,
        "$what read $read where $authored was authored, tolerance $TOLERANCE. Colour managed it would read " +
          "${authored.map { colourManaged(it) }} or ${authored.map { colourManagedFromFrame(it) }}",
      )
    }
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
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    return file
  }

  private companion object {
    val BAR_COLUMNS = listOf(0.25f, 0.5f, 0.75f)

    const val SWATCH_PX = 64
    val SWATCH = Size(SWATCH_PX, SWATCH_PX)
    const val SWATCH_SCALE = 0.3f
    const val FONT_SIZE = 0.18f
  }
}
