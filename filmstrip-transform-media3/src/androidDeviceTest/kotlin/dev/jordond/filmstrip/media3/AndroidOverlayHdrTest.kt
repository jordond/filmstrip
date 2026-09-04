package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Overlays on an export that keeps its HDR grade, on real media3.
 *
 * `OverlayShaderProgram` sorts overlays into HDR families by type, and checks `TextOverlay` before
 * `BitmapOverlay` because the first extends the second. `RasterOverlay` extends `TextOverlay` to
 * land on the one branch that takes a plain SDR bitmap onto an HDR frame. A plain `BitmapOverlay`
 * there is read as UltraHDR, which wants API 34, wants a gainmap on the bitmap and spends two
 * sampler slots. Nothing in media3 documents that sorting, so these tests are what says it still
 * holds after a bump.
 *
 * The pixel arm is the same difference measurement [AndroidOverlayTest] makes, because
 * `MediaMetadataRetriever` tone-maps an HDR frame on the way out without saying how, and only a
 * region compared against the same region of the same frame of a plain export survives that.
 *
 * Skipped when the fixtures are absent, or on a device that writes no HDR, as in [AndroidHdrTest].
 */
class AndroidOverlayHdrTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val filmstrip = Filmstrip(context) { media3Backend() }

  @Test
  fun aWatermarkLandsOnAKeptPqGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val plain = frame(export(source, emptyList()))
      val marked = frame(export(source, listOf(watermark(Corner.BottomEnd))))

      assertTrue(marked.gainedOver(plain, BOTTOM_END) > COVERED, "no watermark on a kept PQ grade")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the watermark bled into the far corner")
    }

  // HLG holds scene light where PQ holds display light, and media3 carries the two through
  // different working spaces, so the transfer the overlay is blended into is a separate answer.
  @Test
  fun aWatermarkLandsOnAKeptHlgGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture(HLG_CLIP) ?: return@runTest
      if (!encodesHdr()) return@runTest

      val plain = frame(export(source, emptyList()))
      val marked = frame(export(source, listOf(watermark(Corner.BottomEnd))))

      assertTrue(marked.gainedOver(plain, BOTTOM_END) > COVERED, "no watermark on a kept HLG grade")
      assertTrue(marked.gainedOver(plain, TOP_START) < UNTOUCHED, "the watermark bled into the far corner")
    }

  @Test
  fun textIsBurnedInOnAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val plain = frame(export(source, emptyList()))
      val captioned = frame(export(source, listOf(caption(Anchor.TopStart))))

      assertTrue(captioned.gainedOver(plain, TOP_START) > COVERED, "no burned-in text on a kept grade")
      assertTrue(captioned.gainedOver(plain, BOTTOM_END) < UNTOUCHED, "the caption reached the opposite corner")
    }

  // Both overlay kinds resolve to the same RasterOverlay, so this is the check that gathering two of
  // them into one OverlayEffect still lands both once the frame is ten-bit.
  @Test
  fun aWatermarkAndTextShareOnePassOnAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val plain = frame(export(source, emptyList()))
      val both = frame(export(source, listOf(watermark(Corner.BottomEnd), caption(Anchor.TopStart))))

      assertTrue(both.gainedOver(plain, BOTTOM_END) > COVERED, "the watermark did not survive sharing a pass")
      assertTrue(both.gainedOver(plain, TOP_START) > COVERED, "the text did not survive sharing a pass")
    }

  @Test
  fun anOverlayDoesNotCostTheGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val written = export(source, listOf(watermark(Corner.BottomEnd)))
      val probed = assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(written.path)))
      val video = assertNotNull(probed.info.video, "the written file has no video track")

      assertTrue(video.hdrTransfer != null, "an overlay on a kept grade wrote an SDR file")
    }

  // The overlay keeps its sRGB values on a BT.2020 frame, which is a real difference from what was
  // authored, so the plan has to say so rather than degrade silently.
  @Test
  fun anOverlayOnAKeptGradeIsReportedAsApproximated() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val composition = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(watermark(Corner.BottomEnd)))
      val verdict = filmstrip.plan(composition, SPEC)

      val degraded = assertIs<Verdict.Degraded>(verdict)
      assertTrue(
        degraded.adjustments.any { it.kind == AdjustmentKind.EffectApproximated },
        "an overlay on HDR was planned without reporting its colour space: ${degraded.adjustments}",
      )
    }

  private suspend fun encodesHdr(): Boolean =
    (filmstrip.capabilities() as? CapabilitiesResult.Success)?.capabilities?.supportsHdrEncoding == true

  private fun watermark(corner: Corner) =
    ImageOverlay(ImageSource.of(badgeFile(context).path), corner, DEFAULT_MARGIN, BADGE_SCALE, 1f, null)

  private fun caption(anchor: Anchor) =
    TextOverlay(
      text = "HELLO",
      style = TextStyle(fontSize = 0.18f, color = Color.WHITE, backgroundColor = BADGE_COLOR),
      anchor = anchor,
    )

  private suspend fun export(
    source: MediaSource,
    effects: List<EffectSpec>,
  ): File {
    val composition = EditComposition(listOf(Track(listOf(Clip(source)))), effects)
    val plan =
      when (val verdict = filmstrip.plan(composition, SPEC)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("refused: ${verdict.reasons.map { it.message }}")
      }

    val statuses = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.temporary()).toList() }
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) throw AssertionError("export failed: ${finished.error.message}")
    return File((assertIs<ExportStatus.Success>(finished).output as MediaSink.Path).path)
  }

  private fun frame(video: File): Bitmap = frameOf(video, MID_CLIP)

  private fun fixture(clip: String = CLIP): MediaSource? {
    val stream = javaClass.classLoader?.getResourceAsStream(clip) ?: return null
    val file = File(context.cacheDir, clip)
    stream.use { input -> file.outputStream().use(input::copyTo) }
    return MediaSource.of(file.path)
  }

  private companion object {
    val TIMEOUT = 5.minutes
    val SPEC = ExportSpec(targetHeight = 360, hdr = HdrMode.KeepHdr)
    val MID_CLIP = 1_000.milliseconds

    const val CLIP = "android_export_hdr.mp4"
    const val HLG_CLIP = "android_export_hdr_hlg.mp4"

    const val DEFAULT_MARGIN = 0.02f

    val TOP_START = Region(0f, 0f, 0.3f, 0.3f)
    val BOTTOM_END = Region(0.7f, 0.7f, 1f, 1f)
  }
}
