@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.color.ycbcrToRgb
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.interop.asString
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration

/**
 * An SD clip through a browser export and a preview, held to the colour `sdSourceDisplayed` says the clip displays as.
 *
 * A browser draws such a clip with its real matrix and without moving its primaries, which the compositor's own pass
 * does. A clip with no tags has none to move, so it reads what this browser reads it as.
 *
 * Every read comes off the decoder's own YCbCr codes through the matrix the file says it holds, since a read through
 * RGBA goes through whatever conversion this browser picked. The clips are real files out of ffmpeg, see [SdClips].
 */
class BrowserSdColorMediaTest {
  @Test
  fun aClipTaggedSmpte170mDisplaysTheSameColourAfterAnExport() =
    runTest { assertExportsDisplayed(SdClips.smpte170m, "smpte170m", SMPTE_C_PRIMARIES) }

  @Test
  fun aClipTaggedEbuDisplaysTheSameColourAfterAnExport() =
    runTest { assertExportsDisplayed(SdClips.ebu, "bt470bg", EBU_PRIMARIES) }

  @Test
  fun aClipTaggedSmpte170mDisplaysTheSameColourInAPreview() =
    runTest { assertPreviewDisplayed(SdClips.smpte170m, "smpte170m", SMPTE_C_PRIMARIES) }

  @Test
  fun aClipTaggedEbuDisplaysTheSameColourInAPreview() =
    runTest { assertPreviewDisplayed(SdClips.ebu, "bt470bg", EBU_PRIMARIES) }

  @Test
  fun aVhsOnATaggedClipReadsTheSignalOverTheClipsCorrectedColourAtBothScopes() =
    runTest {
      val source = MediaSource.Bytes(SdClips.smpte170m)
      val frame = firstCodes(source)
      val shownAt = { x: Double -> frame.displayedAt(x.coerceAtMost(LAST_COLUMN), SMPTE_C_PRIMARIES).toDoubleArray() }
      val moved =
        BAR_CENTRES.maxOf { x ->
          val raw = frame.rgbAt(x, MIDDLE, SMPTE_C_PRIMARIES).toList()
          val shown = shownAt(x)
          raw.indices.maxOf { abs(raw[it] - shown[it]) }
        }
      withClue("the correction has to be wider than the tolerance, $moved") {
        (moved * MAX_CODE > VHS_CODES).shouldBeTrue()
      }
      val vhs = Vhs(intensity = 0.5f)
      mapOf(
        "clip" to Composition(listOf(Track(listOf(Clip(source, effects = listOf(vhs)))))),
        "composition" to Composition(listOf(Track(listOf(Clip(source)))), listOf(vhs)),
      ).forEach { (scope, edit) ->
        val drawn = previewFrameOf(source, edit)
        val tick = VhsSignal.tickAt(0.0)
        VHS_ROWS.forEach { y ->
          val row = VhsSignal.virtualRow((y * drawn.size.height).toInt(), drawn.size.height)
          BAR_CENTRES.forEach { x ->
            val column = ((x * drawn.size.width).toInt() + MIDDLE) / drawn.size.width
            val wanted =
              VhsSignal
                .pixelAt(column, row, tick, vhs.seed, drawn.size.width, VhsParams.of(vhs)) {
                  shownAt((it + 0.5) / drawn.size.width)
                }.toList()
            withClue("$scope scope at row $y") { assertCodes(drawn.rgbAt(x, y), wanted, x, VHS_CODES) }
          }
        }
      }
    }

  @Test
  fun anUntaggedClipReadsTheSameAfterAnExportAsTheBrowserReadsItBefore() =
    runTest {
      val source = MediaSource.Bytes(SdClips.untagged)
      val before = decodeFrames(source).first()
      val after = decodeFrames(outputOf(exportOf(single(source), MediaSink.Uri(""), SPEC))).first()
      BAR_CENTRES.forEach { x ->
        val read = after.at(x, MIDDLE)
        val expected = before.at(x, MIDDLE)
        withClue("the bar at $x read $read against $expected") {
          read.isNear(expected, WRITTEN_CODES.toInt()).shouldBeTrue()
        }
      }
    }

  @Test
  fun anUntaggedClipReadsTheSameInAPreviewAsTheBrowserReadsItBefore() =
    runTest {
      val source = MediaSource.Bytes(SdClips.untagged)
      val before = decodeFrames(source).first()
      val frame = previewFrameOf(source)
      BAR_CENTRES.forEach { x ->
        val read = frame.rgbAt(x, MIDDLE).map { it * MAX_CODE }
        val expected = before.at(x, MIDDLE)
        withClue("the bar at $x read $read against $expected") {
          abs(read[0] - expected.red) <= PREVIEW_CODES &&
            abs(read[1] - expected.green) <= PREVIEW_CODES &&
            abs(read[2] - expected.blue) <= PREVIEW_CODES
        }.shouldBeTrue()
      }
    }

  private suspend fun assertExportsDisplayed(
    clip: ByteArray,
    named: String,
    primaries: Int,
  ) {
    val source = MediaSource.Bytes(clip)
    val frame = firstCodes(source)
    frame.primaries shouldBe named
    val written = firstCodes(outputOf(exportOf(single(source), MediaSink.Uri(""), SPEC)))
    BAR_CENTRES.forEach { x ->
      val shown = frame.displayedAt(x, primaries)
      val read = written.rgbAt(x, MIDDLE, BT709).toList()
      assertCodes(read, shown, x, WRITTEN_CODES)
    }
  }

  private suspend fun assertPreviewDisplayed(
    clip: ByteArray,
    named: String,
    primaries: Int,
  ) {
    val source = MediaSource.Bytes(clip)
    val frame = firstCodes(source)
    frame.primaries shouldBe named
    val drawn = previewFrameOf(source)
    BAR_CENTRES.forEach { x ->
      assertCodes(drawn.rgbAt(x, MIDDLE), frame.displayedAt(x, primaries), x, PREVIEW_CODES)
    }
  }

  private fun FrameCodes.displayedAt(
    x: Double,
    primaries: Int,
  ): List<Double> {
    // H.273 numbers each of these clips' matrix the same as its primaries.
    val matrix = primaries
    return sdSourceDisplayed(rgbAt(x, MIDDLE, matrix), primaries).toList()
  }

  private fun assertCodes(
    read: List<Double>,
    shown: List<Double>,
    x: Double,
    tolerance: Double,
  ) {
    withClue("the bar at $x read ${read.inCodes()} against ${shown.inCodes()}") {
      read.indices.all { abs(read[it] - shown[it]) * MAX_CODE <= tolerance }.shouldBeTrue()
    }
  }

  private suspend fun previewFrameOf(
    source: MediaSource,
    edit: Composition = single(source),
  ): PreviewFrame {
    val preview =
      webCodecsExportEngine(
        components =
          ComponentRegistry
            .Builder()
            .addEffectResolver(BuiltInEffectResolver())
            .addEffectResolver(FilterEffectResolver())
            .build(),
        prober = BrowserProber(),
      ).resolve(edit, ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None))
        .shouldBeInstanceOf<ResolveResult.Resolved>()
        .composition
        .toBrowserPreview(edit)
    try {
      return preview.frameAt(Duration.ZERO).shouldNotBeNull()
    } finally {
      preview.release()
    }
  }

  private fun PreviewFrame.rgbAt(
    xFraction: Double,
    yFraction: Double,
  ): List<Double> {
    val offset = ((yFraction * size.height).toInt() * size.width + (xFraction * size.width).toInt()) * RGBA_CHANNELS
    return (0 until RGB_CHANNELS).map { (pixels[offset + it].toInt() and BYTE_MASK) / MAX_CODE }
  }

  private fun List<Double>.inCodes(): List<Double> = map { it * MAX_CODE }

  private fun single(source: MediaSource): Composition = Composition(listOf(Track(listOf(Clip(source)))))

  /**
   * The first frame of [source] as the YCbCr it stores, a limited range 8-bit plane each.
   */
  private suspend fun firstCodes(source: MediaSource): FrameCodes {
    val reader = SourceReader.of(source) ?: error("the decoder could not open $source")
    try {
      val stream = reader.frames(0.0, Double.POSITIVE_INFINITY) ?: error("no video track in $source")
      try {
        val sample = stream.next() ?: error("no frame in $source")
        try {
          val options = JsOptions().build()
          val planes = Uint8Array(sample.allocationSize(options))
          val frame = sample.toVideoFrame()
          val primaries = frame.colorSpace.primaries?.asString()
          val fullRange = frame.colorSpace.fullRange?.toString() == "true"
          frame.close()
          sample.copyTo(planes, options).await()
          return FrameCodes(
            sample.format.toString(),
            sample.codedWidth,
            sample.codedHeight,
            primaries,
            fullRange,
            planes,
          )
        } finally {
          sample.close()
        }
      } finally {
        stream.close()
      }
    } finally {
      reader.close()
    }
  }

  private class FrameCodes(
    private val format: String,
    private val width: Int,
    private val height: Int,
    val primaries: String?,
    private val fullRange: Boolean,
    private val planes: Uint8Array,
  ) {
    /**
     * The RGB signal this frame's codes read as through matrix coefficients code point [matrix].
     */
    fun rgbAt(
      xFraction: Double,
      yFraction: Double,
      matrix: Int,
    ): DoubleArray {
      val x = (xFraction * width).toInt()
      val y = (yFraction * height).toInt()
      val chroma = (y / 2) * (width / 2) + x / 2
      val lumaSize = width * height
      val (cb, cr) =
        if (format.startsWith("NV12")) {
          val at = lumaSize + (y / 2) * width + (x / 2) * 2
          planes.at(at) to planes.at(at + 1)
        } else {
          planes.at(lumaSize + chroma) to planes.at(lumaSize + lumaSize / 4 + chroma)
        }
      val reading = ycbcrReading(matrix, BT709, BT709)
      return ycbcrToRgb(reading, planes.at(y * width + x), cb, cr, BIT_DEPTH, fullRange)
    }
  }

  private companion object {
    const val BT709 = 1
    const val BIT_DEPTH = 8
    const val RGB_CHANNELS = 3
    const val RGBA_CHANNELS = 4
    const val CLIP_HEIGHT = 72
    const val BARS = 4
    const val BYTE_MASK = 0xFF
    const val MIDDLE = 0.5
    const val MAX_CODE = 255.0

    // Two H.264 encodes of one frame move a colour by a few codes on their own.
    const val WRITTEN_CODES = 4.0

    // The eight-bit texture the pass draws into and the rounding of its read back.
    const val PREVIEW_CODES = 2.0

    // What the pass and its read back leave of a tape drawn over a frame read through its own 4:2:0 planes.
    const val VHS_CODES = 3.0
    const val LAST_COLUMN = 0.999
    val VHS_ROWS = listOf(0.25, 0.5, 0.75)

    val BAR_CENTRES = (0 until BARS).map { (it + MIDDLE) / BARS }
    val SPEC =
      ExportSpec(
        videoCodec = VideoCodec.H264,
        audioCodec = AudioCodec.None,
        heightPx = CLIP_HEIGHT / 2,
        bitrate = MEASURING_BITRATE,
      )
  }
}
