package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
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
import dev.jordond.filmstrip.transform.internal.GainSegment
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.ResolvedGain
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.sigmaFor
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

  @Test
  fun `a ramping gain is sampled off the resolved curve frame by frame`() {
    val fade = fadeOut()
    val provider = ResolvedGainProvider(fade)

    (0 until FRAMES_PER_SECOND).forEach { frame ->
      provider.getGainFactorAtSamplePosition(frame.toLong(), FRAMES_PER_SECOND) shouldBe
        fade.gainAt((frame * FRAME_MILLIS).milliseconds)
    }
  }

  @Test
  fun `a frame the curve is not unity at has no unity boundary`() {
    ResolvedGainProvider(fadeOut()).isUnityUntil(FRAMES_PER_SECOND / 2L, FRAMES_PER_SECOND) shouldBe C.TIME_UNSET
  }

  // media3 throws on TIME_UNSET at a unity frame and spins forever on a boundary that does not
  // advance, so a curve that leaves unity right away still has to name the frame after this one.
  @Test
  fun `a unity frame always names a boundary past itself`() {
    ResolvedGainProvider(fadeOut()).isUnityUntil(0, FRAMES_PER_SECOND) shouldBe 1
  }

  @Test
  fun `a hold before a fade is unity until the fade starts`() {
    val provider = ResolvedGainProvider(heldThenFadedOut())

    provider.isUnityUntil(0, FRAMES_PER_SECOND) shouldBe FRAMES_PER_SECOND.toLong()
    provider.isUnityUntil(FRAMES_PER_SECOND / 2L, FRAMES_PER_SECOND) shouldBe FRAMES_PER_SECOND.toLong()
  }

  // A curve holds its last gain past its own end, so a fade in never leaves unity again and media3
  // can copy the rest of the stream through untouched.
  @Test
  fun `a fade in is unity to the end of the source once it lands`() {
    val faded = ResolvedGain(listOf(GainSegment(Duration.ZERO, 1.seconds, 0f, 1f)))

    ResolvedGainProvider(faded).isUnityUntil(FRAMES_PER_SECOND.toLong(), FRAMES_PER_SECOND) shouldBe
      C.TIME_END_OF_SOURCE
  }

  @Test
  fun `media3 scales every frame by what the curve holds there`() {
    val fade = fadeOut()

    fade.applyTo(ShortArray(FRAMES_PER_SECOND) { SAMPLE }).toList() shouldBe
      (0 until FRAMES_PER_SECOND).map { frame ->
        (SAMPLE * fade.gainAt((frame * FRAME_MILLIS).milliseconds)).toInt().toShort()
      }
  }

  /**
   * The overflow is media3's own: its 16-bit path narrows `sample * gain` straight to a short, so a
   * boost carrying a hot sample past full scale flips its sign rather than clipping. The scaled
   * mixing matrix a constant gain rides on does the same, which is why nothing is clamped on the
   * way in.
   */
  @Test
  fun `a gain above one overflows a hot sample rather than clipping it`() {
    val boost = ResolvedGain(listOf(GainSegment(Duration.ZERO, 1.seconds, 2f, 1f)))

    ResolvedGainProvider(boost).getGainFactorAtSamplePosition(0, FRAMES_PER_SECOND) shouldBe
      boost.gainAt(Duration.ZERO)
    boost.applyTo(shortArrayOf(SAMPLE)).single() shouldBe (-25_536).toShort()
  }

  @Test
  fun `only a ramping gain needs a processor of its own`() {
    rampProcessorFor(ResolvedGain.constant(0.5f, Duration.ZERO, 1.seconds)) shouldBe null
    rampProcessorFor(fadeOut()).shouldBeInstanceOf<GainProcessor>()
  }

  // The chain a mixing clip gets cannot be built off device, since ChannelMixingAudioProcessor
  // holds its matrices in an android.util.SparseArray. This is the one case that builds no mixer.
  @Test
  fun `a unity gain with nothing to mix runs through nothing`() {
    audioProcessors(ResolvedGain.constant(1f, Duration.ZERO, 1.seconds), mixes = false).shouldBeEmpty()
  }

  // A second falling from full to silence, which is the shape every fade out lowers to.
  private fun fadeOut(): ResolvedGain = ResolvedGain(listOf(GainSegment(Duration.ZERO, 1.seconds, 1f, 0f)))

  private fun heldThenFadedOut(): ResolvedGain =
    ResolvedGain(
      listOf(
        GainSegment(Duration.ZERO, 1.seconds, 1f, 1f),
        GainSegment(1.seconds, 2.seconds, 1f, 0f),
      ),
    )

  // Runs the real media3 processor over one mono 16-bit buffer. Reading the frames back out is the
  // only way to see what the provider's unity boundaries actually made it do.
  private fun ResolvedGain.applyTo(samples: ShortArray): ShortArray {
    val processor = GainProcessor(ResolvedGainProvider(this))
    processor.configure(AudioProcessor.AudioFormat(FRAMES_PER_SECOND, 1, C.ENCODING_PCM_16BIT))
    processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

    val input = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
    samples.forEach { input.putShort(it) }
    input.flip()
    processor.queueInput(input)

    val output = processor.output.asShortBuffer()
    return ShortArray(output.remaining()) { output.get(it) }
  }

  // Stands in for whatever a resolver hands back for a colour effect, which media3 merges with its
  // neighbours. Only the type it lowered to decides where the boundary goes.
  @Test
  fun `a looping track lays a whole pass for every length that fits`() {
    // media3 repeats a sequence by item index, and a leading gap is one of those items, so an
    // offset looping track lays its own passes instead. Two and a half lengths of room is two whole
    // passes and a cut one, which is what tells this apart from a gap counted into the period.
    val passes = listOf(clip()).passesCovering(2_500.milliseconds)

    passes.size shouldBe 3
    passes.dropLast(1).forEach { it.duration shouldBe 1.seconds }
    passes.last().duration shouldBe 500.milliseconds
  }

  @Test
  fun `the passes a looping track lays fill exactly what it was given`() {
    // Anything longer would lengthen the export, since the sequence no longer loops and the longest
    // sequence is what sets the composition's own duration.
    listOf(clip())
      .passesCovering(2_600.milliseconds)
      .fold(Duration.ZERO) { total, clip -> total + clip.duration } shouldBe 2_600.milliseconds
  }

  @Test
  fun `a run of clips repeats in order rather than one clip at a time`() {
    val first = clip()
    val second = clip()

    val passes = listOf(first, second).passesCovering(3.seconds)

    passes.size shouldBe 3
    passes[0].source shouldBe first.source
    passes[1].source shouldBe second.source
    passes[2].source shouldBe first.source
  }

  @Test
  fun `a pass cut short carries only the part of its gain curve it reaches`() {
    // The curve ramps across the clip's whole second, so a pass cut at 600ms has to end on what the
    // curve reads there rather than on the one it would have reached had the pass run out.
    val ramp = ResolvedGain(listOf(GainSegment(Duration.ZERO, 1.seconds, 0f, 1f)))
    val faded = clip().let { ResolvedClip(it.source, it.info, it.start, it.end, it.effects, ramp, false, it.span) }

    val cut = listOf(faded).passesCovering(1_600.milliseconds).last()

    cut.duration shouldBe 600.milliseconds
    cut.gain.gainAt(600.milliseconds) shouldBe (ramp.gainAt(600.milliseconds) plusOrMinus 0.001f)
    cut.gain.end shouldBe 600.milliseconds
  }

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
      gain = ResolvedGain.constant(1f, Duration.ZERO, 1.seconds),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, 1.seconds),
    )

  private fun resolvedEffect(handle: Any): ResolvedEffect = ResolvedEffect("test", PlatformEffect(handle))
}

// Eight frames a second, so a frame position lands on a round time and a test can name the time it
// expects without repeating the conversion the provider does.
private const val FRAMES_PER_SECOND = 8
private const val FRAME_MILLIS = 125L

// Loud enough that doubling it runs past what a short holds.
private const val SAMPLE: Short = 20_000
