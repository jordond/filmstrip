package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.ExportGrade
import dev.jordond.filmstrip.transform.internal.ExportPlanner
import dev.jordond.filmstrip.transform.internal.LoweredExport
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketWindow
import dev.jordond.filmstrip.transform.internal.copy.Mp4Copy
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.refusal
import dev.jordond.filmstrip.transform.internal.refusedLowering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.stillUnsupportedMessage
import dev.jordond.filmstrip.webcodecs.internal.render.MAX_CANVASES

/**
 * Turns a composition into a browser verdict.
 *
 * Negotiation itself is [ExportPlanner], the same one media3 and AVFoundation drive: geometry,
 * codec resolution, HDR, trim windows and adjustments are answered identically everywhere, with
 * this backend's own ladder and its own answer to what it can and cannot do injected in.
 *
 * What stays here is what the negotiator does not, and cannot, own: refusals for gaps this backend
 * has that are not policy (no still to decode a video track from, no baking a source's container
 * rotation), and the lowering from a [ResolvedComposition]'s platform effect chain onto
 * one quad, one texture matrix and one color matrix per clip, which is what a single WebGL pass
 * draws.
 */
internal class BrowserPlanner(
  resolvers: List<EffectResolver>,
) {
  private val planner =
    ExportPlanner(
      resolvers = resolvers,
      renderCapabilities = { _, encodesHdr -> renderCapabilities(encodesHdr) },
      parityOf = ::browserParityOf,
      unclaimedMessage = { specId ->
        "No resolver claimed $specId on the browser backend. Register the built-in catalogue with " +
          "builtInEffects(), or add a resolver that recognises RenderApi.WebGl."
      },
      ladder = BROWSER_LADDER,
      supportsCopy = true,
      // Every HDR clip runs the shared tone curve on its own planes before the composite, so a
      // tone map is drawn whatever transfer each clip carries.
      canToneMap = true,
      // A copy is only ever muxed into mp4. mediabunny's own writer carries two codecs on top of
      // what mp4 otherwise takes, and this backend adds them to the shared baseline here.
      muxerAccepts = { info -> Mp4Copy.accepts(info, alsoVideo = MEDIABUNNY_VIDEO, alsoAudio = MEDIABUNNY_AUDIO) },
      // There is no browser encoder for HEVC Main10, and VP9 Profile 2 is the one HDR profile
      // Chromium was measured to encode.
      hdrLadder = listOf(VideoCodec.Vp9),
    )

  /**
   * The grade an export of [composition] at [ask] resolves to, refused the way [lower] refuses that export.
   */
  internal fun exportGradeOf(
    composition: EditComposition,
    ask: Ask,
    opaque: Set<MediaSource>,
  ): ExportGrade {
    unconditionalRefusal(composition, ask.infos)?.let { return ExportGrade.Refused(it.export.refusal()) }
    return when (val grade = planner.exportGradeOf(composition, ask)) {
      is ExportGrade.Refused -> opaqueRefusal(opaque)?.let { ExportGrade.Refused(it.export.refusal()) } ?: grade
      is ExportGrade.Settled -> grade
    }
  }

  /**
   * The window an export of [composition] at [ask] copies whose packets the engine reads into [Ask.copyPackets], or
   * null when it does not copy, the copy runs to its source's end, or [lower] refuses the composition before
   * negotiating it.
   */
  internal fun copyPacketWindowOf(
    composition: EditComposition,
    ask: Ask,
  ): CopyPacketWindow? =
    if (unconditionalRefusal(composition, ask.infos) == null) planner.copyPacketWindowOf(composition, ask) else null

  /**
   * Negotiates [composition] against [ask] and lowers what it settles on onto a render.
   *
   * The negotiator decides from the openings in [ask] whether a copy can reach the cut and which window it lays, so
   * nothing downstream reads a source's sync samples again.
   */
  internal fun lower(
    composition: EditComposition,
    ask: Ask,
    dropped: Set<String> = emptySet(),
    opaque: Set<MediaSource> = emptySet(),
  ): LoweredExport<BrowserRender> {
    unconditionalRefusal(composition, ask.infos)?.let { return it }

    val export = planner.negotiate(composition, ask, dropped)
    val negotiated =
      export.composition ?: return opaqueRefusal(opaque) ?: LoweredExport(export, null)
    val writesVideo = negotiated.audio != AudioSpec.AudioOnly
    if (writesVideo) canvasRefusal(negotiated)?.let { return it }
    if (writesVideo) clipFrameError(negotiated, browserMaxTextureSide())?.let { return refusedLowering(it) }
    if (writesVideo && negotiated.path == ExportPath.Transcode) {
      transcodeOnlyRefusal(composition, ask.infos)?.let { return it }
      // A tone map reads the planes a kept grade does, so a source that hands out opaque frames
      // cannot be brought down to SDR either.
      opaqueRefusal(drawnSources(composition) intersect opaque)?.let { return it }
    }
    return LoweredExport(export, browserRenderOf(negotiated, composition))
  }

  /**
   * Refusals that hold whatever the negotiated path turns out to be, checked before the shared
   * negotiator ever sees the composition: a still anywhere on the timeline, and a clip past the
   * first with no video track. The track shapes no backend lays are the negotiator's to refuse.
   */
  private fun unconditionalRefusal(
    composition: EditComposition,
    infos: Map<MediaSource, MediaInfo>,
  ): LoweredExport<Nothing>? {
    // This backend draws its output by decoding a video track, and a still carries none, so every
    // clip on every track is refused by kind rather than only the ones that reach the negotiator's
    // own checks below. A still on an audio track would otherwise pass those unnoticed and only
    // fail later, as a source the export pipeline cannot open.
    if (composition.tracks.flatMap { it.clips }.any { it.source is MediaSource.Image }) {
      return refusedLowering(FilmstripError.SourceNotExportable(stillUnsupportedMessage("browser")))
    }

    val primary = composition.tracks.firstOrNull() ?: return null
    primary.clips.forEachIndexed { index, clip ->
      val info = infos[clip.source] ?: return@forEachIndexed
      // The negotiator already refuses a missing video track on the first clip by name. Every
      // other clip in this single video track needs the same check.
      if (index > 0 && info.video == null) {
        return refusedLowering(FilmstripError.InvalidComposition("Clip ${clip.source.describe()} has no video track."))
      }
    }
    return null
  }

  /**
   * Refusals that only hold once the negotiated path decodes and re-encodes: a single-pass
   * renderer that cannot bake a source's container rotation into pixels. A stream copy carries it
   * across untouched, and an audio-only export writes no pixels to bake it into.
   */
  private fun transcodeOnlyRefusal(
    composition: EditComposition,
    infos: Map<MediaSource, MediaInfo>,
  ): LoweredExport<Nothing>? {
    val primary = composition.tracks.firstOrNull() ?: return null
    primary.clips.forEach { clip ->
      val info = infos[clip.source] ?: return@forEach
      if ((info.video?.rotationDegrees ?: 0) != 0) {
        return refusedLowering(
          FilmstripError.InvalidComposition(
            "The source ${clip.source.describe()} carries container rotation, and the browser " +
              "pipeline does not bake it yet.",
          ),
        )
      }
    }
    return null
  }

  // The sources the pipeline decodes frames from, which are the clips of the primary track.
  private fun drawnSources(composition: EditComposition): Set<MediaSource> =
    composition.tracks
      .firstOrNull()
      ?.clips
      ?.mapTo(mutableSetOf()) { it.source }
      .orEmpty()

  /**
   * Why a grade this backend was told it could encode still cannot be kept, or tone mapped.
   *
   * The device answered that it has no HDR encoder for this composition, but the reason is the
   * source rather than the device, and the shared negotiator has no way to say so. Naming the
   * source is what separates "this browser cannot encode HDR" from "this browser cannot read this
   * clip", which are different things to do something about.
   */
  private fun opaqueRefusal(opaque: Set<MediaSource>): LoweredExport<Nothing>? {
    val source = opaque.firstOrNull() ?: return null

    return refusedLowering(
      FilmstripError.SourceNotExportable(
        "The source ${source.describe()} decodes to an opaque frame in this browser, so its " +
          "grade cannot be read to keep it or to tone map it. Leave the clip untouched so its " +
          "packets can be copied across.",
      ),
    )
  }
}

/**
 * The order this backend tries video codecs in, most preferred first. VP9 is a browser encode
 * safety net. H264 encode is universal and Hevc encode is concentrated in Safari and non-Windows
 * Chrome, so it goes last.
 */
internal val BROWSER_LADDER: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Vp9, VideoCodec.Hevc)

/**
 * The ids this backend's built-in resolvers realise exactly. Shared with [ExportPlanner], so
 * `PlannedEffect.parity` and the engine-level [dev.jordond.filmstrip.export.ExportEngine.parityOf]
 * answer the same question the same way.
 */
internal fun browserParityOf(specId: String): EffectParity? =
  when (specId) {
    EffectIds.CROP,
    EffectIds.CROP_RECT,
    EffectIds.FLIP,
    EffectIds.ROTATE,
    EffectIds.SCALE,
    EffectIds.KEN_BURNS,
    EffectIds.BRIGHTNESS,
    EffectIds.RGB_ADJUSTMENT,
    EffectIds.CONTRAST,
    EffectIds.SATURATION,
    EffectIds.HUE_ROTATE,
    EffectIds.SEPIA,
    EffectIds.INVERT,
    EffectIds.COLOR_MATRIX,
    EffectIds.IMAGE_OVERLAY,
    EffectIds.TEXT_OVERLAY,
    -> EffectParity.Exact
    else -> null
  }

/**
 * What the browser pipeline renders on this page, whatever size the composition is written at.
 *
 * The longest texture side is the page's own `MAX_TEXTURE_SIZE` from [browserMaxTextureSide], which every overlay
 * raster uploads under. HDR is advertised when [encodesHdr] says the export keeps it.
 */
internal fun renderCapabilities(encodesHdr: Boolean): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.WebGl,
    supportsFragmentShader = true,
    supportsComputeShader = false,
    supportsHdr = encodesHdr,
    colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt601, ColorSpace.Bt2020),
    maxTextureSize = browserMaxTextureSide(),
    // Decoded VideoFrames are uploaded straight into the texture, which is what this advertises. Text is laid out and
    // drawn on a 2D canvas at resolve, and an HDR clip runs the shared tone curve before the composite.
    features = setOf(RenderFeature.ExternalTexture, RenderFeature.TextRendering, RenderFeature.HdrToneMapping),
  )

// What mediabunny's mp4 muxer, which every copy is pinned to, will take as-is, without an encoder.
private val MEDIABUNNY_VIDEO = setOf(CodecKind.Vp8)
private val MEDIABUNNY_AUDIO = setOf(CodecKind.Vorbis)

// The composite pass builds every canvas a clip reads in one shader, which holds at most MAX_CANVASES of them. That is
// this backend's own limit, so a clip that builds more is refused by name.
private fun canvasRefusal(negotiated: ResolvedComposition): LoweredExport<Nothing>? {
  val most = negotiated.tracks.flatMap { it.clips }.maxOfOrNull { it.placement.canvases.size } ?: 0
  if (most <= MAX_CANVASES) return null
  return refusedLowering(
    FilmstripError.InvalidComposition(
      "A clip builds $most canvases, and the browser draws at most $MAX_CANVASES. Each letterboxing or stretching " +
        "crop with an effect after it that reads its bars builds one.",
    ),
  )
}

/**
 * The refusal for a clip whose own frame runs past [maxSide], the longest texture side this page gives out, or null
 * when every clip's fits. A clip with a shader pass draws on a frame of its whole quad, and a fit that crops makes that
 * quad bigger than the output. That is the page's own limit, so it is refused by name at plan time.
 */
internal fun clipFrameError(
  negotiated: ResolvedComposition,
  maxSide: Int,
): FilmstripError? {
  val frame =
    negotiated.tracks
      .flatMap { it.clips }
      .filter { it.stagePasses(negotiated.fill).isNotEmpty() }
      .map { it.stageFrameOn(negotiated.output.size) }
      .firstOrNull { maxOf(it.width, it.height) > maxSide }
      ?: return null
  return FilmstripError.InvalidComposition(
    "A clip's own frame is ${frame.width} by ${frame.height} pixels, past the $maxSide this page's textures reach on " +
      "a side. Its shader passes draw on that whole frame, which a fit that crops makes bigger than the output.",
  )
}
