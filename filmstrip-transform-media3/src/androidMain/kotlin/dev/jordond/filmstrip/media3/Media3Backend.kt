package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media3.internal.Media3Driver
import dev.jordond.filmstrip.transform.internal.PlannedExportEngine
import dev.jordond.filmstrip.transform.internal.copy.Mp4Copy

/**
 * Registers the media3 export backend, so `plan`, `export` and `capabilities` work on Android.
 *
 * @return This builder.
 */
public fun FilmstripBuilder.media3Backend(): FilmstripBuilder =
  builtInEffects()
    .addExportEngineFactory { components -> media3ExportEngine(components) }
    .addBackendInfo(BackendInfo(name = "media3", artifact = "dev.jordond.filmstrip:filmstrip-transform-media3"))

/**
 * Builds the engine every media3 lowering goes through.
 *
 * The preview calls this too, so a previewed edit and an exported one negotiate against the same
 * codec ladder, the same parity table and the same copy rules.
 *
 * @param components The components the owning `Filmstrip` was built with, whose resolvers lower each
 *   effect spec to something media3 can run.
 * @param prober Reads what each source is. The probers in [components] by default.
 * @return An engine that plans, resolves and exports on media3.
 */
@InternalFilmstripApi
public fun media3ExportEngine(
  components: EngineComponents,
  prober: MediaProber = chainedProber(components),
): PlannedExportEngine =
  PlannedExportEngine(
    backend = Media3Driver(prober),
    prober = prober,
    resolvers = components.effectResolvers,
    parity = MEDIA3_PARITY,
    ladder = MEDIA3_LADDER,
    // Transformer names no MIME type on a copy and muxes the source's own samples across, so no
    // encoder is opened.
    supportsCopy = true,
    // Every export here writes mp4, so a copy is allowed for exactly what mp4 carries.
    muxerAccepts = { info -> Mp4Copy.accepts(info) },
    copyLimit = ::media3CopyLimit,
  )

/**
 * The blocker media3 puts on copying [info]'s streams, or null when it can copy them.
 *
 * A source whose pixels are not square is blocked with [CopyBlocker.NonSquarePixels]. media3's own limit: its
 * Transformer re-encodes such a source to square pixels on one sequence, and on the two a copy with audio and video
 * lowers to it writes the samples across with no such check. Blocking the copy puts both on the transcode.
 */
internal fun media3CopyLimit(info: MediaInfo): CopyBlocker? {
  val video = info.video ?: return null
  return if (video.pixelAspectRatio == SQUARE_PIXELS) null else CopyBlocker.NonSquarePixels
}

private const val SQUARE_PIXELS = 1f

private val MEDIA3_LADDER: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Hevc)

private val MEDIA3_PARITY: Map<String, EffectParity> =
  mapOf(
    EffectIds.ROTATE to EffectParity.Exact,
    EffectIds.FLIP to EffectParity.Exact,
    EffectIds.CROP to EffectParity.Exact,
    EffectIds.CROP_RECT to EffectParity.Exact,
    EffectIds.KEN_BURNS to EffectParity.Exact,
    EffectIds.SCALE to EffectParity.Exact,
    EffectIds.BRIGHTNESS to EffectParity.Exact,
    EffectIds.RGB_ADJUSTMENT to EffectParity.Exact,
    EffectIds.CONTRAST to EffectParity.Exact,
    EffectIds.SATURATION to EffectParity.Exact,
    EffectIds.HUE_ROTATE to EffectParity.Exact,
    EffectIds.SEPIA to EffectParity.Exact,
    EffectIds.INVERT to EffectParity.Exact,
    EffectIds.COLOR_MATRIX to EffectParity.Exact,
    EffectIds.IMAGE_OVERLAY to EffectParity.Exact,
    EffectIds.GAUSSIAN_BLUR to EffectParity.Exact,
    EffectIds.VIGNETTE to EffectParity.Exact,
    EffectIds.TEXT_OVERLAY to EffectParity.Approximate,
  )
