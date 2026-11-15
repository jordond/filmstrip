package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.tape.TapeLook
import dev.jordond.filmstrip.effects.filters.tape.TapePictureParams
import dev.jordond.filmstrip.effects.filters.tape.TapePictureSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Draws a tape look in a browser preview and reads the frame against [TapePictureSignal], for the tests of each tape
 * format.
 */
abstract class BrowserTapePictureFrames {
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
  ): BrowserPreview = resolvedOf(composition, spec).toBrowserPreview(composition)

  internal suspend fun resolvedOf(
    composition: Composition,
    spec: ExportSpec,
  ) = webCodecsExportEngine(
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

  internal suspend fun rampClip(): MediaSource.Bytes =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, x, _ ->
        val step = x * RAMP_CODES / WIDTH
        Rgb(RAMP_FLOOR + step, RAMP_MIDDLE, RAMP_FLOOR + RAMP_CODES - step)
      },
    )

  // The largest gap between the frame and the signal across rows and columns from edge to edge of the picture and one
  // row inside the head band, in eight bit codes, with the picture bilinear over its columns the way the sampler reads
  // it.
  internal fun worstCodes(
    frame: PreviewFrame,
    picture: PreviewFrame,
    look: TapeLook,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    var worst = 0.0
    listOf(height / 4, height / 2, height * 5 / 8, height - 1).forEach { y ->
      listOf(0, width / 4, width / 2, width * 3 / 4, width - 1).forEach { column ->
        worst = max(worst, codesAt(frame, picture, look, y, column))
      }
    }
    return worst
  }

  // The gap between the frame and the signal at one pixel, in eight bit codes, the widest of its channels.
  internal fun codesAt(
    frame: PreviewFrame,
    picture: PreviewFrame,
    look: TapeLook,
    y: Int,
    column: Int,
  ): Double {
    val width = frame.size.width
    val height = frame.size.height
    val row = TapePictureSignal.virtualRow(y, height)
    val texel = { at: Int -> picture.at((at + 0.5) / width, (y + 0.5) / height).channels() }
    val x = (column + 0.5) / width
    val expected = TapePictureSignal.pixelAt(x, row, TICK, look.seed, width, TapePictureParams.of(look), texel)
    val read = frame.at(x, (y + 0.5) / height).channels()
    return (0 until RGB).maxOf { channel -> abs(read[channel] - expected[channel]) * FULL }
  }

  internal fun Rgb.channels(): DoubleArray = doubleArrayOf(red / FULL, green / FULL, blue / FULL)

  companion object {
    val SPEC = ExportSpec(videoCodec = VideoCodec.H264)
    const val FULL = 255.0
    const val RGB = 3
    const val WIDTH = 320
    const val HEIGHT = 240
    const val SMALL_HEIGHT = 120
    const val FRAMES = 12
    const val FRAME_RATE = 30
    const val READ_SLOT = 6L
    val READ_TIME: Duration = outputSlotTime(READ_SLOT, FRAME_RATE)
    val TICK: Int = TapePictureSignal.tickAt(READ_TIME.toDouble(DurationUnit.SECONDS))
    const val RAMP_FLOOR = 64
    const val RAMP_CODES = 128
    const val RAMP_MIDDLE = 128
    const val BLACK_ARGB = 0xFF000000.toInt()

    // A read and the picture it is held against each round to one eight bit code. Measured at 0.49.
    const val SIGNAL_CODES = 1.0
  }
}
