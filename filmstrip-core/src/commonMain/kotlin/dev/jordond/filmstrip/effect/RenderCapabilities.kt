package dev.jordond.filmstrip.effect

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.ColorSpace

/**
 * What the platform can actually render, on this device, right now.
 *
 * Handed to every [EffectResolver] so a resolver advertises against real capability rather than an
 * assumed platform floor. Constructed by filmstrip only.
 *
 * @property api Which rendering API is behind the pipeline.
 * @property supportsFragmentShader True when fragment shaders can run here.
 * @property supportsComputeShader True when compute shaders can run here.
 * @property supportsHdr True when HDR can be rendered without tone-mapping.
 * @property colorSpaces The colour spaces this device can render into.
 * @property maxTextureSize The longest texture edge the pipeline accepts, in pixels.
 * @property features The additive capabilities present here.
 */
public class RenderCapabilities
  @InternalFilmstripApi
  constructor(
    public val api: RenderApi,
    public val supportsFragmentShader: Boolean,
    public val supportsComputeShader: Boolean,
    public val supportsHdr: Boolean,
    public val colorSpaces: Set<ColorSpace>,
    public val maxTextureSize: Int,
    public val features: Set<RenderFeature>,
  ) {
    /**
     * Checks for one capability.
     *
     * @return true when [feature] is available here.
     */
    public fun has(feature: RenderFeature): Boolean = feature in features

    override fun toString(): String = "RenderCapabilities(api=$api, maxTextureSize=$maxTextureSize, features=$features)"
  }

/**
 * Which rendering API is behind the effect pipeline.
 *
 * This enum is open: filmstrip gains rendering APIs, so entries arrive in minor versions. Every
 * resolver gates on the one it was written for and declines the rest, which is what keeps a new
 * entry from silently routing effects into a resolver that cannot render them.
 */
public enum class RenderApi {
  /**
   * Android's Media3 effect pipeline.
   */
  OpenGlEs,

  /**
   * Apple, with a Metal-backed context.
   */
  Metal,

  /**
   * Apple, with a CPU or unspecified Core Image context.
   */
  CoreImage,

  /**
   * No GPU path available.
   */
  Software,

  /**
   * A browser, with a WebGL 2 context on an `OffscreenCanvas`.
   */
  WebGl,

  /**
   * A declarative filter graph, run by an external toolchain rather than in this process.
   */
  FilterGraph,
}

/**
 * Additive capability flags.
 *
 * Ask [RenderCapabilities.has] for the ones a resolver needs. A resolver that does not recognise a
 * flag never asks for it, so new ones can be added without breaking it.
 */
public enum class RenderFeature {
  /**
   * Tone-mapping between HDR and SDR is available.
   */
  HdrToneMapping,

  /**
   * YUV frames can be sampled directly.
   */
  YuvSampling,

  /**
   * Decoder output can be sampled directly as an external texture.
   */
  ExternalTexture,

  /**
   * An effect may render in more than one pass.
   */
  MultipassRender,

  /**
   * Text can be rasterised into a frame.
   */
  TextRendering,
}
