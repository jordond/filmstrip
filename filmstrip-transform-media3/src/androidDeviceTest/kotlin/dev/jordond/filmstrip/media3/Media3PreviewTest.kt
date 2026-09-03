package dev.jordond.filmstrip.media3

import androidx.media3.common.Effect
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbMatrix
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.toMedia3Preview
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.ResolvedGain
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The two things a preview needs from the lowering that an export does not: where each clip sits on
 * the composition's clock, and which positions in the chain a parameter change can still reach once
 * the graph is standing.
 *
 * Nothing here builds a shader program, so nothing here touches GL. What is checked is which effect
 * object the chain holds and what it answers when asked. It runs on a device rather than on the
 * host because the lowering reads a Uri and builds media3's matrices, both of which are real
 * Android and neither of which the unit-test stubs answer.
 *
 * Camel case test names, because dex below version 040, which needs API 30, rejects a space in a
 * method name.
 */
class Media3PreviewTest {
  @Test
  fun spansTileTheTimelineBehindTheGapTheTrackStartsWith() {
    val preview =
      composition(
        trackStart = 500.milliseconds,
        clips = listOf(clip(source = "a.mp4", length = 1.seconds), clip(source = "b.mp4", length = 2.seconds)),
      ).toMedia3Preview()

    preview.spans.map { it.start } shouldBe listOf(500.milliseconds, 1_500.milliseconds)
    preview.spans.map { it.end } shouldBe listOf(1_500.milliseconds, 3_500.milliseconds)
  }

  // The middle of each clip, not its edges: an offset that adds where it should subtract agrees
  // with a boundary and disagrees everywhere else.
  @Test
  fun aCompositionTimeMapsToAPositionInsideTheClipCoveringIt() {
    val preview =
      composition(
        trackStart = 500.milliseconds,
        clips = listOf(clip(source = "a.mp4", length = 1.seconds), clip(source = "b.mp4", length = 2.seconds)),
      ).toMedia3Preview()

    val first = checkNotNull(preview.spanAt(900.milliseconds))
    first.positionIn(900.milliseconds) shouldBe 400.milliseconds

    val second = checkNotNull(preview.spanAt(2_300.milliseconds))
    second.positionIn(2_300.milliseconds) shouldBe 800.milliseconds
  }

  @Test
  fun theGapTheTrackStartsWithCoversNoClipAtAll() {
    val preview =
      composition(trackStart = 500.milliseconds, clips = listOf(clip(source = "a.mp4", length = 1.seconds)))
        .toMedia3Preview()

    preview.spanAt(200.milliseconds) shouldBe null
  }

  @Test
  fun aColourParameterReachesTheStandingChainWithoutItBeingRebuilt() {
    val preview = composition(compositionEffects = listOf(brightness(DIM))).toMedia3Preview()
    val live = preview.compositionEffects.filterIsInstance<RgbMatrix>().single()
    live.gain() shouldBe (DIM plusOrMinus TOLERANCE)

    preview.updateParameters(composition(compositionEffects = listOf(brightness(BRIGHT)))) shouldBe true

    // The same object, still in the chain the player is drawing with, now answering differently.
    live.gain() shouldBe (BRIGHT plusOrMinus TOLERANCE)
    preview.compositionEffects.filterIsInstance<RgbMatrix>().single() shouldBe live
  }

  @Test
  fun aMatrixThatWouldStillBeANoOpKeepsItsPositionInTheChain() {
    val preview = composition(compositionEffects = listOf(brightness(1f))).toMedia3Preview()
    val live = preview.compositionEffects.filterIsInstance<RgbMatrix>().single()

    // media3 drops a no-op while it builds the chain, and a dropped position cannot take a later
    // parameter, so this one never admits to being one.
    live.isNoOp(FRAME.width, FRAME.height) shouldBe false
  }

  @Test
  fun anEffectAddedToTheChainIsRefusedSinceTheShapeMoved() {
    val preview = composition(compositionEffects = listOf(brightness(DIM))).toMedia3Preview()

    preview.updateParameters(composition(compositionEffects = listOf(brightness(DIM), brightness(BRIGHT)))) shouldBe
      false
  }

  @Test
  fun aFillColourIsRefusedSinceThePassThatPaintsItIsBuiltOnce() {
    val preview = composition(fill = Fill.Solid(RED)).toMedia3Preview()

    preview.updateParameters(composition(fill = Fill.Solid(RED))) shouldBe true
    preview.updateParameters(composition(fill = Fill.Solid(BLUE))) shouldBe false
  }

  @Test
  fun anOutputFrameThatMovedIsRefusedWhateverTheChainLooksLike() {
    val preview = composition().toMedia3Preview()

    preview.updateParameters(composition(output = Size(160, 90))) shouldBe false
  }

  // An overlay is the one that matters in practice: it owns a texture media3 uploads and frees on
  // its own render thread, so there is no swap of one on offer and an edit carrying one rebuilds.
  @Test
  fun anEffectThatIsNeitherAColourNorAGeometryMatrixIsRefused() {
    val preview = composition(compositionEffects = listOf(opaque())).toMedia3Preview()

    preview.updateParameters(composition(compositionEffects = listOf(opaque()))) shouldBe false
  }

  private fun RgbMatrix.gain(): Float = getMatrix(0L, false)[0]

  private fun brightness(scale: Float): ResolvedEffect =
    ResolvedEffect(
      "brightness",
      PlatformEffect(
        RgbAdjustment
          .Builder()
          .setRedScale(scale)
          .setGreenScale(scale)
          .setBlueScale(scale)
          .build(),
      ),
    )

  private fun opaque(): ResolvedEffect = ResolvedEffect("opaque", PlatformEffect(OpaqueEffect()))

  private fun composition(
    output: Size = FRAME,
    fit: Fit = Fit.Contain,
    fill: Fill = Fill.Black,
    trackStart: Duration = Duration.ZERO,
    compositionEffects: List<ResolvedEffect> = emptyList(),
    clips: List<ResolvedClip> = listOf(clip(source = "a.mp4", length = 1.seconds)),
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = false,
            start = trackStart,
            clips = clips,
          ),
        ),
      compositionGeometry = emptyList(),
      compositionInputSize = output,
      compositionEffects = compositionEffects,
      output =
        OutputFormat(
          size = output,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      layoutSize = output,
      fit = fit,
      fill = fill,
      duration = trackStart + clips.fold(Duration.ZERO) { total, clip -> total + clip.duration },
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  private fun clip(
    source: String,
    length: Duration,
    size: Size = FRAME,
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of(source),
      info =
        MediaInfo(
          duration = length,
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
      end = length,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, length),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, length),
    )

  private companion object {
    val FRAME = Size(320, 180)
    const val DIM = 0.4f
    const val BRIGHT = 1.4f
    const val TOLERANCE = 0.0001f
    val RED = 0xFFFF0000.toInt()
    val BLUE = 0xFF0000FF.toInt()
  }
}

/**
 * An effect the chain can hold and nothing can be swapped into, standing in for an overlay.
 */
private class OpaqueEffect : Effect
