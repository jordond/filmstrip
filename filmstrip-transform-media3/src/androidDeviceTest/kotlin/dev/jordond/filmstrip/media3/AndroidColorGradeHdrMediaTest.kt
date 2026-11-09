package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HLG_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.assumeEncodesHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * A color matrix on an export that keeps its grade, on real media3.
 *
 * Every assertion is directional. `MediaMetadataRetriever` tone-maps an HDR frame on the way out
 * and never says how, so what a code value there means is not something to hold to a figure. What
 * survives any monotone map is the order of two exports and whether a grey stayed grey, and the
 * effects here are the ones media3's own matrix cannot spell on linear light: a contrast, whose
 * bias lifts black, and a desaturation, which mixes channels.
 *
 * The fixture's corner sits on the pattern's black bar and its centre on a flat orange patch.
 */
class AndroidColorGradeHdrMediaTest {
  private val probe = Media3ExportProbe()

  // A matrix multiplied into linear light and floored at zero leaves black where it was, so a
  // black that comes up under a low contrast is the bias landing where the signal puts it.
  @Test
  fun contrastPivotsOnMidGreyOnAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val flat = probe.frame(export(source, listOf(Contrast(LOW))))
      val steep = probe.frame(export(source, listOf(Contrast(HIGH))))

      assertContrastPivots(flat, steep)
    }

  @Test
  fun desaturationEqualisesTheChannelsOnAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      // Dimmed on both sides so the orange sits inside the retriever's range rather than on its
      // white. The dim alone lowers to media3's own matrix, and the dim with the desaturation folds
      // to one matrix that has to go through the HDR pass, so the pair also says the two agree.
      val toned = probe.frame(export(source, listOf(Brightness(DIM)))).averageAt(CENTRE, CENTRE)
      val grey = probe.frame(export(source, listOf(Brightness(DIM), Saturation(0f)))).averageAt(CENTRE, CENTRE)

      withClue("the desaturated patch reads as black, nothing to compare against") {
        (luminance(grey) > BLACK_CEILING) shouldBe true
      }
      withClue("a desaturated patch still reads as $grey") { (spread(grey) < EQUAL_GAP) shouldBe true }
      withClue("the toned patch $toned is no more colorful than the grey one $grey") {
        (spread(toned) > spread(grey)) shouldBe true
      }
    }

  @Test
  fun aKeptGradeSurvivesAColorMatrix() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val written = export(source, listOf(Contrast(HIGH)))
      val probed = probe.infoOf(MediaSource.of(written.path))
      val video = withClue("the written file has no video track") { probed.video.shouldNotBeNull() }

      withClue("a color matrix on a kept grade wrote an SDR file") { (video.hdrTransfer != null) shouldBe true }
    }

  // HLG is the arm where the pass reads scene light through the system gamma rather than display
  // light, so it gets an export of its own rather than resting on the PQ one.
  @Test
  fun contrastPivotsOnMidGreyOnAKeptHlgGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HLG_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val flat = probe.frame(export(source, listOf(Contrast(LOW))))
      val steep = probe.frame(export(source, listOf(Contrast(HIGH))))

      assertContrastPivots(flat, steep)
    }

  private fun assertContrastPivots(
    flat: Bitmap,
    steep: Bitmap,
  ) {
    val flatBlack = luminance(flat.averageAt(CORNER, CORNER))
    val steepBlack = luminance(steep.averageAt(CORNER, CORNER))
    val flatSpread = luminance(flat.averageAt(CENTRE, CENTRE)) - flatBlack
    val steepSpread = luminance(steep.averageAt(CENTRE, CENTRE)) - steepBlack

    withClue("a contrast of $LOW left black at $flatBlack against $steepBlack") {
      (flatBlack > steepBlack + BLACK_LIFT) shouldBe true
    }
    withClue("a contrast of $HIGH read a spread of $steepSpread against $flatSpread") {
      (steepSpread > flatSpread) shouldBe true
    }
  }

  private suspend fun export(
    source: MediaSource,
    effects: List<EffectSpec>,
  ): File {
    val composition = Composition(listOf(Track(listOf(Clip(source)))), effects)
    return probe.export(composition, ExportSpec(hdr = HdrMode.KeepHdr))
  }

  private fun spread(color: Triple<Int, Int, Int>): Int =
    max(color.first, max(color.second, color.third)) - min(color.first, min(color.second, color.third))

  private companion object {
    const val LOW = 0.5f
    const val HIGH = 2f
    const val DIM = 0.35f

    // The pattern's first bar is black outside the circle, and the corner is well outside it.
    const val CORNER = 0.05f
    const val CENTRE = 0.5f

    // Summed over three channels. A lifted black lands a few dozen above a true black under any
    // tone map, and a grey's channels part by an encoder's chroma noise and no more.
    const val BLACK_LIFT = 12
    const val BLACK_CEILING = 36
    const val EQUAL_GAP = 24
  }
}
