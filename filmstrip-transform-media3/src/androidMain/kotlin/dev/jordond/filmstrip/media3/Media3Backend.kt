package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.chainedProber
import dev.jordond.filmstrip.media3.internal.Media3Driver
import dev.jordond.filmstrip.transform.internal.Mp4Copy
import dev.jordond.filmstrip.transform.internal.PlannedExportEngine

/**
 * Registers the media3 export backend, so `plan`, `export` and `capabilities` work on Android.
 *
 * @return This builder.
 */
@OptIn(InternalFilmstripApi::class)
public fun FilmstripBuilder.media3Backend(): FilmstripBuilder =
  builtInEffects()
    .addExportEngineFactory { components ->
      media3ExportEngine(chainedProber(components), components.effectResolvers)
    }.addBackendInfo(BackendInfo(name = "media3", artifact = "dev.jordond.filmstrip:filmstrip-transform-media3"))

/**
 * Builds the engine every media3 lowering goes through.
 *
 * The preview calls this too, so a previewed edit and an exported one negotiate against the same
 * codec ladder, the same parity table and the same copy rules rather than against two sets that
 * have to be kept in step.
 *
 * @param prober Reads what each source is.
 * @param resolvers Lower an effect spec to something media3 can run.
 * @return An engine that plans, resolves and exports on media3.
 */
@InternalFilmstripApi
public fun media3ExportEngine(
  prober: MediaProber,
  resolvers: List<EffectResolver>,
): PlannedExportEngine =
  PlannedExportEngine(
    backend = Media3Driver(prober),
    prober = prober,
    resolvers = resolvers,
    parity = MEDIA3_PARITY,
    ladder = MEDIA3_LADDER,
    // Transformer names no MIME type on a copy and muxes the source's own samples across, so no
    // encoder is opened.
    supportsPassthrough = true,
    // Every export here writes mp4, so a copy is allowed for exactly what mp4 carries.
    canCopy = { info -> Mp4Copy.accepts(info) },
  )

private val MEDIA3_LADDER: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Hevc)

private val MEDIA3_PARITY: Map<String, EffectParity> =
  mapOf(
    EffectIds.ROTATE to EffectParity.Exact,
    EffectIds.FLIP to EffectParity.Exact,
    EffectIds.CROP to EffectParity.Exact,
    EffectIds.CROP_RECT to EffectParity.Exact,
    EffectIds.SCALE to EffectParity.Exact,
    EffectIds.BRIGHTNESS to EffectParity.Exact,
    EffectIds.WATERMARK to EffectParity.Exact,
    EffectIds.TEXT to EffectParity.Approximate,
  )
