package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.AlphaScale
import androidx.media3.effect.RgbMatrix
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.fill.FillCoverBlur
import dev.jordond.filmstrip.media3.internal.fill.FillFlatten
import dev.jordond.filmstrip.transform.internal.geometry.ClipPlacement
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.transform.internal.resolve.GainSegment
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
  fun `flattens when a clip leaves bars and not when every clip fills the frame`() {
    composition(clips = listOf(barred())).compositionVideoEffects().any { it is FillFlatten } shouldBe true
    composition().compositionVideoEffects().any { it is FillFlatten } shouldBe false
  }

  @Test
  fun `flattens after every effect outside the Composite stage`() {
    val first = AlphaScale(0.5f)
    val second = AlphaScale(0.25f)
    val effects =
      composition(clips = listOf(barred()), compositionEffects = listOf(resolvedEffect(first), resolvedEffect(second)))
        .compositionVideoEffects()

    effects.last().shouldBeInstanceOf<FillFlatten>()
    effects[effects.lastIndex - 1] shouldBe second
    effects[effects.lastIndex - 2] shouldBe first
  }

  // The fill is painted in before the overlays, so a grade never reaches a bar and an overlay placed
  // in one draws over the fill rather than under it. A real TextureOverlay reaches android.opengl on
  // construction, so the overlay here is any effect the planner put in the Composite stage.
  @Test
  fun `flattens between a grade and an overlay`() {
    val grade = matrix()
    val overlay = AlphaScale(0.5f)
    val effects =
      composition(
        clips = listOf(barred()),
        compositionEffects = listOf(resolvedEffect(grade), resolvedEffect(overlay, EffectStage.Composite)),
      ).compositionVideoEffects()

    val flatten = effects.indexOfFirst { it is FillFlatten }
    effects[flatten - 1] shouldBe grade
    effects.drop(flatten + 1) shouldBe listOf(overlay)
  }

  // media3 merges consecutive matrices into one shader program with nothing clamping between them,
  // and the planner folds a run of color effects per stage. Every clip's chain ends on the pass that
  // places it, so the clip's grade is written out before the composition's runs, the way the other
  // backends write a frame between the two.
  @Test
  fun `a clip grade ends its program before the composition grade runs`() {
    val grade = resolvedEffect(matrix())
    val graded = composition(compositionEffects = listOf(grade), clips = listOf(clip(effects = listOf(grade))))
    val clip =
      graded.tracks
        .single()
        .clips
        .single()

    clip.clipVideoEffects(Fill.Solid(0xFF000000.toInt()), OUTPUT).last().shouldBeInstanceOf<PlacedFrame>()
    graded.compositionVideoEffects().first().shouldBeInstanceOf<RgbMatrix>()
  }

  @Test
  fun `blurs a clip for Fill Blurred and leaves it alone for Fill Solid`() {
    barred().clipVideoEffects(Fill.Blurred(), OUTPUT).any { it is FillCoverBlur } shouldBe true
    barred().clipVideoEffects(Fill.Solid(0xFFFF0000.toInt()), OUTPUT).any { it is FillCoverBlur } shouldBe false
  }

  // Either side of the one-pixel bar the shared placement draws its line at, at the natural frame and at a smaller one
  // the clip is lowered into, so the blur is lowered exactly where the placement says the clip leaves bars.
  @Test
  fun `blurs a clip exactly where its placement leaves bars`() {
    val natural = Size(1920, 1080)

    listOf(natural, Size(480, 270)).forEach { output ->
      listOf(Size(19200, 10805), Size(19200, 10806), Size(1918, 1080), Size(1440, 1080)).forEach { size ->
        val placement = placementOf(size, listOf(emptyList()), natural)
        clip(size = size, placement = placement)
          .clipVideoEffects(Fill.Blurred(), output)
          .any { it is FillCoverBlur } shouldBe placement.leavesBars
      }
    }
  }

  @Test
  fun `runs no cover blur for a canvas that fills the frame`() {
    val output = Size(1920, 1080)
    val size = Size(1600, 1600)
    val pan = KenBurns(NormalizedRect.Full, NormalizedRect(0f, 0f, 0.5f, 0.5f))
    val placement = placementOf(size, listOf(listOf(Crop(AspectRatio(16, 9), Fit.Contain), pan)), output)

    placement.leavesBars shouldBe true
    clip(size = size, placement = placement, canvasEffectIndices = listOf(-1))
      .clipVideoEffects(Fill.Blurred(), output)
      .any { it is FillCoverBlur } shouldBe false
  }

  @Test
  fun `every frame is scaled by what the curve holds there`() {
    val fade = fadeOut()

    fade.applyTo(ShortArray(FRAMES_PER_SECOND) { SAMPLE }).toList() shouldBe
      (0 until FRAMES_PER_SECOND).map { frame ->
        (SAMPLE * fade.gainAt((frame * FRAME_MILLIS).milliseconds)).toInt().toShort()
      }
  }

  @Test
  fun `a held level scales every frame by the one factor`() {
    val held = ResolvedGain.constant(0.4f, Duration.ZERO, 1.seconds)

    held.applyTo(ShortArray(FRAMES_PER_SECOND) { SAMPLE }).toSet() shouldBe setOf((SAMPLE * 0.4f).toInt().toShort())
  }

  @Test
  fun `a gain above one holds a hot sample at full scale`() {
    val boost = ResolvedGain(listOf(GainSegment(Duration.ZERO, 1.seconds, 2f, 1f)))

    boost.applyTo(shortArrayOf(SAMPLE)).single() shouldBe Short.MAX_VALUE
    boost.applyTo(shortArrayOf((-SAMPLE).toShort())).single() shouldBe Short.MIN_VALUE
  }

  @Test
  fun `a held boost and a ramp passing the same factor land on the same sample`() {
    val held = ResolvedGain.constant(1.5f, Duration.ZERO, 1.seconds)
    val ramp = ResolvedGain(listOf(GainSegment(Duration.ZERO, 1.seconds, 1f, 2f)))
    val middle = FRAMES_PER_SECOND / 2
    ramp.gainAt((middle * FRAME_MILLIS).milliseconds) shouldBe 1.5f

    listOf(SAMPLE, QUIET_SAMPLE).forEach { sample ->
      val samples = ShortArray(FRAMES_PER_SECOND) { sample }
      ramp.applyTo(samples)[middle] shouldBe held.applyTo(samples)[middle]
    }
    held.applyTo(shortArrayOf(QUIET_SAMPLE)).single() shouldBe (QUIET_SAMPLE * 1.5f).toInt().toShort()
  }

  // The chain a mixing clip gets cannot be built off device, since ChannelMixingAudioProcessor
  // holds its matrices in an android.util.SparseArray. These are the cases that build no mixer.
  @Test
  fun `a unity gain with nothing to mix runs through nothing`() {
    audioProcessors(ResolvedGain.constant(1f, Duration.ZERO, 1.seconds), mixes = false).shouldBeEmpty()
  }

  @Test
  fun `a standing graph carries its level processor at unity too`() {
    audioProcessors(ResolvedGain.constant(1f, Duration.ZERO, 1.seconds), mixes = false, liveGain = true)
      .single()
      .shouldBeInstanceOf<ClipGainProcessor>()
  }

  // A second falling from full to silence, which is the shape every fade out lowers to.
  private fun fadeOut(): ResolvedGain = ResolvedGain(listOf(GainSegment(Duration.ZERO, 1.seconds, 1f, 0f)))

  // Runs the level processor over one mono 16-bit buffer.
  private fun ResolvedGain.applyTo(samples: ShortArray): ShortArray {
    val processor = ClipGainProcessor(this)
    processor.configure(AudioProcessor.AudioFormat(FRAMES_PER_SECOND, 1, C.ENCODING_PCM_16BIT))
    processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

    val input = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
    samples.forEach { input.putShort(it) }
    input.flip()
    processor.queueInput(input)

    val output = processor.output.asShortBuffer()
    return ShortArray(output.remaining()) { output.get(it) }
  }

  // Asking media3 to loop would leave one item for the whole run, and it repeats by item index, so
  // a leading gap would be repeated along with them. The plan holds every pass instead.
  @Test
  fun `a looping track lays one item per pass and never asks media3 to repeat`() {
    // Building real items reaches android.net.Uri, which a host test has no implementation of.
    // Video alone, since the audio processors reach android.util.SparseArray for the same reason
    // and what is counted here is the items rather than what they carry.
    mockkStatic(Uri::class)
    every { Uri.fromFile(any()) } returns mockk(relaxed = true)
    every { Uri.parse(any()) } returns mockk(relaxed = true)
    try {
      val laid = List(4) { clip() }
      val sequence =
        composition(
          clips = laid,
          looping = true,
          trackStart = 700.milliseconds,
          content = TrackContent.Video,
        ).toMedia3(Media3ClipReads.None)
          .sequences
          .single()

      sequence.isLooping shouldBe false
      // The leading gap plus one item per laid pass. Asked to loop, media3 would hold one item here
      // and repeat it along with the gap.
      sequence.editedMediaItems.size shouldBe laid.size + 1
      sequence.editedMediaItems
        .first()
        .isGap shouldBe true
      // A gap reaches the composition's chain at the output frame's size, the same as every clip.
      sequence.editedMediaItems
        .first()
        .effects.videoEffects shouldBe
        listOf(PlacedFrame(NormalizedRect.Full, OUTPUT))
      sequence.editedMediaItems.drop(1).forEach { it.isGap shouldBe false }
    } finally {
      unmockkAll()
    }
  }

  // The track above opens on a gap, which is the shape asking media3 to loop always got wrong. A
  // track that starts at zero has no gap, so looping one item reproduced the same run and this is
  // the case the old lowering still got right. What it never got right is the cut last pass, which
  // only shows up as the item count.
  @Test
  fun `a looping track that starts at zero still lays one item per pass`() {
    mockkStatic(Uri::class)
    every { Uri.fromFile(any()) } returns mockk(relaxed = true)
    try {
      val laid = List(4) { clip() }
      val sequence =
        composition(
          clips = laid,
          looping = true,
          trackStart = Duration.ZERO,
          content = TrackContent.Video,
        ).toMedia3(Media3ClipReads.None)
          .sequences
          .single()

      sequence.isLooping shouldBe false
      sequence.editedMediaItems.size shouldBe laid.size
      sequence.editedMediaItems.forEach { it.mediaItem.localConfiguration shouldNotBe null }
    } finally {
      unmockkAll()
    }
  }

  private fun matrix(): RgbMatrix =
    RgbMatrix { _, _ -> floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f) }

  private fun composition(
    fill: Fill = Fill.Black,
    compositionEffects: List<ResolvedEffect> = emptyList(),
    clips: List<ResolvedClip> = listOf(clip()),
    hdrTransfer: HdrTransfer? = null,
    looping: Boolean = false,
    trackStart: Duration = Duration.ZERO,
    content: TrackContent = TrackContent.AudioAndVideo,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = content,
            looping = looping,
            start = trackStart,
            clips = clips,
          ),
        ),
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
      fill = fill,
      duration = 1.seconds,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = hdrTransfer,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  // A portrait clip in the landscape output, pillarboxed under the default fit.
  private fun barred(): ResolvedClip =
    clip(size = PORTRAIT, placement = placementOf(PORTRAIT, listOf(emptyList()), OUTPUT))

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
              hasAlpha = false,
            ),
          audioTracks = emptyList(),
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
    stage: EffectStage = EffectStage.Color,
  ): ResolvedEffect = ResolvedEffect("test", PlatformEffect(effect), stage)
}

private val OUTPUT = Size(320, 180)
private val PORTRAIT = Size(360, 640)

// Eight frames a second, so a frame position lands on a round time and a test can name the time it
// expects without repeating the conversion the provider does.
private const val QUIET_SAMPLE: Short = 1_001
private const val FRAMES_PER_SECOND = 8
private const val FRAME_MILLIS = 125L

// Loud enough that doubling it runs past what a short holds.
private const val SAMPLE: Short = 20_000
