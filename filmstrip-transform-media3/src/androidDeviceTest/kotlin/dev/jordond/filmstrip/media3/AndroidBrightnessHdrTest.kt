package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.media3.effect.RgbAdjustment
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.brightnessSceneGain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Brightness on an export that keeps its grade, on real media3.
 *
 * The gain assertions are the contract: media3 holds PQ as display light and HLG as scene light, so
 * the same factor reaches the colour matrix as two different numbers, both taken from the shared
 * functions. They run on a device rather than on the host because building an `RgbAdjustment` calls
 * into `android.opengl.Matrix`.
 *
 * The pixel arm is directional only. `MediaMetadataRetriever` tone-maps an HDR frame on the way out
 * and never says how, so what a code value there means is not something to assert an exact figure
 * against.
 */
class AndroidBrightnessHdrTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val filmstrip = Filmstrip(context) { media3Backend() }
  private val resolver = BuiltInEffectResolver()

  @Test
  fun anSdrFrameIsMultipliedAsAuthored() {
    gainFor(HALF, transfer = null) shouldBeNear HALF
  }

  @Test
  fun aPqFrameIsMultipliedByTheDisplayGain() {
    gainFor(HALF, HdrTransfer.Pq) shouldBeNear brightnessDisplayGain(HALF)
  }

  @Test
  fun anHlgFrameIsMultipliedBySceneGain() {
    gainFor(HALF, HdrTransfer.Hlg) shouldBeNear brightnessSceneGain(HALF)
  }

  @Test
  fun theTwoGradesDoNotShareAGain() {
    val pq = gainFor(HALF, HdrTransfer.Pq)
    val hlg = gainFor(HALF, HdrTransfer.Hlg)

    assertTrue(abs(pq - hlg) > GAIN_GAP, "one gain served both transfer functions, so one of them is wrong")
  }

  @Test
  fun everyChannelCarriesTheSameGain() {
    val matrix = matrixFor(HALF, HdrTransfer.Hlg)

    matrix[RED] shouldBeNear matrix[GREEN]
    matrix[GREEN] shouldBeNear matrix[BLUE]
  }

  // The arm that says a brightness effect on a kept grade reaches the encoder and lands in the
  // right direction.
  //
  // Two dimmed exports rather than one against an untouched reference. The retriever's tone map
  // clips a bright pixel to white, and the fixture's centre is bright enough that an untouched
  // frame and a halved one both come back saturated.
  @Test
  fun dimmingFurtherDarkensAKeptGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val half = luminanceOf(export(source, HALF))
      val quarter = luminanceOf(export(source, QUARTER))

      assertTrue(half > BLACK_CEILING, "the reference frame reads as black, nothing to compare against")
      assertTrue(quarter < half, "a quarter brightness read $quarter against $half at half")
    }

  @Test
  fun aKeptGradeSurvivesABrightnessEffect() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val written = assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(export(source, HALF).path)))
      val video = assertNotNull(written.info.video, "the written file has no video track")

      assertTrue(video.hdrTransfer != null, "a brightness on a kept grade wrote an SDR file")
    }

  // HLG is the arm where the backends disagree about what linear means, so it gets an export of its
  // own rather than resting on the PQ one and the gain assertions above.
  @Test
  fun dimmingFurtherDarkensAKeptHlgGrade() =
    runTest(timeout = TIMEOUT) {
      val source = fixture(HLG_CLIP) ?: return@runTest
      if (!encodesHdr()) return@runTest

      val half = luminanceOf(export(source, HALF))
      val quarter = luminanceOf(export(source, QUARTER))

      assertTrue(half > BLACK_CEILING, "the reference frame reads as black, nothing to compare against")
      assertTrue(quarter < half, "a quarter brightness read $quarter against $half at half")
    }

  private fun gainFor(
    factor: Float,
    transfer: HdrTransfer?,
  ): Float = matrixFor(factor, transfer)[RED]

  private fun matrixFor(
    factor: Float,
    transfer: HdrTransfer?,
  ): FloatArray {
    val attributes =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
        hdrTransfer = transfer,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 1.seconds),
      )
    val capabilities =
      RenderCapabilities(
        api = RenderApi.OpenGlEs,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = transfer != null,
        colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
        maxTextureSize = 8_192,
        features = emptySet(),
      )
    val resolution = resolver.resolve(Brightness(factor), capabilities, attributes)
    val effect = assertIs<EffectResolution.Resolved>(resolution).effect.handle

    return assertIs<RgbAdjustment>(effect).getMatrix(0L, transfer != null)
  }

  private suspend fun export(
    source: MediaSource,
    factor: Float,
  ): File {
    // The effect is carried at every factor, one at 1f included, so the reference frame came out of
    // the same encoder rather than off the copy path.
    val composition = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(Brightness(factor)))
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

  private fun luminanceOf(video: File): Int {
    val bitmap = frame(video)
    val left = (bitmap.width - PATCH) / 2
    val top = (bitmap.height - PATCH) / 2
    var total = 0
    for (x in left until left + PATCH) {
      for (y in top until top + PATCH) {
        val pixel = bitmap.getPixel(x, y)
        total += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
      }
    }

    return total / (PATCH * PATCH)
  }

  private fun frame(video: File): Bitmap {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(video.path)
      requireNotNull(retriever.getFrameAtTime(MID_CLIP.inWholeMicroseconds, MediaMetadataRetriever.OPTION_CLOSEST)) {
        "no frame in $video"
      }
    } finally {
      retriever.release()
    }
  }

  private suspend fun encodesHdr(): Boolean =
    (filmstrip.capabilities() as? CapabilitiesResult.Success)?.capabilities?.supportsHdrEncoding == true

  private fun fixture(clip: String = CLIP): MediaSource? {
    val stream = javaClass.classLoader?.getResourceAsStream(clip) ?: return null
    val file = File(context.cacheDir, clip)
    stream.use { input -> file.outputStream().use(input::copyTo) }
    return MediaSource.of(file.path)
  }

  private infix fun Float.shouldBeNear(expected: Float) {
    assertTrue(abs(this - expected) <= TOLERANCE, "expected $expected but was $this")
  }

  private companion object {
    val TIMEOUT = 5.minutes
    val MID_CLIP = 1_000.milliseconds
    val FRAME = Size(1280, 720)

    const val CLIP = "android_export_hdr.mp4"
    const val HLG_CLIP = "android_export_hdr_hlg.mp4"
    const val HALF = 0.5f
    const val QUARTER = 0.25f
    const val PATCH = 8
    const val TOLERANCE = 1e-4f
    const val GAIN_GAP = 0.05f
    const val BLACK_CEILING = 36

    // The diagonal of media3's column-major 4x4 colour matrix.
    const val RED = 0
    const val GREEN = 5
    const val BLUE = 10
  }
}
