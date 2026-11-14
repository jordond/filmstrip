package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Takes the alpha of every pixel left of [edge], a fraction of the clip's own frame, to [alpha] of what it was.
 *
 * At an [alpha] of 0 the colour goes to 0 too, the way a clear bar holds it. It is no built-in effect, so it runs after
 * every built-in one of its stage, and a filter at the clip's scope comes after it.
 */
internal class AlphaLeftOf(
  val edge: Double,
  val alpha: Double,
) : EffectSpec {
  override val id: EffectId get() = EffectId("test.alphaLeftOf")
  override val stage: EffectStage get() = EffectStage.Spatial
}

/**
 * The preview frame at [ALPHA_READ_SLOT] of [clip] alone, with [effects] on the composition over [fill].
 */
internal suspend fun alphaFrameOf(
  clip: Clip,
  effects: List<EffectSpec> = emptyList(),
  fill: Fill,
): PreviewFrame {
  val composition =
    Composition(tracks = listOf(Track(listOf(clip))), effects = effects, audio = AudioSpec.Remove, fill = fill)
  val resolved =
    webCodecsExportEngine(
      components =
        ComponentRegistry
          .Builder()
          .addEffectResolver(BuiltInEffectResolver())
          .addEffectResolver(FilterEffectResolver())
          .addEffectResolver(AlphaLeftOfResolver)
          .build(),
      prober = BrowserProber(),
    ).resolve(composition, ExportSpec(videoCodec = VideoCodec.H264))
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition
  val preview = resolved.toBrowserPreview(composition)
  try {
    return preview.frameAt(outputSlotTime(ALPHA_READ_SLOT, ALPHA_FRAME_RATE)).shouldNotBeNull()
  } finally {
    preview.release()
  }
}

/**
 * Channel [channel] of the pixel at [x], [y] as an eight bit code, counted from the top left.
 */
internal fun PreviewFrame.codeAt(
  x: Int,
  y: Int,
  channel: Int,
): Double = (pixels[(y * size.width + x) * ALPHA_CHANNELS + channel].toInt() and ALPHA_BYTE_MASK).toDouble()

// The texel at [column] on row [y] the way a look's signal reads it, straight from 0 to 1 with [alphaOf] as alpha.
internal fun PreviewFrame.straightTexel(
  column: Int,
  y: Int,
  alphaOf: (Int) -> Double,
): DoubleArray =
  DoubleArray(ALPHA_CHANNELS) { channel ->
    if (channel == ALPHA_CHANNELS - 1) alphaOf(column) else codeAt(column, y, channel) / MAX_CODE
  }

private val AlphaLeftOfResolver =
  EffectResolver { spec, _, _ ->
    if (spec is AlphaLeftOf) {
      val colour = if (spec.alpha > 0.0) "c.rgb" else "vec3(0.0)"
      val source =
        "#version 300 es\nprecision highp float;\nin vec2 vUv;\nout vec4 fragColor;\nuniform sampler2D uFrame;\n" +
          "void main() {\n  vec4 c = texture(uFrame, vUv);\n" +
          "  fragColor = vUv.x < ${glsl(spec.edge)} ? vec4($colour, c.a * ${glsl(spec.alpha)}) : c;\n}\n"
      val step = WebGlStep("alphaLeftOf", source, mapOf("uFrame" to WebGlStep.INPUT)) { _, _, _ -> emptyMap() }
      EffectResolution.Resolved(
        PlatformEffect(WebGlPass("test.alphaLeftOf.${spec.edge}.${spec.alpha}", listOf(step))),
      )
    } else {
      null
    }
  }

// A browser prints a whole double with no point, which GLSL reads as an int.
private fun glsl(value: Double): String = value.toString().let { if ('.' in it) it else "$it.0" }

internal const val ALPHA_FRAME_RATE = 30
internal const val ALPHA_READ_SLOT = 6L
private const val ALPHA_CHANNELS = 4
private const val ALPHA_BYTE_MASK = 0xFF
private const val MAX_CODE = 255.0
