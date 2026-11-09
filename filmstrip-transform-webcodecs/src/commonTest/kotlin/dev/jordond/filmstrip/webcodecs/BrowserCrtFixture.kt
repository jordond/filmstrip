package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtParams
import dev.jordond.filmstrip.effects.filters.crt.CrtSignal
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration

/**
 * What a read of a [Crt] through the real browser compositor is made of.
 *
 * Reads come off a preview, which is the export's own lowering and pass with no encoder in the way. The picture a read
 * is held against is the same clip drawn with no [Crt], so what the fixture's encoder and the decoder did to it is in
 * the expected value as well.
 */
abstract class BrowserCrtFixture {
  internal fun litAt(
    column: Int,
    row: Int,
    k: Double,
    width: Int = WIDTH,
    height: Int = HEIGHT,
  ): Boolean = CrtSignal.coverageAt((column + 0.5) / width, (row + 0.5) / height, width, height, k) > 0.0

  internal suspend fun drawn(
    composition: Composition,
    spec: ExportSpec = SPEC,
  ): PreviewFrame {
    val preview = previewOf(composition, spec)
    try {
      return preview.frameAt(READ_TIME).shouldNotBeNull()
    } finally {
      preview.release()
    }
  }

  internal suspend fun previewOf(
    composition: Composition,
    spec: ExportSpec,
  ): BrowserPreview =
    webCodecsExportEngine(
      components =
        ComponentRegistry
          .Builder()
          .addEffectResolver(BuiltInEffectResolver())
          .addEffectResolver(FilterEffectResolver())
          .build(),
      prober = BrowserProber(),
    ).resolve(composition, spec)
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition
      .toBrowserPreview(composition)

  internal fun edit(
    clip: Clip,
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Solid(BLACK_ARGB),
  ) = Composition(
    tracks = listOf(Track(listOf(clip))),
    effects = effects,
    fill = fill,
    audio = AudioSpec.Remove,
  )

  internal suspend fun gradientClip(
    width: Int,
    height: Int,
  ): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(width, height, FRAMES, FRAME_RATE) { _, x, y ->
        Rgb(60 + 120 * x / width, 170 - 80 * y / height, 90 + 50 * x / width)
      },
    )

  internal suspend fun flatClip(
    width: Int,
    height: Int,
    colour: Rgb,
  ): MediaSource.Bytes = MediaSource.Bytes(makeClip(width, height, FRAMES, FRAME_RATE) { _, _ -> colour })

  internal fun swatch(argb: Int = SWATCH_ARGB): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), argb)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  // The largest gap between the frame and the signal over the middle of the picture and the curve's edges in both
  // directions, in eight bit codes, with the source read off the picture with no Crt on it.
  internal fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    crt: Crt,
    rows: List<Int>? = null,
    columns: List<Int>? = null,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    val params = CrtParams.of(crt)
    val readRows = rows ?: readsAcross(height) { row -> litAt(width / 2, row, params.barrelK, width, height) }
    val readColumns =
      columns ?: readsAcross(width) { column -> litAt(column, height / 2, params.barrelK, width, height) }
    var worst = 0.0
    readRows.forEach { y ->
      readColumns.forEach { x ->
        val expected =
          CrtSignal.pixelAt((x + 0.5) / width, (y + 0.5) / height, width, height, params) { u, v ->
            picture.bilinear(u, v)
          }
        val read = frame.pixelAt(x, y).channels()
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - expected[channel]) * FULL)
      }
    }
    return worst
  }

  private fun readsAcross(
    size: Int,
    inside: (Int) -> Boolean,
  ): List<Int> {
    val edge = (0 until size).first(inside)
    val spread = (0 until SPREAD_READS).map { it * (size - 1) / (SPREAD_READS - 1) }
    val atEdge = listOf(edge - 1, edge, edge + 1, size - edge - 2, size - edge - 1, size - edge)
    val middle = (-MIDDLE_READS..MIDDLE_READS).map { size / 2 + it }
    return (spread + atEdge + middle).filter { it in 0 until size }.distinct()
  }

  // The frame read the way a texture filters, between pixel centres and clamped at the edge.
  private fun PreviewFrame.bilinear(
    u: Double,
    v: Double,
  ): DoubleArray {
    val x = u * size.width - 0.5
    val y = v * size.height - 0.5
    val left = floor(x).toInt()
    val top = floor(y).toInt()
    val across = x - left
    val down = y - top
    val texel = { column: Int, row: Int ->
      pixelAt(column.coerceIn(0, size.width - 1), row.coerceIn(0, size.height - 1)).channels()
    }
    val upperLeft = texel(left, top)
    val upperRight = texel(left + 1, top)
    val lowerLeft = texel(left, top + 1)
    val lowerRight = texel(left + 1, top + 1)
    return DoubleArray(RGB) {
      val upper = upperLeft[it] + (upperRight[it] - upperLeft[it]) * across
      val lower = lowerLeft[it] + (lowerRight[it] - lowerLeft[it]) * across
      upper + (lower - upper) * down
    }
  }

  internal fun PreviewFrame.pixelAt(
    x: Int,
    y: Int,
  ): Rgb = at((x + 0.5) / size.width, (y + 0.5) / size.height)

  internal fun Rgb.channels(): DoubleArray = doubleArrayOf(red / FULL, green / FULL, blue / FULL)
}

private val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
private const val FULL = 255.0
private const val RGB = 3
private const val WIDTH = 1280
private const val HEIGHT = 720
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val READ_SLOT = 6L
private val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
private const val SPREAD_READS = 9
private const val MIDDLE_READS = 6
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val SWATCH_ARGB = 0xFF202020.toInt()
private const val SWATCH_PX = 64
private const val SWATCH_SCALE = 0.3f
