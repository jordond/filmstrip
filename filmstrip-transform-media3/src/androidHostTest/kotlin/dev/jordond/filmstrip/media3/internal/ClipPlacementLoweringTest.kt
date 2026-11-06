package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.Effect
import androidx.media3.effect.AlphaScale
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.fill.FillCoverBlur
import dev.jordond.filmstrip.media3.internal.fill.FillFlatten
import dev.jordond.filmstrip.transform.internal.geometry.ClipPlacement
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.drawnRect
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a clip's chain ends on the pass that places it in the output frame. Nothing here touches GL.
 */
class ClipPlacementLoweringTest {
  @Test
  fun `places every clip in the output frame at its drawn rect`() {
    val clip = barred

    clip.clipVideoEffects(Fill.Black, OUTPUT).last() shouldBe
      PlacedFrame(clip.drawnRect, OUTPUT)
    clip.clipVideoEffects(Fill.Blurred(), OUTPUT).last() shouldBe FillCoverBlur(Fill.Blurred(), OUTPUT, clip.drawnRect)
  }

  // The crop lowers to nothing that removes pixels, so the canvas is built right behind it and the pan after it reads
  // the canvas, bars included. The bars stay clear for the flatten pass, and under a blurred fill they turn black once
  // the clip's effects have run, the way every backend builds them.
  @Test
  fun `builds a clip's canvas right after the crop that makes it`() {
    val crop = AlphaScale(1f)
    val pan = AlphaScale(0.5f)
    val placement = placementOf(Size(640, 360), listOf(listOf(Crop(AspectRatio(4, 5), Fit.Contain), PAN)), OUTPUT)
    val clip =
      clip(
        effects = listOf(resolvedEffect(crop, EffectStage.Geometry), resolvedEffect(pan, EffectStage.Geometry)),
        placement = placement,
        canvasEffectIndices = listOf(0),
      )
    val built = placement.canvases.single()
    val canvas = PlacedFrame(built.contentInCanvas, built.size)

    clip.clipVideoEffects(Fill.Black, OUTPUT) shouldBe
      listOf(crop, canvas, pan, PlacedFrame(placement.drawnRect, OUTPUT))
    clip.clipVideoEffects(Fill.Blurred(), OUTPUT).take(BLURRED_CHAIN) shouldBe
      listOf(crop, canvas, pan, FillFlatten(BLACK))
  }

  // The composition's turn reads the clip's square canvas and runs on that canvas. Its crop then makes a portrait
  // canvas nothing reads, which the clip's drawn rect folds in, so the turned content keeps the source's aspect.
  @Test
  fun `builds the canvas a composition turn reads and folds the one after it into the drawn rect`() {
    val square = AlphaScale(1f)
    val turn = AlphaScale(0.75f)
    val tall = AlphaScale(0.5f)
    val stages =
      listOf(
        listOf(Crop(AspectRatio.Square, Fit.Contain)),
        listOf(Rotate(QUARTER_TURN), Crop(AspectRatio(9, 16), Fit.Contain)),
      )
    val portrait = Size(OUTPUT.height * 9 / 16, OUTPUT.height)
    val placement = placementOf(LANDSCAPE, stages, portrait)
    val clip =
      clip(
        effects = listOf(square, turn, tall).map { resolvedEffect(it, EffectStage.Geometry) },
        placement = placement,
        canvasEffectIndices = listOf(0),
      )
    val canvas = placement.canvases.single()
    val drawn = placement.drawnRect
    val aspect =
      canvas.contentInCanvas.height * drawn.width * portrait.width /
        (canvas.contentInCanvas.width * drawn.height * portrait.height)

    aspect shouldBe (LANDSCAPE.height.toFloat() / LANDSCAPE.width plusOrMinus ASPECT_SLACK)
    clip.clipVideoEffects(Fill.Black, portrait) shouldBe
      listOf(
        square,
        PlacedFrame.canvasOf(canvas),
        turn,
        tall,
        PlacedFrame(drawn, portrait),
      )
  }

  @Test
  fun `a placement that draws the frame onto itself is a no-op`() {
    PlacedFrame(NormalizedRect.Full, OUTPUT)
      .isNoOp(OUTPUT.width, OUTPUT.height) shouldBe true
    PlacedFrame(NormalizedRect.Full, OUTPUT)
      .isNoOp(OUTPUT.width, OUTPUT.height + 1) shouldBe false
    barred
      .drawnRect
      .let { PlacedFrame(it, OUTPUT) }
      .isNoOp(OUTPUT.width, OUTPUT.height) shouldBe false
  }

  // A portrait clip in the landscape output, pillarboxed under the default fit.
  private val barred: ResolvedClip =
    clip(size = PORTRAIT, placement = placementOf(PORTRAIT, listOf(emptyList()), OUTPUT))

  private fun composition(): ResolvedComposition =
    ResolvedComposition(
      tracks = emptyList(),
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = OUTPUT,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      layoutSize = OUTPUT,
      fill = Fill.Black,
      duration = 1.seconds,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  private fun clip(
    size: Size = Size(640, 360),
    effects: List<ResolvedEffect> = emptyList(),
    placement: ClipPlacement = ClipPlacement.Filling,
    canvasEffectIndices: List<Int> = emptyList(),
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of("clip.mp4"),
      info =
        MediaInfo(
          duration = 1.seconds,
          video =
            VideoTrackInfo(
              codedSize = size,
              displaySize = size,
              rotationDegrees = 0,
              pixelAspectRatio = 1f,
              frameRate = 30f,
              codec = trackCodecOf("avc1"),
              bitDepth = 8,
              colorSpace = ColorSpace.Bt709,
              hdrTransfer = null,
              bitrate = null,
            ),
          audio = null,
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = 1.seconds,
      effects = effects,
      gain = ResolvedGain.constant(1f, Duration.ZERO, 1.seconds),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, 1.seconds),
      sourceIndex = 0,
      placement = placement,
      canvasEffectIndices = canvasEffectIndices,
    )

  private fun resolvedEffect(
    effect: Effect,
    stage: EffectStage,
  ): ResolvedEffect = ResolvedEffect("test", PlatformEffect(effect), stage)

  private companion object {
    val OUTPUT = Size(320, 180)
    val PORTRAIT = Size(360, 640)
    val PAN = KenBurns(NormalizedRect(0f, 0f, 0.5f, 0.5f), NormalizedRect.Full)
    val LANDSCAPE = Size(640, 360)
    const val QUARTER_TURN = 90
    const val BLACK = 0xFF000000.toInt()
    const val BLURRED_CHAIN = 4

    // A placement read off a whole-pixel frame lands within this of the aspect it was asked for.
    const val ASPECT_SLACK = 0.02f
  }
}
