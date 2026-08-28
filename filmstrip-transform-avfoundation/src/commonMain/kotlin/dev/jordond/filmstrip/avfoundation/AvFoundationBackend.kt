package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.FilmstripBuilder
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.AvFoundationDriver
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.builtInEffects
import dev.jordond.filmstrip.export.VideoCodec
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
  builtInEffects().addExportEngineFactory { context, components ->
    val prober = chainedProber(context, components)
    PlannedExportEngine(
      backend = AvFoundationDriver(context, prober),
      prober = prober,
      resolvers = components.effectResolvers,
      parity = AVFOUNDATION_PARITY,
      ladder = AVFOUNDATION_LADDER,
      // AVAssetWriter takes a source track by format hint instead of output settings, so a copy
      // needs no encoder at all.
      supportsPassthrough = true,
      // Every export here writes AVFileTypeMPEG4, so a copy is allowed for exactly what mp4 carries.
      canCopy = { info -> Mp4Copy.accepts(info) },
    )
  }

internal val AVFOUNDATION_LADDER: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Hevc)

private val AVFOUNDATION_PARITY: Map<String, EffectParity> =
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
