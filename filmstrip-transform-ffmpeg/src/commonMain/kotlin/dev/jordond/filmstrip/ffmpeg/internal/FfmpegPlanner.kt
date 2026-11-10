package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.ffmpeg.internal.lowering.CODEC_LADDER
import dev.jordond.filmstrip.ffmpeg.internal.lowering.GraphLowering
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.LIVE_PROTOCOL
import dev.jordond.filmstrip.ffmpeg.internal.lowering.SdColor
import dev.jordond.filmstrip.ffmpeg.internal.lowering.canToneMap
import dev.jordond.filmstrip.ffmpeg.internal.lowering.filterNames
import dev.jordond.filmstrip.ffmpeg.internal.lowering.hdrPixelFormatFor
import dev.jordond.filmstrip.ffmpeg.internal.lowering.liveFilterNames
import dev.jordond.filmstrip.ffmpeg.internal.lowering.noLiveProtocol
import dev.jordond.filmstrip.ffmpeg.internal.lowering.tooManyLiveInputs
import dev.jordond.filmstrip.ffmpeg.internal.picture.decodingOverlayPictures
import dev.jordond.filmstrip.ffmpeg.internal.process.BytesRoot
import dev.jordond.filmstrip.ffmpeg.internal.process.DEFAULT_BYTES_ROOT
import dev.jordond.filmstrip.ffmpeg.internal.process.READS_FILES
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.ExportGrade
import dev.jordond.filmstrip.transform.internal.ExportPlanner
import dev.jordond.filmstrip.transform.internal.LoweredExport
import dev.jordond.filmstrip.transform.internal.NegotiatedExport
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketRead
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketWindow
import dev.jordond.filmstrip.transform.internal.copy.Mp4Copy
import dev.jordond.filmstrip.transform.internal.copy.withFallbackCopyPackets
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.negotiation.effectsThatRequireSdr
import dev.jordond.filmstrip.transform.internal.refusal
import dev.jordond.filmstrip.transform.internal.refusedLowering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.anyClipExposesFill
import dev.jordond.filmstrip.transform.internal.resolve.stillUnsupportedMessage

/**
 * Turns a composition into a filter graph and a verdict.
 *
 * Negotiation itself is [ExportPlanner], the same one media3, AVFoundation and the browser drive:
 * geometry, codec resolution, trim windows and adjustments are answered identically everywhere,
 * with this backend's ladder and its own answer to what it can do injected in.
 *
 * What stays here is the refusal the negotiator cannot make, since ffmpeg opens files and a source
 * that names none is unreadable before anything is planned, and the lowering from a
 * [ResolvedComposition] onto the graph [GraphLowering] writes.
 */
internal class FfmpegPlanner(
  private val toolchain: Toolchain,
  resolvers: List<EffectResolver>,
  private val bytesRoot: BytesRoot = DEFAULT_BYTES_ROOT,
) {
  // Computed once and consumed by the negotiator's canToneMap and the capability it reports.
  private val canToneMap = canToneMap(toolchain)

  private val planner =
    ExportPlanner(
      resolvers = decodingOverlayPictures(resolvers, toolchain),
      renderCapabilities = { outputSize, encodesHdr ->
        renderCapabilities(canToneMap, outputSize, encodesHdr)
      },
      parityOf = FfmpegParity::of,
      unclaimedMessage = { specId ->
        "No resolver claimed $specId on the ffmpeg backend. Register the built-in catalogue with " +
          "builtInEffects(), or add a resolver that recognises RenderApi.FilterGraph."
      },
      ladder = CODEC_LADDER,
      noteOf = FfmpegParity::noteFor,
      supportsCopy = true,
      // Every export here writes mp4, so a copy is allowed for exactly what mp4 carries.
      muxerAccepts = { info -> Mp4Copy.accepts(info) },
      // The shared tone curve runs on lut1d, geq and colorchannelmixer, which every supported
      // build carries.
      canToneMap = canToneMap,
    )

  /**
   * The grade an export of [composition] at [ask] resolves to.
   *
   * A source this backend cannot read is refused the way [lower] refuses it.
   */
  fun exportGradeOf(
    composition: Composition,
    ask: Ask,
  ): ExportGrade {
    try {
      sourceRefusal(composition)?.let { return ExportGrade.Refused(it.export.refusal()) }
    } catch (failure: MediaBytesFileFailure) {
      return ExportGrade.Refused(FilmstripError.SourceUnreadable(failure.source, failure.message))
    }
    return planner.exportGradeOf(composition, ask)
  }

  /**
   * The window an export of [composition] at [ask] copies whose packets the engine reads into [Ask.copyPackets], or
   * null when it does not copy, the copy runs to its source's end, or a source is one [lower] refuses.
   */
  fun copyPacketWindowOf(
    composition: Composition,
    ask: Ask,
  ): CopyPacketWindow? =
    try {
      if (sourceRefusal(composition) == null) planner.copyPacketWindowOf(composition, ask) else null
    } catch (_: MediaBytesFileFailure) {
      // lower refuses the same source with the reason.
      null
    }

  /**
   * [export] with the plan it falls back on settled over the packets [read] lists, through
   * [ExportPlanner.withFallbackCopyPackets].
   */
  suspend fun withFallbackCopyPackets(
    composition: Composition,
    ask: Ask,
    export: NegotiatedExport,
    read: suspend (CopyPacketWindow) -> CopyPacketRead?,
  ): NegotiatedExport = planner.withFallbackCopyPackets(composition, ask, export, read)

  /**
   * Negotiates [composition] against [ask] and lowers what it settles on onto a filter graph.
   *
   * The engine reads the openings in [ask] with [FfmpegRuntime.syncSampleAtOrBefore], and the negotiator decides from
   * them whether the copy is reachable and where the window then opens. Each source in [sdColors] sits on SD primaries,
   * which takes this build's `colorspace` filter, since an export or a preview of it can draw the clip through one.
   * The packets in [Ask.copyPackets] give the count a copy ending before its source does stops its video on, which the
   * planner settles on [ResolvedComposition.copyPrefix].
   */
  fun lower(
    composition: Composition,
    ask: Ask,
    dropped: Set<String> = emptySet(),
    sdColors: Map<MediaSource, SdColor> = emptyMap(),
    bt709Sources: Set<MediaSource> = emptySet(),
    tailFormatSources: Set<MediaSource> = emptySet(),
  ): LoweredExport<Invocation> =
    try {
      lowerReadable(composition, ask, dropped, sdColors, bt709Sources, tailFormatSources)
    } catch (failure: MediaBytesFileFailure) {
      refusedLowering(FilmstripError.SourceUnreadable(failure.source, failure.message))
    }

  private fun lowerReadable(
    composition: Composition,
    ask: Ask,
    dropped: Set<String>,
    sdColors: Map<MediaSource, SdColor>,
    bt709Sources: Set<MediaSource>,
    tailFormatSources: Set<MediaSource>,
  ): LoweredExport<Invocation> {
    sourceRefusal(composition)?.let { return it }

    val export = planner.negotiate(composition, ask, dropped)
    val negotiated = export.composition ?: return LoweredExport(export, null)
    val fill = negotiated.fill
    if (fill is Fill.Blurred && negotiated.anyClipExposesFill) {
      BLUR_FILTERS.firstOrNull { !toolchain.hasFilter(it) }?.let { return unsupportedBlur(it) }
      // colorchannelmixer is only reached when dim actually darkens the background, so a build
      // missing it is refused only for the fills that would need it.
      if (fill.backgroundGain != 1f &&
        !toolchain.hasFilter("colorchannelmixer")
      ) {
        return unsupportedBlur("colorchannelmixer")
      }
    }
    val effects =
      negotiated.tracks.flatMap { track -> track.clips.flatMap { it.effects } } + negotiated.compositionEffects
    // Geometry moves a clip's pixels and leaves their colour, so only what the planner left in any other stage counts.
    val drawsOnColor = effects.any { it.stage != EffectStage.Geometry }
    val demands =
      effects.map { it.effect.fragment.filterNames() to it.specId } +
        sdColors.values.map { it.filterNames() to SD_CLIP }
    demands.forEach { (filters, what) ->
      filters.firstOrNull { !toolchain.hasFilter(it) }?.let { filter ->
        return refusedLowering(FilmstripError.InvalidComposition(missingFilter(filter, what)))
      }
    }
    val lowering = { readsLivePlanes: Boolean, liveForm: Invocation?, liveFallback: String? ->
      GraphLowering(
        negotiated = negotiated,
        hdrPixelFormat = hdrPixelFormatFor(negotiated.encoderName, negotiated.hdrTransfer),
        bytesRoot = bytesRoot,
        sdColors = sdColors,
        keepsSdColor = !drawsOnColor && composition.effectsThatRequireSdr().isEmpty(),
        tailFormatSources = tailFormatSources,
        readsLivePlanes = readsLivePlanes,
        liveForm = liveForm,
        liveFallback = liveFallback,
        copyVideoPackets = negotiated.copyPrefix?.count,
      ).build()
    }
    val fragments = effects.map { it.effect.fragment }
    if (fragments.none { it.live != null }) return LoweredExport(export, lowering(false, null, null))
    // A live plan's limits are named rather than refused, since the graph it falls back on writes the same samples.
    val sources = negotiated.tracks.flatMap { track -> track.clips.map { it.source } }
    val refusal =
      toolchain.liveRefusal(fragments) ?: untaggedFrames(fragments, sources.all { it in bt709Sources })
    val live = if (refusal == null) lowering(true, null, null) else null
    val overCap = live?.let { form -> tooManyLiveInputs(form.inputs.count { it.source is InputSource.Live }) }
    val fallback = refusal ?: overCap
    return LoweredExport(export, lowering(false, live.takeIf { fallback == null }, fallback))
  }

  private fun unreadable(source: MediaSource): LoweredExport<Nothing> =
    refusedLowering(FilmstripError.SourceUnreadable(source.describe(), READS_FILES))

  private fun stillUnsupported(): LoweredExport<Nothing> =
    refusedLowering(FilmstripError.SourceNotExportable(stillUnsupportedMessage("ffmpeg")))

  private fun sourceRefusal(composition: Composition): LoweredExport<Nothing>? {
    composition.tracks.flatMap { it.clips }.forEach { clip ->
      // A still is refused by kind, not by asking readablePath and guessing why it came back
      // null: it names a real file, and the actual reason is that this backend has no stills
      // support, not that the source is unreadable.
      if (clip.source is MediaSource.Image) return stillUnsupported()
      readablePath(clip.source, bytesRoot) ?: return unreadable(clip.source)
    }
    return null
  }

  private fun unsupportedBlur(filter: String): LoweredExport<Nothing> =
    refusedLowering(FilmstripError.InvalidComposition(missingBlurFilter(filter)))
}

// A limit of ffmpeg's negotiation, which offers no way to copy one pad's matrix onto another.
private fun untaggedFrames(
  fragments: List<FilterFragment>,
  everySourceBt709: Boolean,
): String? =
  if (!everySourceBt709 && fragments.any { it.live?.needsBt709Frames == true }) {
    "A clip here is not tagged BT.709, and a look's plane with chroma of its own would move on the way onto it, " +
      "so it draws its map with its own graph."
  } else {
    null
  }

// Why this build cannot read a live plane, or null when it can.
private fun Toolchain.liveRefusal(fragments: List<FilterFragment>): String? {
  if (!hasInputProtocol(LIVE_PROTOCOL)) return noLiveProtocol()
  return fragments
    .flatMap { it.liveFilterNames() }
    .firstOrNull { !hasFilter(it) }
    ?.let { "This ffmpeg build has no $it filter, so a look draws its map with its own graph." }
}

// The filters SpatialBlurGraph draws the blur with.
private val BLUR_FILTERS = listOf("convolution", "pad", "fillborders", "transpose")

private const val SD_CLIP = "a clip on SMPTE C or EBU primaries"

internal fun missingBlurFilter(filter: String): String =
  "This ffmpeg build has no $filter filter, so it cannot lower Fill.Blurred. Use Fill.Solid instead."

internal fun missingFilter(
  filter: String,
  what: String,
): String = "This ffmpeg build has no $filter filter, so it cannot lower $what."

private fun renderCapabilities(
  canToneMap: Boolean,
  outputSize: Size,
  encodesHdr: Boolean,
): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.FilterGraph,
    supportsFragmentShader = false,
    supportsComputeShader = false,
    // True only once the grade actually reaches the encoder. A tone map still runs through here on
    // its way down to SDR, so this is not the tone-map check. ExportPlanner has already answered
    // that one before it hands encodesHdr in.
    supportsHdr = encodesHdr,
    colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt601, ColorSpace.Bt2020),
    maxTextureSize = maxOf(outputSize.width, outputSize.height, MAX_TEXTURE_FLOOR),
    features =
      buildSet {
        add(RenderFeature.MultipassRender)
        add(RenderFeature.TextRendering)
        if (canToneMap) add(RenderFeature.HdrToneMapping)
      },
  )

private const val MAX_TEXTURE_FLOOR = 16_384

// What the mp4 muxer this backend always writes will take as-is, without an encoder.
