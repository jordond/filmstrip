package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.transform.internal.PlannedExportEngine
import dev.jordond.filmstrip.transform.internal.copy.Mp4Copy

/**
 * Registers the AVFoundation export backend, so `plan`, `export` and `capabilities` work on Apple
 * targets.
 *
 * @return This builder.
 */
public fun FilmstripBuilder.avFoundationBackend(): FilmstripBuilder =
  builtInEffects()
    .addExportEngineFactory { components -> avFoundationExportEngine(components) }
    .addBackendInfo(
      BackendInfo(name = "avfoundation", artifact = "dev.jordond.filmstrip:filmstrip-transform-avfoundation"),
    )

/**
 * Builds the engine every AVFoundation lowering goes through.
 *
 * The preview calls this too, so a previewed edit and an exported one negotiate against the same
 * codec ladder, the same parity table and the same copy rules.
 *
 * @param components The components the owning `Filmstrip` was built with, whose resolvers lower each
 *   effect spec to something Core Image can run and each audio effect spec to a kernel.
 * @param prober Reads what each source is. The probers in [components] by default.
 * @return An engine that plans, resolves and exports on AVFoundation.
 */
@InternalFilmstripApi
public fun avFoundationExportEngine(
  components: EngineComponents,
  prober: MediaProber = chainedProber(components),
): PlannedExportEngine = avFoundationExportEngine(components, prober, AvDriver(prober))

/**
 * Builds the engine around the [backend] given, so a test can stand in a device of another shape.
 */
internal fun avFoundationExportEngine(
  components: EngineComponents,
  prober: MediaProber,
  backend: AvDriver,
): PlannedExportEngine =
  PlannedExportEngine(
    backend = backend,
    prober = prober,
    resolvers = components.effectResolvers,
    audioResolvers = components.audioEffectResolvers,
    parity = AV_PARITY,
    ladder = AV_LADDER,
    // AVAssetWriter takes a source track by format hint instead of output settings, so a copy
    // needs no encoder at all.
    supportsCopy = true,
    // Every export here writes AVFileTypeMPEG4, so a copy is allowed for exactly what mp4 carries.
    muxerAccepts = { info -> Mp4Copy.accepts(info) },
  )

internal val AV_LADDER: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Hevc)

private val AV_PARITY: Map<String, EffectParity> =
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
    EffectIds.GAUSSIAN_BLUR to EffectParity.Exact,
    EffectIds.VIGNETTE to EffectParity.Exact,
    EffectIds.IMAGE_OVERLAY to EffectParity.Exact,
    EffectIds.TEXT_OVERLAY to EffectParity.Approximate,
  )
