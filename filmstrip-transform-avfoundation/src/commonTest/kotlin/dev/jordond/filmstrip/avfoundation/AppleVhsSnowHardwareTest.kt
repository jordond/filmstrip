package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the snow a [Vhs] tracking band lays over a clip through the compositor, ahead of the encoder, against
 * [VhsSignal.snowAt].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleVhsSnowHardwareTest {
  private val components =
    ComponentRegistry
      .Builder()
      .addEffectResolver(BuiltInEffectResolver())
      .addEffectResolver(FilterEffectResolver())
      .build()

  // Snow is a streak a few pixels to a cell, so the read's mean is held against the signal at every pixel it covers.
  @Test
  fun `snow in the middle of a rolling band lands on the signal through the compositor`() =
    runTest(timeout = TIMEOUT) {
      assertSnowRead(ExportSpec(), "full")
    }

  @Test
  fun `a smaller render draws the same snow at its own rows`() =
    runTest(timeout = TIMEOUT) {
      assertSnowRead(ExportSpec(heightPx = SNOW_SMALL_HEIGHT), "small")
    }

  // Reads a snowy band mid frame on a flat BT.709 clip, rendered ahead of the encoder at [spec]'s size, against the
  // signal at that size, and against the same band with no snow so the read tells the snow apart.
  private suspend fun assertSnowRead(
    spec: ExportSpec,
    name: String,
  ) {
    val source = NSTemporaryDirectory() + "filmstrip-vhs-snow-$name.mp4"
    val primaries = AVVideoColorPrimaries_ITU_R_709_2.orEmpty()
    val matrix = AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty()
    withContext(Dispatchers.Default) { writeTaggedBars(source, SNOW_FRAMES, primaries, matrix, SD_FLAT) }
    val picture = codeFramesOf(source, 1).single().at(CENTRE, CENTRE)
    val edit = Composition(listOf(Track(listOf(Clip(MediaSource.of(source))))), listOf(SNOWY))
    val frame = withContext(Dispatchers.Default) { renderedFrameOf(graphOf(edit, spec), SNOW_READ_FRAME) }
    val tick = VhsSignal.tickAt(SNOW_READ_FRAME / FRAME_RATE)
    val row = VhsSignal.virtualRow(frame.height / 2, frame.height)
    val read = frame.at(CENTRE, CENTRE)
    val wanted = signalOverRead(SNOWY, frame.width, frame.height, picture)
    val clean = signalOverRead(CLEAN_BAND, frame.width, frame.height, picture)

    VhsSignal.snowAt(SNOWY.seed, tick, row, 0.5, VhsParams.of(SNOWY)).weight shouldBeGreaterThan SNOW / 2.0
    withClue("$name snow $wanted has to depart from the band with no snow $clean to tell") {
      maxOf(abs(wanted.r - clean.r), abs(wanted.g - clean.g), abs(wanted.b - clean.b)) * MAX_CODE shouldBeGreaterThan
        TONE_MAP_TOLERANCE_CODES
    }
    withClue("$name read $read against the signal's $wanted at ${frame.width} by ${frame.height}") {
      abs(read.r - wanted.r) * MAX_CODE shouldBeLessThan TONE_MAP_TOLERANCE_CODES
      abs(read.g - wanted.g) * MAX_CODE shouldBeLessThan TONE_MAP_TOLERANCE_CODES
      abs(read.b - wanted.b) * MAX_CODE shouldBeLessThan TONE_MAP_TOLERANCE_CODES
    }
    remove(source)
  }

  // The signal at every pixel of the square a CodeFrame read covers at the centre, on a flat picture.
  private fun signalOverRead(
    vhs: Vhs,
    width: Int,
    height: Int,
    picture: Rgb,
  ): Rgb {
    val tick = VhsSignal.tickAt(SNOW_READ_FRAME / FRAME_RATE)
    val source = { _: Double -> doubleArrayOf(picture.r, picture.g, picture.b) }
    val left = (width * CENTRE).toInt() - READ_HALF
    val top = (height * CENTRE).toInt() - READ_HALF
    val pixels =
      (top until top + 2 * READ_HALF).flatMap { y ->
        (left until left + 2 * READ_HALF).map { x ->
          val row = VhsSignal.virtualRow(y, height)
          VhsSignal.pixelAt((x + 0.5) / width, row, tick, vhs.seed, width, VhsParams.of(vhs), source)
        }
      }
    return Rgb(pixels.map { it[0] }.average(), pixels.map { it[1] }.average(), pixels.map { it[2] }.average())
  }

  private suspend fun graphOf(
    edit: Composition,
    spec: ExportSpec,
  ): AvComposition {
    val resolved = avFoundationExportEngine(components).resolve(edit, spec)
    return withContext(Dispatchers.Default) {
      resolved.shouldBeInstanceOf<ResolveResult.Resolved>().composition.toAvComposition()
    }
  }

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    const val FRAME_RATE = 30.0
    const val CENTRE = 0.5f
    const val MAX_CODE = 255.0

    // Mid-range, with green holding the frame up.
    val SD_FLAT = listOf(Triple(51, 166, 64))

    // A band that rolls through the frame once a second sits mid frame at half a second, frame 15.
    const val SNOW = 0.5f
    val SNOWY = Vhs(tracking = Tracking.Rolling(1.seconds, height = 0.3f, snow = SNOW))
    val CLEAN_BAND = Vhs(tracking = Tracking.Rolling(1.seconds, height = 0.3f, snow = 0f))
    const val SNOW_READ_FRAME = 15
    const val SNOW_FRAMES = 20
    const val SNOW_SMALL_HEIGHT = 180
    const val READ_HALF = 8
  }
}
