package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.ExportPlanner
import dev.jordond.filmstrip.transform.internal.Mp4Copy
import dev.jordond.filmstrip.transform.internal.NegotiatedComposition
import dev.jordond.filmstrip.transform.internal.backgroundGain
import dev.jordond.filmstrip.transform.internal.showsFill
import dev.jordond.filmstrip.transform.internal.sigmaFor
import kotlin.math.roundToInt

/**
 * What a plan resolved to: the verdict a caller sees, and the graph that runs when it is capable.
 */
internal class Lowering(
  val verdict: Verdict,
  val invocation: Invocation?,
)

/**
 * Turns a composition into a filter graph and a verdict.
 *
 * Negotiation itself is [ExportPlanner], the same one media3, AVFoundation and the browser drive:
 * geometry, codec resolution, trim windows and adjustments are answered identically everywhere,
 * with this backend's ladder and its own answer to what it can do injected in.
 *
 * What stays here is the refusal the negotiator cannot make, since ffmpeg opens files and a source
 * that names none is unreadable before anything is planned, and the lowering from a
 * [NegotiatedComposition] onto the graph [GraphLowering] writes.
 */
internal class FfmpegPlanner(
  private val toolchain: Toolchain,
  resolvers: List<EffectResolver>,
) {
  // Computed once and consumed everywhere this backend needs it: the negotiator's canToneMap, the
  // capability it reports, and the graph GraphLowering writes all read this one answer.
  private val toneMapRoute = toneMapRoute(toolchain)

  private val planner =
    ExportPlanner(
      resolvers = resolvers,
      renderCapabilities = { outputSize, encodesHdr ->
        renderCapabilities(toolchain, toneMapRoute, outputSize, encodesHdr)
      },
      parityOf = FfmpegParity::of,
      unclaimedMessage = { specId ->
        "No resolver claimed $specId on the ffmpeg backend. Register the built-in catalogue with " +
          "builtInEffects(), or add a resolver that recognises RenderApi.FilterGraph."
      },
      ladder = CODEC_LADDER,
      noteOf = FfmpegParity::noteFor,
      // ffmpeg has no single-pass form that stream-copies around a trim, so a trim always decodes
      // and re-encodes rather than snapping to a sync sample.
      supportsFastTrim = false,
      supportsPassthrough = true,
      // Every export here writes mp4, so a copy is allowed for exactly what mp4 carries.
      canCopy = { info -> Mp4Copy.accepts(info) },
      // A build needs zscale (--enable-libzimg) or libplacebo to bring a grade down to SDR.
      // Neither ships in stock Homebrew ffmpeg, so an HDR source is refused there until one of
      // them is installed.
      canToneMap = toneMapRoute != null,
      // concat refuses inputs that differ in resolution, so every clip is pinned to the output
      // frame before the join and there is no composited frame left to run geometry on.
      compositionGeometryPerClip = true,
    )

  fun lower(
    composition: EditComposition,
    spec: ExportSpec,
    device: DeviceCapabilities,
    infos: Map<MediaSource, MediaInfo>,
    dropped: Set<String> = emptySet(),
  ): Lowering {
    composition.tracks.flatMap { it.clips }.forEach { clip ->
      readablePath(clip.source) ?: return unreadable(clip.source)
    }

    val export = planner.negotiate(composition, spec, device, infos, dropped)
    val negotiated = export.composition ?: return Lowering(export.verdict, null)
    val fill = negotiated.fill
    if (fill is Fill.Blurred && negotiated.showsFill) {
      if (!toolchain.hasFilter("gblur")) return unsupportedBlur("gblur")
      // colorchannelmixer is only reached when dim actually darkens the background, so a build
      // missing it is refused only for the fills that would need it.
      if (fill.backgroundGain != 1f &&
        !toolchain.hasFilter("colorchannelmixer")
      ) {
        return unsupportedBlur("colorchannelmixer")
      }
    }
    return Lowering(export.verdict, GraphLowering(negotiated, toneMapRoute).build())
  }

  private fun unreadable(source: MediaSource): Lowering =
    Lowering(
      Verdict.Incapable(listOf(ExportError.SourceUnreadable(source.describe(), READS_FILES)), null),
      null,
    )

  private fun unsupportedBlur(filter: String): Lowering =
    Lowering(Verdict.Incapable(listOf(ExportError.InvalidComposition(missingBlurFilter(filter))), null), null)
}

/**
 * The file a source names, or null when this backend cannot read it.
 *
 * ffmpeg reads files, so a path is a path and a `file://` URI is the same thing spelled with a
 * scheme. Nothing else has a desktop meaning, and in-memory bytes have to be written down first.
 */
internal const val READS_FILES: String =
  "This backend reads files. Use a path or a file:// URI; in-memory bytes have to be written down " +
    "before anything here can open them."

internal fun missingBlurFilter(filter: String): String =
  "This ffmpeg build has no $filter filter, so it cannot lower Fill.Blurred. Use Fill.Solid instead."

internal fun readablePath(source: MediaSource): String? =
  when (source) {
    is MediaSource.Path -> source.path
    is MediaSource.Uri -> source.uri.removePrefix("file://").takeIf { !source.uri.contains("://") || it != source.uri }
    is MediaSource.Bytes -> null
  }

/**
 * Which filter chain this build can bring an HDR grade down to SDR with.
 *
 * [Zscale] undoes the transfer into linear light, runs the curve, then writes BT.709 back out. It
 * needs `zscale`, which wants ffmpeg built with `--enable-libzimg`. [Libplacebo] does the same job
 * in one node and needs `libplacebo`, which reads PQ and HLG natively and ships in more static and
 * distro builds than zimg does.
 */
internal enum class ToneMapRoute {
  Zscale,
  Libplacebo,
}

/**
 * The tone-map route this toolchain can take, or null when it has neither filter.
 *
 * Prefers [ToneMapRoute.Zscale] when a build carries both, since that is the chain this backend has
 * tested and pinned.
 */
internal fun toneMapRoute(toolchain: Toolchain): ToneMapRoute? =
  when {
    toolchain.hasFilter("zscale") -> ToneMapRoute.Zscale
    toolchain.hasFilter("libplacebo") -> ToneMapRoute.Libplacebo
    else -> null
  }

/**
 * The grade stage, run on a clip that carries HDR before anything else touches it.
 *
 * On [ToneMapRoute.Zscale], linear light is where a tone curve belongs, so the transfer is undone,
 * the curve applied and the result written back out as BT.709. On [ToneMapRoute.Libplacebo], one
 * node does the whole job: it reads the source transfer natively and delivers BT.709 output
 * directly. Either way every resolver downstream is handed BT.709 attributes.
 */
internal fun toneMapNodes(route: ToneMapRoute): List<FilterNode> =
  when (route) {
    ToneMapRoute.Zscale -> {
      listOf(
        FilterNode("zscale", "transfer" to "linear", "npl" to "100"),
        FilterNode("tonemap", "tonemap" to "hable", "desat" to "0"),
        FilterNode("zscale", "primaries" to "bt709", "transfer" to "bt709", "matrix" to "bt709"),
      )
    }
    ToneMapRoute.Libplacebo -> {
      listOf(
        FilterNode(
          "libplacebo",
          "tonemapping" to "bt.2390",
          "colorspace" to "bt709",
          "color_primaries" to "bt709",
          "color_trc" to "bt709",
          "range" to "tv",
        ),
      )
    }
  }

/**
 * The size stage, pinned to the resolved output frame.
 *
 * Not an effect and not in `ExportPlan.effectOrder`: it is what every clip carries so `concat` sees
 * uniform inputs, and it is why the scale effect emits nothing of its own.
 *
 * [padColor] is the fill's own colour, or transparent while that colour is waiting for composition
 * effects to run before it is painted in. The caller decides which.
 */
internal fun tailNodes(
  outputSize: Size,
  fit: Fit,
  padColor: String,
  frameRate: Int,
  pixelFormat: String,
): List<FilterNode> =
  buildList {
    val width = outputSize.width.toString()
    val height = outputSize.height.toString()
    when (fit) {
      Fit.Contain -> {
        add(FilterNode("scale", "w" to width, "h" to height, "force_original_aspect_ratio" to "decrease"))
        add(
          FilterNode(
            "pad",
            "w" to width,
            "h" to height,
            "x" to "(ow-iw)/2",
            "y" to "(oh-ih)/2",
            "color" to padColor,
          ),
        )
      }
      Fit.Crop -> {
        add(FilterNode("scale", "w" to width, "h" to height, "force_original_aspect_ratio" to "increase"))
        add(FilterNode("crop", "w" to width, "h" to height))
      }
      Fit.Stretch -> {
        add(FilterNode("scale", "w" to width, "h" to height))
      }
    }
    addAll(trailingNodes(frameRate, pixelFormat))
  }

/**
 * The sharp half of a blurred letterbox: the frame scaled down to fit inside the output, with no
 * crop and no pad of its own.
 *
 * Centring it onto the output is left to the overlay that reads this alongside
 * [coverBlurNodes]'s background.
 */
internal fun containNodes(outputSize: Size): List<FilterNode> =
  listOf(
    FilterNode(
      "scale",
      "w" to outputSize.width.toString(),
      "h" to outputSize.height.toString(),
      "force_original_aspect_ratio" to "decrease",
    ),
  )

/**
 * The background half of a blurred letterbox.
 *
 * The frame is scaled up until it covers the output, centre-cropped to that size, then blurred at
 * [fill]'s radius. A non-zero [Fill.Blurred.dim] darkens the result afterward, as a gain on the
 * colour channels rather than an offset, so it never shifts hue.
 */
internal fun coverBlurNodes(
  outputSize: Size,
  fill: Fill.Blurred,
): List<FilterNode> =
  buildList {
    val width = outputSize.width.toString()
    val height = outputSize.height.toString()
    add(FilterNode("scale", "w" to width, "h" to height, "force_original_aspect_ratio" to "increase"))
    add(FilterNode("crop", "w" to width, "h" to height))
    add(FilterNode("gblur", "sigma" to blurSigma(fill, outputSize).toString()))
    val gain = fill.backgroundGain
    if (gain != 1f) {
      add(FilterNode("colorchannelmixer", "rr" to gain.toString(), "gg" to gain.toString(), "bb" to gain.toString()))
    }
  }

// 1024 is gblur's own ceiling on sigma, not a limit filmstrip picked.
private fun blurSigma(
  fill: Fill.Blurred,
  outputSize: Size,
): Int = fill.sigmaFor(outputSize).roundToInt().coerceIn(1, 1024)

/**
 * Centres the sharp foreground over the blurred background.
 *
 * ffmpeg's `overlay` reads its first input as the base and its second as what is drawn on top, so
 * the blurred pad has to be listed before the sharp one wherever this is chained.
 */
internal fun overlayNodes(): List<FilterNode> = listOf(overlayNode("x" to "(W-w)/2", "y" to "(H-h)/2"))

/**
 * An `overlay` that keeps whatever bit depth its inputs arrived at.
 *
 * The filter's own `format` defaults to `yuv420`, which is 8-bit, so a 10-bit frame reaching any
 * overlay is taken down and back up again on its way to the encoder, and nothing about the exported
 * file says it happened. Every overlay this backend writes is built here so the option cannot be
 * left at its default in one place and set in another.
 */
internal fun overlayNode(vararg arguments: Pair<String, String>): FilterNode =
  FilterNode("overlay", *arguments, "format" to "auto")

/**
 * The stage every tail ends on, blurred letterbox or not.
 *
 * Pins the sample aspect, the frame rate and the pixel format so `concat` sees uniform inputs
 * across every clip. [pixelFormat] is the encoder's own 10-bit format for an HDR export, or
 * `yuv420p` for everything else.
 */
internal fun trailingNodes(
  frameRate: Int,
  pixelFormat: String,
): List<FilterNode> =
  listOf(
    // scale does not reset the sample aspect ratio and concat refuses to join clips whose sample
    // aspects differ, so this is not cosmetic.
    FilterNode("setsar", "r" to "1"),
    FilterNode("fps", "fps" to frameRate.toString()),
    FilterNode("format", "pix_fmts" to pixelFormat),
  )

/**
 * The alpha-carrying format at [pixelFormat]'s own bit depth and chroma layout, for a clip tail
 * that has to pad or composite transparently rather than write an opaque frame outright.
 *
 * `p010le` and `yuv420p10le` are both 10-bit 4:2:0, one packed and one planar, and both convert
 * losslessly to and from the same planar alpha format, `yuva420p10le`. The trailing
 * `format=<pixelFormat>` after the overlay converts back to whichever one the encoder actually
 * wants. Fails loudly on a format this has no equivalent for, so a new HDR format shows up as a
 * build failure instead of a black bar nobody notices.
 */
internal fun alphaPixelFormat(pixelFormat: String): String =
  when (pixelFormat) {
    "yuv420p" -> "yuva420p"
    "yuv420p10le", "p010le" -> "yuva420p10le"
    else -> error("No alpha pixel format known for $pixelFormat.")
  }

private fun renderCapabilities(
  toolchain: Toolchain,
  toneMapRoute: ToneMapRoute?,
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
    realtimeBudgetNanos = null,
    features =
      buildSet {
        add(RenderFeature.MultipassRender)
        if (toolchain.hasFilter("drawtext")) add(RenderFeature.TextRendering)
        if (toneMapRoute != null) add(RenderFeature.HdrToneMapping)
      },
  )

private const val MAX_TEXTURE_FLOOR = 16_384

// What the mp4 muxer this backend always writes will take as-is, without an encoder.
