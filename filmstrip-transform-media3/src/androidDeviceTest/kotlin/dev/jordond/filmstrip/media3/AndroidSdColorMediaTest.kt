package dev.jordond.filmstrip.media3

import android.media.MediaFormat
import android.os.Build
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.media3.internal.bounds.readClips
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.test.firstEightBitFrame
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.test.Test
import androidx.media3.transformer.Composition as Media3Composition

/**
 * An SD clip through an export, held to the colour the clip displays as.
 *
 * A clip's read goes through `sdSourceDisplayed` off its own tags, and so does the file's, so a file writing true
 * BT.709 and one keeping BT.601 are held to the same answer. An encoder that writes BT.601 for BT.709 gets its file
 * retagged and the export says so. Android names the primaries with the matrix, so that file's primaries are not read.
 * A clip with no tags has no colour of its own to read, so it is held to what Android draws it as. The last bar is why
 * a tagged clip can fail, since the primaries of the mid-range bars ahead of it move by little more than the tolerance.
 */
class AndroidSdColorMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aClipTaggedSmpte170mDisplaysTheSameColourAfterAnExport() =
    runTest(timeout = TIMEOUT) {
      assertTaggedClipDisplays("sd-170m.mp4", MediaFormat.COLOR_STANDARD_BT601_NTSC, SMPTE_C_PRIMARIES)
    }

  @Test
  fun aClipTaggedEbuDisplaysTheSameColourAfterAnExport() =
    runTest(timeout = TIMEOUT) {
      assertTaggedClipDisplays("sd-ebu.mp4", MediaFormat.COLOR_STANDARD_BT601_PAL, EBU_PRIMARIES)
    }

  @Test
  fun anUntaggedClipDisplaysAsAndroidPlaysIt() =
    runTest(timeout = TIMEOUT) {
      val source = clip("sd-untagged.mp4", null)
      val plays = sampledFirstFrame(source)

      assertExportDisplays(source) { fx -> plays.rgbAt(fx, MIDDLE).signal() }
    }

  @Test
  fun aPreviewOfATaggedClipDrawsTheSameColour() =
    runTest(timeout = TIMEOUT) {
      val source = clip("sd-preview.mp4", MediaFormat.COLOR_STANDARD_BT601_NTSC)
      val stored = Written(firstEightBitFrame(source), SMPTE_C_PRIMARIES, fullRange = false)
      val edit = Composition(listOf(Track(listOf(Clip(MediaSource.of(source.path))))))
      val engine =
        media3ExportEngine(ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build())
      val resolved =
        engine
          .resolve(edit, ExportSpec(heightPx = SMALLER_HEIGHT))
          .shouldBeInstanceOf<ResolveResult.Resolved>()
          .composition
      val output = File(probe.context.cacheDir, "sd-preview-out.mp4")
      drawn(resolved.toMedia3Preview(resolved.readClips()).composition, output)
      val (matrix, fullRange) = colorTagOf(output)
      val written = Written(firstEightBitFrame(output), matrix, fullRange)

      BAR_CENTRES.forEach { fx ->
        val expected = sdSourceDisplayed(stored.rgbAt(fx, MIDDLE).signal(), SMPTE_C_PRIMARIES)
        val actual = written.rgbAt(fx, MIDDLE).signal()
        withClue("the preview at bar $fx read ${actual.inCodes()} against ${expected.inCodes()}, matrix $matrix") {
          expected.indices.all { abs(expected[it] - actual[it]) * MAX_CODE <= SD_CODES } shouldBe true
        }
      }
    }

  // The preview's own composition run to a file, which is where its effects can be read back on any device.
  private suspend fun drawn(
    composition: Media3Composition,
    output: File,
  ) = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { waiter ->
      val transformer =
        Transformer
          .Builder(probe.context)
          .addListener(
            object : Transformer.Listener {
              override fun onCompleted(
                composition: Media3Composition,
                exportResult: ExportResult,
              ) = waiter.resume(Unit)

              override fun onError(
                composition: Media3Composition,
                exportResult: ExportResult,
                exportException: ExportException,
              ) = waiter.resumeWithException(exportException)
            },
          ).build()
      waiter.invokeOnCancellation { transformer.cancel() }
      transformer.start(composition, output.path)
    }
  }

  private suspend fun assertTaggedClipDisplays(
    name: String,
    colorStandard: Int,
    codePoint: Int,
  ) {
    val source = clip(name, colorStandard)
    val stored = Written(firstEightBitFrame(source), codePoint, fullRange = false)

    val moved = stored.rgbAt(BAR_CENTRES.last(), MIDDLE).signal()
    val shown = sdSourceDisplayed(moved, codePoint)
    withClue("the last bar's primaries move from ${moved.inCodes()} to ${shown.inCodes()}") {
      moved.indices.maxOf { abs(moved[it] - shown[it]) } * MAX_CODE shouldBeGreaterThan SD_CODES
    }
    assertExportDisplays(source) { fx -> sdSourceDisplayed(stored.rgbAt(fx, MIDDLE).signal(), codePoint) }
  }

  private suspend fun assertExportDisplays(
    source: File,
    shown: (Float) -> DoubleArray,
  ) {
    val composition = Composition(listOf(Track(listOf(Clip(MediaSource.of(source.path))))))
    val finished = probe.finish(probe.plan(composition, ExportSpec(heightPx = SMALLER_HEIGHT)))
    val written = probe.writtenBy(finished)
    val retagged = finished.adjustments.any { it.kind == AdjustmentKind.ColorSpaceChanged }
    val primaries = written.primariesFrom(finished)

    BAR_CENTRES.forEachIndexed { bar, fx ->
      val expected = shown(fx)
      val actual = sdSourceDisplayed(written.rgbAt(fx, MIDDLE).signal(), primaries)
      withClue("${source.name} at bar $fx shows ${expected.inCodes()} where ${BARS[bar].toList()} was drawn") {
        expected.indices.all { abs(expected[it] - BARS[bar][it]) * MAX_CODE <= DRAWN_CODES } shouldBe true
      }
      withClue(
        "API ${Build.VERSION.SDK_INT} ${source.name} at bar $fx read ${actual.inCodes()} against " +
          "${expected.inCodes()}, matrix ${written.matrix} retagged $retagged",
      ) {
        expected.indices.all { abs(expected[it] - actual[it]) * MAX_CODE <= SD_CODES } shouldBe true
      }
    }
  }

  private suspend fun clip(
    name: String,
    colorStandard: Int?,
  ): File {
    val file = File(probe.context.cacheDir, name)
    probe.writeBarsClip(file, CLIP_WIDTH, CLIP_HEIGHT, BARS, colorStandard)
    return file
  }

  private fun Triple<Int, Int, Int>.signal(): DoubleArray =
    doubleArrayOf(first / MAX_CODE, second / MAX_CODE, third / MAX_CODE)

  private fun IntArray.signal(): DoubleArray = DoubleArray(size) { this[it] / MAX_CODE }

  private fun DoubleArray.inCodes(): List<Double> = map { it * MAX_CODE }

  private companion object {
    const val CLIP_WIDTH = 640
    const val CLIP_HEIGHT = 360
    const val SMALLER_HEIGHT = 240
    const val MIDDLE = 0.5f
    const val MAX_CODE = 255.0

    // Two H.264 encodes of one frame move a colour by a few codes on their own.
    const val SD_CODES = 4.0

    // How far a clip's read can sit from what was drawn, across the matrix its encoder picked. A read further off is a
    // garbled clip, which would otherwise pass against itself.
    const val DRAWN_CODES = 30.0

    val BARS =
      listOf(
        floatArrayOf(0.78f, 0.55f, 0.43f),
        floatArrayOf(0.42f, 0.72f, 0.46f),
        floatArrayOf(0.33f, 0.5f, 0.78f),
        floatArrayOf(0.74f, 0.66f, 0.36f),
        floatArrayOf(0.2f, 0.8f, 0.5f),
      )
    val BAR_CENTRES = BARS.indices.map { (it + 0.5f) / BARS.size }
  }
}
