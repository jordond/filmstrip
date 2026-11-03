package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Capabilities a browser text overlay resolves under, SDR with text drawing.
 */
internal val TEXT_CAPABILITIES = textCapabilities(4_096)

/**
 * Capabilities a browser text overlay resolves under, SDR with text drawing, holding at most [maxTextureSize] pixels
 * on a texture's side.
 */
internal fun textCapabilities(maxTextureSize: Int): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.WebGl,
    supportsFragmentShader = true,
    supportsComputeShader = false,
    supportsHdr = false,
    colorSpaces = setOf(ColorSpace.Bt709),
    maxTextureSize = maxTextureSize,
    features = setOf(RenderFeature.TextRendering),
  )

/**
 * Attributes a browser text overlay resolves under, a 1280 by 720 SDR frame laid out at its own size.
 */
internal val TEXT_ATTRIBUTES =
  Attributes(
    inputSize = Size(1280, 720),
    outputSize = Size(1280, 720),
    layoutSize = Size(1280, 720),
    colorSpace = ColorSpace.Bt709,
    hdrTransfer = null,
    frameRate = 30f,
    span = TimeRange.of(Duration.ZERO, 10.seconds),
  )

/**
 * Draws [text] the way [rasterizeText] does and fails unless it is drawn.
 *
 * The texture side is [TEXT_CAPABILITIES]'s unless a test lifts it.
 */
internal fun drawnText(
  text: String,
  style: TextStyle,
  frame: Size,
  maxSide: Int = TEXT_CAPABILITIES.maxTextureSize,
): LaidOutText = rasterizeText(text, style, frame, maxSide).shouldBeInstanceOf<TextRaster.Drawn<LaidOutText>>().pixels
