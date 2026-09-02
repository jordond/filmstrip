package dev.jordond.filmstrip.media3.internal

import androidx.media3.effect.AlphaScale
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
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.sigmaFor
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The lowering from a resolved composition and its clips onto media3's own effect lists.
 *
 * media3 has no fill of its own, so filmstrip synthesises one as GL passes and stitches them into
 * the composition's and each clip's effect chain. What matters here is where those passes land,
 * never how they render, so nothing in this file touches GL.
 */
class Media3CompositionTest {
  @Test
  fun `flattens for Fit Contain and leaves Fit Crop alone`() {
    composition(fit = Fit.Contain).compositionVideoEffects().any { it is FillFlatten } shouldBe true
    composition(fit = Fit.Crop).compositionVideoEffects().any { it is FillFlatten } shouldBe false
  }

  @Test
  fun `flattens last, after every composition effect`() {
    val first = AlphaScale(0.5f)
    val second = AlphaScale(0.25f)
    val effects =
      composition(fit = Fit.Contain, compositionEffects = listOf(resolvedEffect(first), resolvedEffect(second)))
        .compositionVideoEffects()

    effects.last().shouldBeInstanceOf<FillFlatten>()
    effects[effects.lastIndex - 1] shouldBe second
    effects[effects.lastIndex - 2] shouldBe first
  }

  // media3 merges consecutive matrices into one shader program with nothing clamping between them,
  // and the planner folds a run of colour effects per stage. The boundary pass writes the clip's
  // stage out before the composition's runs, which is what the other backends do by writing a frame.
  @Test
  fun `a clip grade and a composition grade are separated by a boundary`() {
    val graded =
      composition(
        fit = Fit.Crop,
        compositionEffects = listOf(resolvedEffect(matrix())),
        clips = listOf(clip(effects = listOf(resolvedEffect(matrix())))),
      )

    graded.compositionVideoEffects().first().shouldBeInstanceOf<ColorStageBoundary>()
  }

  @Test
  fun `one stage grading on its own needs no boundary`() {
    val clipOnly = composition(fit = Fit.Crop, clips = listOf(clip(effects = listOf(resolvedEffect(matrix())))))
    val compositionOnly = composition(fit = Fit.Crop, compositionEffects = listOf(resolvedEffect(matrix())))

    clipOnly.compositionVideoEffects().any { it is ColorStageBoundary } shouldBe false
    compositionOnly.compositionVideoEffects().any { it is ColorStageBoundary } shouldBe false
  }

  // A kept grade lowers its colour to a pass of its own, which ends the program and clamps at the
  // transfer's ceiling without help.
  @Test
  fun `a kept grade needs no boundary either`() {
    val graded =
      composition(
        fit = Fit.Crop,
        compositionEffects = listOf(resolvedEffect(matrix())),
        clips = listOf(clip(effects = listOf(resolvedEffect(matrix())))),
        hdrTransfer = HdrTransfer.Pq,
      )

    graded.compositionVideoEffects().any { it is ColorStageBoundary } shouldBe false
  }

  @Test
  fun `blurs a clip for Fill Blurred and leaves it alone for Fill Solid`() {
    val landscape = clip(size = Size(640, 360))
    val squareOutput = Size(360, 360)

    landscape
      .clipVideoEffects(Fit.Contain, Fill.Blurred(), squareOutput)
      .any { it is FillCoverBlur } shouldBe true
    landscape
      .clipVideoEffects(Fit.Contain, Fill.Solid(0xFFFF0000.toInt()), squareOutput)
      .any { it is FillCoverBlur } shouldBe false
  }

  // Pinned against the shared Fill.Blurred.sigmaFor, not a number typed here, so a later change to
  // the KDoc's formula moves this test rather than leaving it silently wrong.
  @Test
  fun `sigma is the radius times the output's shorter side`() {
    val output = Size(1080, 1920)

    Fill.Blurred(radius = 0.04f).sigmaFor(output) shouldBe (43.2f plusOrMinus 0.01f)
  }

  // The default radius against a 1080-pixel shorter side, pinned so a later change to the tap
  // budget has to move this test rather than quietly changing how soft the default blur looks.
  @Test
  fun `downscales a wide sigma so the tap radius stays fixed`() {
    val output = Size(1080, 1920)
    val sigma = Fill.Blurred(radius = 0.04f).sigmaFor(output)

    val downscale = downscaleFor(sigma)
    downscale shouldBe 6
    tapRadiusFor(sigma / downscale) shouldBe 22
  }

  /**
   * The clamp is this backend's own ceiling on how long the generated shader may get, not a claim
   * about the blur, so what is asserted is that a sigma past it stops there. Both the threshold
   * and the expected result are read off the constants the function itself reads, so a change to
   * either moves this test rather than leaving it pinned to a number that no longer means
   * anything.
   */
  @Test
  fun `a sigma past the tap budget clamps to the shader's own ceiling`() {
    val atCap = MAX_TAP_RADIUS / TAP_STANDARD_DEVIATIONS

    tapRadiusFor(atCap / 2f) shouldBeLessThan MAX_TAP_RADIUS
    tapRadiusFor(atCap) shouldBe MAX_TAP_RADIUS
    tapRadiusFor(atCap * 1.5f) shouldBe MAX_TAP_RADIUS
    tapRadiusFor(atCap * 4f) shouldBe MAX_TAP_RADIUS
  }

  @Test
  fun `a small sigma needs no downscale at all`() {
    val output = Size(1080, 1920)
    val sigma = Fill.Blurred(radius = 0.001f).sigmaFor(output)

    downscaleFor(sigma) shouldBe 1
  }

  // Stands in for whatever a resolver hands back for a colour effect, which media3 merges with its
  // neighbours. Only the type it lowered to decides where the boundary goes.
  private fun matrix(): RgbMatrix =
    RgbMatrix { _, _ -> floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f) }

  private fun composition(
    fit: Fit,
    fill: Fill = Fill.Black,
    compositionEffects: List<ResolvedEffect> = emptyList(),
    clips: List<ResolvedClip> = listOf(clip()),
    hdrTransfer: HdrTransfer? = null,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = false,
            start = Duration.ZERO,
            clips = clips,
          ),
        ),
      compositionGeometry = emptyList(),
      compositionInputSize = Size(320, 180),
      compositionEffects = compositionEffects,
      output =
        OutputFormat(
          size = Size(320, 180),
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = 30,
          audioFormat = null,
        ),
      layoutSize = Size(320, 180),
      fit = fit,
      fill = fill,
      duration = 1.seconds,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = hdrTransfer,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  private fun clip(
    size: Size = Size(640, 360),
    effects: List<ResolvedEffect> = emptyList(),
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
      gain = 1f,
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, 1.seconds),
    )

  private fun resolvedEffect(handle: Any): ResolvedEffect = ResolvedEffect("test", PlatformEffect(handle))
}
