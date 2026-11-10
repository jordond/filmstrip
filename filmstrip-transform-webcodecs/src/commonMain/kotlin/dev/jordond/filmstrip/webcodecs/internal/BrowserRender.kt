package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.colorMatrixOfColumnMajor4x4OrNull
import dev.jordond.filmstrip.effects.color.then
import dev.jordond.filmstrip.effects.color.toColumnMajor4x4
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.geometry.coverScale
import dev.jordond.filmstrip.transform.internal.geometry.showsBlurredFill
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.resolve.drawnRect
import dev.jordond.filmstrip.transform.internal.resolve.effectsOverFill
import dev.jordond.filmstrip.transform.internal.resolve.effectsUnderFill
import dev.jordond.filmstrip.webcodecs.internal.audio.BrowserAudioMix
import dev.jordond.filmstrip.webcodecs.internal.interop.MP4
import dev.jordond.filmstrip.webcodecs.internal.interop.containerFor
import dev.jordond.filmstrip.webcodecs.internal.interop.muxCodecKey
import dev.jordond.filmstrip.webcodecs.internal.interop.webCodecString
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Everything the pipeline needs that is not per-frame.
 *
 * @property writesVideo Whether the output carries a video track. False for an [AudioSpec.AudioOnly]
 * export, where [clips] is empty, [videoEncoding] is null, and the frame geometry, the bitrate and
 * [estimatedFrames] all read zero because nothing is drawn.
 * @property bitrate The video bitrate the plan settled on, or zero when no encoder is opened.
 * @property videoEncoding The video encoder [BrowserEncoder] opens, or null on an audio-only render and on a
 * [dev.jordond.filmstrip.export.ExportPath.Copy] render, which never opens one. [BrowserStreamCopy] always writes mp4
 * regardless of what a copy's source codec would map to.
 * @property container The container the file is written in, from [containerFor]. A copy is always mp4, since
 * [BrowserStreamCopy] writes nothing else.
 * @property audioCodec The codec the plan writes audio with, [AudioCodec.None] when it writes none.
 * @property audioFormat The format the composition's audio is normalised to, or null when there is
 * none to mix.
 * @property audioTracks Every track of the negotiated composition, video and audio-only alike, for
 * [BrowserAudioMix] to mix. [clips] carries only the primary track's video.
 * @property leadFrames How many output slots the primary track's leading gap fills, which is the slot its start lands
 * on. Each of these slots is the fill alone, and the first clip opens on the slot after the last of them.
 * @property estimatedFrames Every slot the pipeline encodes, [leadFrames] included.
 * @property fill What the compositor fills the frame with where no clip's pixels land.
 * @property copyVideoPackets How many video packets a copy that ends before its source does writes, the count the plan
 * settled on [dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition.copyPrefix]. Null for every other
 * render.
 * @property hdrTransfer The transfer function the written video carries, or null for SDR or for a
 * render that writes none.
 * @property compositionOverlays The rasters the composition composites over the whole output frame after the fill,
 * bars and gaps included, in chain order.
 * @property compositionPasses The shader passes the composition runs over the whole output frame after the fill, ahead
 * of [compositionOverlays], in chain order.
 */
internal class BrowserRender(
  val writesVideo: Boolean,
  val clips: List<RenderedClip>,
  val duration: Duration,
  val frameRate: Int,
  val width: Int,
  val height: Int,
  val videoEncoding: VideoEncoding?,
  val container: String,
  val audioCodec: AudioCodec,
  val bitrate: Int,
  val leadFrames: Long,
  val estimatedFrames: Long,
  val adjustments: List<Adjustment>,
  val audioFormat: AudioFormat?,
  val audioTracks: List<ResolvedTrack>,
  val fill: Fill,
  val hdrTransfer: HdrTransfer?,
  val compositionOverlays: List<WebGlOverlay>,
  val compositionPasses: List<WebGlPass> = emptyList(),
  val copyVideoPackets: Int? = null,
) {
  /**
   * How long one output slot runs in microseconds at [frameRate]. Every pass that walks the output frame by frame
   * steps on by this, and it is the duration each encoded frame carries.
   */
  val stepUs: Double = MICROS_PER_SECOND / frameRate

  /**
   * Where output [slot] sits on the composition timeline in microseconds at [frameRate], read off the shared grid.
   * This is the timestamp a frame written on that slot carries.
   */
  fun slotUs(slot: Long): Double = outputSlotTime(slot, frameRate).toDouble(DurationUnit.MICROSECONDS)

  /**
   * Whether [clip]'s frames are read as ten-bit planes rather than uploaded as an image, which is every clip of a
   * render that keeps the grade and every tone mapped HDR clip.
   */
  fun readsPlanes(clip: RenderedClip): Boolean = hdrTransfer != null || clip.toneMapFrom != null
}

/**
 * The lowering from a resolved composition onto what one WebGL pass draws per clip.
 *
 * A clip's own effects and the composition's, resolved separately by the negotiator, are one
 * combined texture matrix and one combined color matrix here, because a single pass has no
 * intermediate canvas to apply them on separately. [composition] is the same one negotiation ran
 * against: dropping an unsupported effect's id never changes a clip's trim or its count, so the
 * clip a resolved one was laid from is the one its [ResolvedClip.sourceIndex] names.
 *
 * An [ExportPath.Copy] render never opens an encoder, and `videoCodec` there is the source's
 * own codec rather than the ladder's pick, which [webCodecString] and [muxCodecKey] refuse to
 * name a WebCodecs string for. [BrowserRender.videoEncoding] is left null on that path rather
 * than asked for.
 */
internal fun browserRenderOf(
  plan: ResolvedComposition,
  composition: Composition,
): BrowserRender {
  if (plan.audio == AudioSpec.AudioOnly) return audioOnlyRender(plan)

  val track = composition.tracks.first()
  val outputSize = plan.output.size
  val videoCodec = plan.output.videoCodec
  val encodes = plan.path == ExportPath.Transcode
  val frameRate = plan.output.frameRate

  val primary = plan.tracks.first()
  val leadFrames = outputSlotOf(primary.start, frameRate)
  val slots = primary.clips.map { outputSlotsOf(it.span, frameRate) }
  // The last covered slot belongs to the last clip that covers any, so a trailing clip too short to cover one leaves
  // the hold on the clip before it.
  val holder = slots.indexOfLast { !it.isEmpty() }
  val heldFrames = plan.heldTail.size
  // The composition's overlays run after the fill and are drawn on their own, so the passes folded into each clip stop
  // short of them.
  val compositionChain = plan.effectsUnderFill
  compositionChain.requireDrawable()
  plan.effectsOverFill.requireDrawable()
  val clips =
    primary.clips.mapIndexed { index, resolved ->
      renderedClip(
        resolved = resolved,
        track = track,
        outputSize = outputSize,
        compositionChain = compositionChain,
        slots = slots[index],
        heldFrames = if (index == holder) heldFrames else 0L,
        toneMaps = plan.hdr == ResolvedHdr.ToneMap,
        fill = plan.fill,
      )
    }

  return BrowserRender(
    writesVideo = true,
    clips = clips,
    duration = plan.duration,
    frameRate = frameRate,
    width = outputSize.width,
    height = outputSize.height,
    videoEncoding =
      if (encodes) {
        VideoEncoding(
          encoderCodec = webCodecString(videoCodec, outputSize, plan.hdrTransfer != null),
          muxCodec = muxCodecKey(videoCodec),
        )
      } else {
        null
      },
    container = if (encodes) containerFor(videoCodec, plan.output.audioCodec, plan.hdrTransfer != null) else MP4,
    audioCodec = plan.output.audioCodec,
    // Handed to mediabunny as an Int, since a Kotlin Long is no JS number. No encoder takes a rate near the cap.
    bitrate =
      if (encodes) {
        checkNotNull(plan.output.bitrate) { "The planner settles a bitrate on every transcode." }
          .bitsPerSecond
          .coerceAtMost(Int.MAX_VALUE.toLong())
          .toInt()
      } else {
        0
      },
    leadFrames = leadFrames,
    // Summed from the gap and the clips, not the duration, so what progress measures against is
    // exactly what the pipeline walks.
    estimatedFrames = leadFrames + clips.sumOf { it.frames + it.heldFrames },
    adjustments = plan.adjustments,
    audioFormat = plan.output.audioFormat,
    audioTracks = plan.tracks,
    fill = plan.fill,
    hdrTransfer = plan.hdrTransfer,
    compositionOverlays = plan.effectsOverFill.mapNotNull { it.effect.overlay },
    compositionPasses = plan.effectsOverFill.shaderPasses(),
    copyVideoPackets = plan.copyPrefix?.count,
  )
}

// One resolved clip as the quad, matrices and slots the compositor draws it with. [track] is the edit's own track the
// clip was laid from. The quad is the clip's drawn rect, which every placement centres in the frame, and the texture
// matrix its geometry runs in samples the clip's content over it, or the clip's canvas when an effect after the crop
// reads it. [toneMaps] says the render brings HDR sources down to SDR, and [fill] decides what the canvas bars hold.
private fun renderedClip(
  resolved: ResolvedClip,
  track: Track,
  outputSize: Size,
  compositionChain: List<ResolvedEffect>,
  slots: LongRange,
  heldFrames: Long,
  toneMaps: Boolean,
  fill: Fill,
): RenderedClip {
  resolved.effects.requireDrawable()
  val raw = track.clips[resolved.sourceIndex]
  val drawn = resolved.drawnRect
  // The blurred cover is the frame the chain leaves, scaled by the shared cover scale until it covers the output.
  val leaves = resolved.placement.drawnSize
  val cover = coverScale(leaves, outputSize)
  val cuts = listOf(-1) + resolved.canvasEffectIndices
  val onCanvas = resolved.effects.drop(cuts.last() + 1)
  val chain = onCanvas + compositionChain
  val lastOverlay = resolved.effects.indexOfLast { it.effect.overlay != null }
  val canvases =
    resolved.placement.canvases.mapIndexed { index, canvas ->
      ClipCanvas.of(resolved.effects.subList(cuts[index] + 1, cuts[index + 1] + 1).textureMatrix(), canvas, fill)
    }
  val clipGrade = resolved.effects.colorMatrix()
  val identity = ColorMatrix.Identity.toColumnMajor4x4()
  val passes = resolved.stagePasses(fill)
  val stageFrame = resolved.stageFrameOn(outputSize)
  val passStage =
    if (passes.isEmpty()) {
      null
    } else {
      ClipPassStage(
        matrix = onCanvas.textureMatrix(),
        colorMatrix = clipGrade,
        canvases = canvases.map { it.withClearBars() },
        width = stageFrame.width,
        height = stageFrame.height,
        passes = passes,
      )
    }
  return RenderedClip(
    source = resolved.source,
    trimStart = resolved.start,
    // Open ended only where the pass really does read to the source's end. A looping track
    // cuts its last pass, and that one carries a bound even though the clip it was laid from
    // was never trimmed.
    trimEndUs =
      if (raw.trim?.endExclusive == null && resolved.end >= resolved.info.duration) {
        OPEN_END
      } else {
        resolved.end.microseconds()
      },
    firstSlot = slots.first,
    quadHalfW = drawn.width,
    quadHalfH = drawn.height,
    coverHalfW = leaves.width * cover / outputSize.width,
    coverHalfH = leaves.height * cover / outputSize.height,
    hasBars = resolved.placement.showsBlurredFill(outputSize) || resolved.info.video?.hasAlpha == true,
    matrix = if (passStage == null) chain.textureMatrix() else compositionChain.textureMatrix(),
    canvases = if (passStage == null) canvases else emptyList(),
    overlayMatrix = (resolved.effects.drop(lastOverlay + 1) + compositionChain).textureMatrix(),
    colorMatrix = if (passStage == null) clipGrade else identity,
    compositionColorMatrix = compositionChain.colorMatrix(),
    frames = slots.size,
    heldFrames = heldFrames,
    overlays = resolved.effects.mapNotNull { it.effect.overlay },
    toneMapFrom = if (toneMaps) resolved.info.video?.hdrTransfer else null,
    passStage = passStage,
    passesUnderFill = compositionChain.shaderPasses(),
    speed = resolved.speed,
    spanStart = resolved.span.start,
    audioTrack = resolved.audioTrack,
  )
}

/**
 * The lowering for a composition that writes audio and no video.
 *
 * The mix reads the tracks, the audio format and the duration. Nothing else is asked for: no clip
 * is drawn, no encoder is opened, and the output frame the negotiator resolved describes a track
 * that never gets written.
 */
private fun audioOnlyRender(plan: ResolvedComposition): BrowserRender =
  BrowserRender(
    writesVideo = false,
    clips = emptyList(),
    duration = plan.duration,
    frameRate = 0,
    width = 0,
    height = 0,
    videoEncoding = null,
    container = containerFor(null, plan.output.audioCodec),
    audioCodec = plan.output.audioCodec,
    bitrate = 0,
    leadFrames = 0,
    estimatedFrames = 0,
    adjustments = plan.adjustments,
    audioFormat = plan.output.audioFormat,
    audioTracks = plan.tracks,
    fill = plan.fill,
    hdrTransfer = null,
    compositionOverlays = emptyList(),
  )

private fun List<ResolvedEffect>.textureMatrix(): TextureMatrix = TextureMatrix.of(map { it.effect.pass })

/**
 * The passes this clip runs on a frame of its own ahead of the composite, empty when it needs no such frame.
 *
 * Its shader passes, and [BARS_OVER_BLACK] after them when [fill] is blurred and something would reach a canvas's bars.
 */
internal fun ResolvedClip.stagePasses(fill: Fill): List<WebGlPass> {
  val shaderPasses = effects.shaderPasses()
  if (fill !is Fill.Blurred || placement.canvases.isEmpty()) return shaderPasses
  val grades = !effects.colorMatrix().contentEquals(ColorMatrix.Identity.toColumnMajor4x4())
  return if (shaderPasses.isEmpty() && !grades) shaderPasses else shaderPasses + BARS_OVER_BLACK
}

/**
 * The frame [stagePasses] draw on, which is this clip's quad on an output of [outputSize] and runs past it under
 * a fit that crops.
 */
internal fun ResolvedClip.stageFrameOn(outputSize: Size): Size =
  Size(
    (drawnRect.width * outputSize.width).roundToInt().coerceAtLeast(1),
    (drawnRect.height * outputSize.height).roundToInt().coerceAtLeast(1),
  )

private fun List<ResolvedEffect>.shaderPasses(): List<WebGlPass> =
  map {
    it.effect.pass
  }.filter { it.steps.isNotEmpty() }

// A pass the compositor has no way to draw would otherwise vanish from the frame. Each scope runs its shader passes
// over its picture and lays its overlays after them, so a pass sits in the spatial or composite stage ahead of them.
private fun List<ResolvedEffect>.requireDrawable() {
  var overlaySeen = false
  for (resolved in this) {
    val pass = resolved.effect.pass
    when {
      resolved.effect.overlay != null -> {
        overlaySeen = true
      }
      pass.steps.isNotEmpty() -> {
        if (resolved.stage != EffectStage.Spatial && resolved.stage != EffectStage.Composite) {
          throw BrowserExportFailure(
            "Effect ${resolved.specId} draws through its own shader at the ${resolved.stage} stage, and the browser " +
              "runs a shader pass at the Spatial and Composite stages only.",
          )
        }
        if (overlaySeen) {
          throw BrowserExportFailure(
            "Effect ${resolved.specId} draws through its own shader after an overlay, and the browser runs a shader " +
              "pass beneath the overlays of its scope.",
          )
        }
      }
      pass.textureMatrixAt == null && pass.uniforms.keys.none { it in FOLDED_UNIFORMS } -> {
        throw BrowserExportFailure(
          "Effect ${resolved.specId} resolved to program ${pass.programKey}, which has no fragment source and no " +
            "program the compositor knows, so it would draw nothing.",
        )
      }
    }
  }
}

// Composed through the shared fold, so what a stage's passes come to here is the matrix the planner
// would have made had it folded them itself. A pass this backend did not write is free to spell a
// uniform of that name any way it likes, and one that is not a mat4 is left out rather than failing
// the plan.
private fun List<ResolvedEffect>.colorMatrix(): FloatArray =
  fold(ColorMatrix.Identity) { combined, resolved ->
    val columns = resolved.effect.pass.uniforms[COLOR_MATRIX_UNIFORM] ?: return@fold combined
    colorMatrixOfColumnMajor4x4OrNull(columns)?.let { combined.then(it) } ?: combined
  }.toColumnMajor4x4()

// The pipeline reads this as "to the end of the track", never as a timestamp.
private val OPEN_END = Double.POSITIVE_INFINITY

private val LongRange.size: Long get() = if (isEmpty()) 0 else last - first + 1

/**
 * WebCodecs timestamps are microseconds, as a double.
 */
internal fun Duration.microseconds(): Double = toDouble(DurationUnit.MICROSECONDS)

// The uniform names the built-in browser resolver writes. They are private there, so the names are
// spelled out here and any resolver emitting the same names composes the same way.
internal const val TEX_MATRIX_UNIFORM = "uTexMatrix"
private const val COLOR_MATRIX_UNIFORM = "uColorMatrix"
private val FOLDED_UNIFORMS = setOf(TEX_MATRIX_UNIFORM, COLOR_MATRIX_UNIFORM)
