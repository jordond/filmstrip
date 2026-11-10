package dev.jordond.filmstrip.media3

import androidx.media3.common.Effect
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbMatrix
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
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
class Media3PreviewMediaTest {
  @Test
  fun spansTileTheTimelineBehindTheGapTheTrackStartsWith() {
    val preview =
      composition(
        trackStart = 500.milliseconds,
        clips = laid(500.milliseconds, "a.mp4" to 1.seconds, "b.mp4" to 2.seconds),
      ).toMedia3Preview(Media3ClipReads.None)

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
        clips = laid(500.milliseconds, "a.mp4" to 1.seconds, "b.mp4" to 2.seconds),
      ).toMedia3Preview(Media3ClipReads.None)

    val first = checkNotNull(preview.spanAt(900.milliseconds))
    first.positionIn(900.milliseconds) shouldBe 400.milliseconds

    val second = checkNotNull(preview.spanAt(2_300.milliseconds))
    second.positionIn(2_300.milliseconds) shouldBe 800.milliseconds
  }

  // Half a slot in at 25 fps rounds up onto slot one, and the second clip's start half a slot past
  // its own slot rounds up with it. Each span opens on the slot the export lays its clip on.
  @Test
  fun spansOpenOnTheSlotsAnOffGridTrackStartLandsOn() {
    val clips = laid(HALF_SLOT_IN, "a.mp4" to 1.seconds, "b.mp4" to 2.seconds)
    val preview =
      composition(trackStart = HALF_SLOT_IN, clips = clips, frameRate = OFF_GRID_RATE)
        .toMedia3Preview(Media3ClipReads.None)

    preview.spans.map { it.start } shouldBe
      clips.map { outputSlotTime(outputSlotsOf(it.span, OFF_GRID_RATE).first, OFF_GRID_RATE) }
    preview.spans.map { it.end } shouldBe
      clips.map { outputSlotTime(outputSlotsOf(it.span, OFF_GRID_RATE).last + 1, OFF_GRID_RATE) }
  }

  @Test
  fun aCompositionTimeMapsFromTheSlotItsClipOpensOn() {
    val clips = laid(HALF_SLOT_IN, "a.mp4" to 1.seconds, "b.mp4" to 2.seconds)
    val preview =
      composition(trackStart = HALF_SLOT_IN, clips = clips, frameRate = OFF_GRID_RATE)
        .toMedia3Preview(Media3ClipReads.None)

    val first = checkNotNull(preview.spanAt(900.milliseconds))
    first.positionIn(900.milliseconds) shouldBe 900.milliseconds - outputSlotTime(1, OFF_GRID_RATE)

    val second = checkNotNull(preview.spanAt(2_300.milliseconds))
    val secondOpens = outputSlotTime(outputSlotsOf(clips[1].span, OFF_GRID_RATE).first, OFF_GRID_RATE)
    second.positionIn(2_300.milliseconds) shouldBe 2_300.milliseconds - secondOpens
  }

  @Test
  fun theGapTheTrackStartsWithCoversNoClipAtAll() {
    val preview =
      composition(trackStart = 500.milliseconds, clips = laid(500.milliseconds, "a.mp4" to 1.seconds))
        .toMedia3Preview(Media3ClipReads.None)

    preview.spanAt(200.milliseconds) shouldBe null
  }

  @Test
  fun aColorParameterReachesTheStandingChainWithoutItBeingRebuilt() {
    val preview = composition(compositionEffects = listOf(brightness(DIM))).toMedia3Preview(Media3ClipReads.None)
    val live = preview.compositionEffects.filterIsInstance<RgbMatrix>().single()
    live.gain() shouldBe (DIM plusOrMinus TOLERANCE)

    preview.updateParameters(
      composition(compositionEffects = listOf(brightness(BRIGHT))),
      Media3ClipReads.None,
    ) shouldBe
      true

    // The same object, still in the chain the player is drawing with, now answering differently.
    live.gain() shouldBe (BRIGHT plusOrMinus TOLERANCE)
    preview.compositionEffects.filterIsInstance<RgbMatrix>().single() shouldBe live
  }

  @Test
  fun aMatrixThatWouldStillBeANoOpKeepsItsPositionInTheChain() {
    val preview = composition(compositionEffects = listOf(brightness(1f))).toMedia3Preview(Media3ClipReads.None)
    val live = preview.compositionEffects.filterIsInstance<RgbMatrix>().single()

    // media3 drops a no-op while it builds the chain, and a dropped position cannot take a later
    // parameter, so this one never admits to being one.
    live.isNoOp(FRAME.width, FRAME.height) shouldBe false
  }

  @Test
  fun anEffectAddedToTheChainIsRefusedSinceTheShapeMoved() {
    val preview = composition(compositionEffects = listOf(brightness(DIM))).toMedia3Preview(Media3ClipReads.None)

    preview.updateParameters(
      composition(compositionEffects = listOf(brightness(DIM), brightness(BRIGHT))),
      Media3ClipReads.None,
    ) shouldBe
      false
  }

  // The track opens on a gap, so the fill is painted and its pass is in the chain.
  @Test
  fun aFillColorIsRefusedSinceThePassThatPaintsItIsBuiltOnce() {
    val preview = composition(fill = Fill.Solid(RED), trackStart = LEAD).toMedia3Preview(Media3ClipReads.None)

    preview.updateParameters(composition(fill = Fill.Solid(RED), trackStart = LEAD), Media3ClipReads.None) shouldBe
      true
    preview.updateParameters(composition(fill = Fill.Solid(BLUE), trackStart = LEAD), Media3ClipReads.None) shouldBe
      false
  }

  @Test
  fun anOutputFrameThatMovedIsRefusedWhateverTheChainLooksLike() {
    val preview = composition().toMedia3Preview(Media3ClipReads.None)

    preview.updateParameters(composition(output = Size(160, 90)), Media3ClipReads.None) shouldBe false
  }

  @Test
  fun aPlannedBitrateIsNoReasonToRebuild() {
    val preview = composition().toMedia3Preview(Media3ClipReads.None)

    preview.updateParameters(composition(bitrate = PLANNED_BITRATE), Media3ClipReads.None) shouldBe true
  }

  @Test
  fun droppingTheAudioIsRefused() {
    val preview = composition().toMedia3Preview(Media3ClipReads.None)

    preview.updateParameters(composition(audioCodec = AudioCodec.None), Media3ClipReads.None) shouldBe false
  }

  // A copy lowers to other sequences than a transcode does, so the edge is refused however few slots either side has.
  @Test
  fun leavingTheCopyPathIsRefusedWithNoSlotOnEitherSide() {
    val preview = composition(path = ExportPath.Copy).toMedia3Preview(Media3ClipReads.None)

    preview.updateParameters(composition(path = ExportPath.Transcode), Media3ClipReads.None) shouldBe false
  }

  // An overlay is the one that matters in practice: it owns a texture media3 uploads and frees on
  // its own render thread, so there is no swap of one on offer and an edit carrying one rebuilds.
  @Test
  fun anEffectThatIsNeitherAColorNorAGeometryMatrixIsRefused() {
    val preview = composition(compositionEffects = listOf(opaque())).toMedia3Preview(Media3ClipReads.None)

    preview.updateParameters(composition(compositionEffects = listOf(opaque())), Media3ClipReads.None) shouldBe false
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
      EffectStage.Color,
    )

  private fun opaque(): ResolvedEffect = ResolvedEffect("opaque", PlatformEffect(OpaqueEffect()), EffectStage.Color)

  private fun composition(
    output: Size = FRAME,
    fill: Fill = Fill.Black,
    trackStart: Duration = Duration.ZERO,
    compositionEffects: List<ResolvedEffect> = emptyList(),
    clips: List<ResolvedClip> = laid(Duration.ZERO, "a.mp4" to 1.seconds),
    frameRate: Int = 30,
    bitrate: Bitrate? = null,
    audioCodec: AudioCodec = AudioCodec.Aac,
    path: ExportPath = ExportPath.Transcode,
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
      compositionEffects = compositionEffects,
      output =
        OutputFormat(
          size = output,
          videoCodec = VideoCodec.H264,
          audioCodec = audioCodec,
          bitrate = bitrate,
          frameRate = frameRate,
          audioFormat = null,
        ),
      layoutSize = output,
      fill = fill,
      duration = trackStart + clips.fold(Duration.ZERO) { total, clip -> total + clip.sourceDuration },
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = path,
    )

  /**
   * Clips laid end to end from [from], each a whole source of the length it is paired with.
   */
  private fun laid(
    from: Duration,
    vararg clips: Pair<String, Duration>,
  ): List<ResolvedClip> {
    var at = from
    return clips.map { (source, length) -> clip(source, length, at).also { at += length } }
  }

  private fun clip(
    source: String,
    length: Duration,
    at: Duration,
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
              hasAlpha = false,
            ),
          audio = null,
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = length,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, length),
      startsAtKeyFrame = false,
      span = TimeRange.of(at, at + length),
      sourceIndex = 0,
    )

  private companion object {
    val FRAME = Size(320, 180)
    val LEAD = 500.milliseconds

    // Half a slot at OFF_GRID_RATE, which rounds up onto the first slot.
    val HALF_SLOT_IN = 20.milliseconds
    const val OFF_GRID_RATE = 25
    val PLANNED_BITRATE = Bitrate.kbps(750)
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
