package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.AvFoundationDriver
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.chainedProber
import dev.jordond.filmstrip.transform.internal.Mp4Copy
import dev.jordond.filmstrip.transform.internal.PlannedExportEngine

/**
 * Registers the AVFoundation export backend, so `plan`, `export` and `capabilities` work on Apple
 * targets.
 *
 * @return This builder.
 */
@OptIn(InternalFilmstripApi::class)
public fun FilmstripBuilder.avFoundationBackend(): FilmstripBuilder =
  builtInEffects()
    .addExportEngineFactory { components ->
      avFoundationExportEngine(chainedProber(components), components.effectResolvers)
    }.addBackendInfo(
      BackendInfo(name = "avfoundation", artifact = "dev.jordond.filmstrip:filmstrip-transform-avfoundation"),
    )

/**
 * Builds the engine every AVFoundation lowering goes through.
 *
 * The preview calls this too, so a previewed edit and an exported one negotiate against the same
 * codec ladder, the same parity table and the same copy rules rather than against two sets that
 * have to be kept in step.
 *
 * @param prober Reads what each source is.
 * @param resolvers Lower an effect spec to something Core Image can run.
 * @return An engine that plans, resolves and exports on AVFoundation.
 */
@InternalFilmstripApi
public fun avFoundationExportEngine(
  prober: MediaProber,
  resolvers: List<EffectResolver>,
): PlannedExportEngine =
  PlannedExportEngine(
    backend = AvFoundationDriver(prober),
    prober = prober,
    resolvers = resolvers,
    parity = AVFOUNDATION_PARITY,
    ladder = AVFOUNDATION_LADDER,
    // AVAssetWriter takes a source track by format hint instead of output settings, so a copy
    // needs no encoder at all.
    supportsPassthrough = true,
    // Every export here writes AVFileTypeMPEG4, so a copy is allowed for exactly what mp4 carries.
    canCopy = { info -> Mp4Copy.accepts(info) },
  )

internal val AVFOUNDATION_LADDER: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Hevc)

private val AVFOUNDATION_PARITY: Map<String, EffectParity> =
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
    EffectIds.TEXT_OVERLAY to EffectParity.Approximate,
  )
