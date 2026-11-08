package dev.jordond.filmstrip.media3

import android.graphics.Color
import androidx.media3.effect.RgbAdjustment
import dev.jordond.filmstrip.color.brightnessDisplayGain
import dev.jordond.filmstrip.color.brightnessSceneGain
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.Media3Handle
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HLG_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.assumeEncodesHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Brightness on an export that keeps its grade, on real media3.
 *
 * The gain assertions are the contract: media3 holds PQ as display light and HLG as scene light, so
 * the same factor reaches the color matrix as two different numbers, both taken from the shared
 * functions. They run on a device rather than on the host because building an `RgbAdjustment` calls
 * into `android.opengl.Matrix`.
 *
 * The pixel arm is directional only. `MediaMetadataRetriever` tone-maps an HDR frame on the way out
 * and never says how, so what a code value there means is not something to assert an exact figure
 * against.
 */
class AndroidBrightnessHdrMediaTest {
  private val probe = Media3ExportProbe()
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

    withClue("one gain served both transfer functions, so one of them is wrong") {
      (abs(pq - hlg) > GAIN_GAP) shouldBe true
    }
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
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val half = luminanceOf(export(source, HALF))
      val quarter = luminanceOf(export(source, QUARTER))

      withClue("the reference frame reads as black, nothing to compare against") {
        (half > BLACK_CEILING) shouldBe true
      }
      withClue("a quarter brightness read $quarter against $half at half") { (quarter < half) shouldBe true }
    }

  @Test
  fun aKeptGradeSurvivesABrightnessEffect() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val written = probe.infoOf(MediaSource.of(export(source, HALF).path))
      val video = withClue("the written file has no video track") { written.video.shouldNotBeNull() }

      withClue("a brightness on a kept grade wrote an SDR file") { (video.hdrTransfer != null) shouldBe true }
    }

  // HLG is the arm where the backends disagree about what linear means, so it gets an export of its
  // own rather than resting on the PQ one and the gain assertions above.
  @Test
  fun dimmingFurtherDarkensAKeptHlgGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HLG_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val half = luminanceOf(export(source, HALF))
      val quarter = luminanceOf(export(source, QUARTER))

      withClue("the reference frame reads as black, nothing to compare against") {
        (half > BLACK_CEILING) shouldBe true
      }
      withClue("a quarter brightness read $quarter against $half at half") { (quarter < half) shouldBe true }
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
    val handle = resolution.shouldBeInstanceOf<EffectResolution.Resolved>().effect.handle

    return handle.shouldBeInstanceOf<Media3Handle.Video>().effect.shouldBeInstanceOf<RgbAdjustment>().getMatrix(
      0L,
      transfer != null,
    )
  }

  private suspend fun export(
    source: MediaSource,
    factor: Float,
  ): File {
    // The effect is carried at every factor, one at 1f included, so the reference frame came out of
    // the same encoder rather than off the copy path.
    val composition = EditComposition(listOf(Track(listOf(Clip(source)))), listOf(Brightness(factor)))
    return probe.export(composition, ExportSpec(hdr = HdrMode.KeepHdr))
  }

  private fun luminanceOf(video: File): Int {
    val bitmap = probe.frame(video)
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

  private infix fun Float.shouldBeNear(expected: Float) {
    withClue("expected $expected but was $this") { (abs(this - expected) <= TOLERANCE) shouldBe true }
  }

  private companion object {
    val FRAME = Size(1280, 720)

    const val HALF = 0.5f
    const val QUARTER = 0.25f
    const val PATCH = 8
    const val TOLERANCE = 1e-4f
    const val GAIN_GAP = 0.05f
    const val BLACK_CEILING = 36

    // The diagonal of media3's column-major 4x4 color matrix.
    const val RED = 0
    const val GREEN = 5
    const val BLUE = 10
  }
}
