package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * A colour matrix on an export that keeps its grade, on real media3.
 *
 * Every assertion is directional. `MediaMetadataRetriever` tone-maps an HDR frame on the way out
 * and never says how, so what a code value there means is not something to hold to a figure. What
 * survives any monotone map is the order of two exports and whether a grey stayed grey, and the
 * effects here are the ones media3's own matrix cannot spell on linear light: a contrast, whose
 * bias lifts black, and a desaturation, which mixes channels.
 *
 * The fixture's corner sits on the pattern's black bar and its centre on a flat orange patch.
 */
class AndroidColorGradeHdrTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val filmstrip = Filmstrip(context) { media3Backend() }

  // A matrix multiplied into linear light and floored at zero leaves black where it was, so a
  // black that comes up under a low contrast is the bias landing where the signal puts it.
  @Test
  fun contrastPivotsOnMidGreyOnAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val flat = frameOf(export(source, listOf(Contrast(LOW))), MID_CLIP)
      val steep = frameOf(export(source, listOf(Contrast(HIGH))), MID_CLIP)

      assertContrastPivots(flat, steep)
    }

  @Test
  fun desaturationEqualisesTheChannelsOnAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      // Dimmed on both sides so the orange sits inside the retriever's range rather than on its
      // white. The dim alone lowers to media3's own matrix, and the dim with the desaturation folds
      // to one matrix that has to go through the HDR pass, so the pair also says the two agree.
      val toned = frameOf(export(source, listOf(Brightness(DIM))), MID_CLIP).averageAt(CENTRE, CENTRE)
      val grey = frameOf(export(source, listOf(Brightness(DIM), Saturation(0f))), MID_CLIP).averageAt(CENTRE, CENTRE)

      assertTrue(luminance(grey) > BLACK_CEILING, "the desaturated patch reads as black, nothing to compare against")
      assertTrue(spread(grey) < EQUAL_GAP, "a desaturated patch still reads as $grey")
      assertTrue(spread(toned) > spread(grey), "the toned patch $toned is no more colourful than the grey one $grey")
    }

  @Test
  fun aKeptGradeSurvivesAColourMatrix() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val written = export(source, listOf(Contrast(HIGH)))
      val probed = assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(written.path)))
      val video = assertNotNull(probed.info.video, "the written file has no video track")

      assertTrue(video.hdrTransfer != null, "a colour matrix on a kept grade wrote an SDR file")
    }

  // HLG is the arm where the pass reads scene light through the system gamma rather than display
  // light, so it gets an export of its own rather than resting on the PQ one.
  @Test
  fun contrastPivotsOnMidGreyOnAKeptHlgGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture(HLG_CLIP) ?: return@runTest
      if (!encodesHdr()) return@runTest

      val flat = frameOf(export(source, listOf(Contrast(LOW))), MID_CLIP)
      val steep = frameOf(export(source, listOf(Contrast(HIGH))), MID_CLIP)

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

    assertTrue(flatBlack > steepBlack + BLACK_LIFT, "a contrast of $LOW left black at $flatBlack against $steepBlack")
    assertTrue(steepSpread > flatSpread, "a contrast of $HIGH read a spread of $steepSpread against $flatSpread")
  }

  private suspend fun export(
    source: MediaSource,
    effects: List<EffectSpec>,
  ): File {
    val composition = EditComposition(listOf(Track(listOf(Clip(source)))), effects)
    val plan =
      when (val verdict = filmstrip.plan(composition, ExportSpec(hdr = HdrMode.KeepHdr))) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("refused: ${verdict.reasons.map { it.message }}")
      }

    val statuses = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.temporary()).toList() }
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) throw AssertionError("export failed: ${finished.error.message}")
    return File((assertIs<ExportStatus.Success>(finished).output as MediaSink.Path).path)
  }

  private fun spread(color: Triple<Int, Int, Int>): Int =
    max(color.first, max(color.second, color.third)) - min(color.first, min(color.second, color.third))

  private suspend fun encodesHdr(): Boolean =
    (filmstrip.capabilities() as? CapabilitiesResult.Success)?.capabilities?.supportsHdrEncoding == true

  private fun fixture(clip: String = CLIP): MediaSource? {
    val stream = javaClass.classLoader?.getResourceAsStream(clip) ?: return null
    val file = File(context.cacheDir, clip)
    stream.use { input -> file.outputStream().use(input::copyTo) }
    return MediaSource.of(file.path)
  }

  private companion object {
    val TIMEOUT = 5.minutes
    val MID_CLIP = 1_000.milliseconds

    const val CLIP = "android_export_hdr.mp4"
    const val HLG_CLIP = "android_export_hdr_hlg.mp4"
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
