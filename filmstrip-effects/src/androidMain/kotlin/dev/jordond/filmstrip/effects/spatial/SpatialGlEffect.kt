package dev.jordond.filmstrip.effects.spatial

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.GlStep
import dev.jordond.filmstrip.effects.StepShaderProgram
import dev.jordond.filmstrip.effects.color.HdrColorMatrixUniforms
import dev.jordond.filmstrip.effects.media3SignalUniforms
import dev.jordond.filmstrip.effects.stepTargetFormat
import dev.jordond.filmstrip.media.HdrTransfer

/**
 * Draws a [GaussianBlur] or a [Vignette] as a media3 pass running [SpatialShader]'s steps.
 *
 * media3 says which domain the frame is in when it asks for the program. A graph in HDR holds the light of [transfer],
 * which the steps move to the SDR signal and back through [media3SignalUniforms]. Any other graph holds codes, which a
 * readback that tone maps a kept grade to BT.709 gets too.
 *
 * media3 holds a frame's color straight, not premultiplied, and clears a bar it letterboxes to no alpha, so the steps
 * read and write straight color and each pixel keeps the alpha it came with. The fill painted after them reads that
 * alpha the same way.
 *
 * @property spec The blur or vignette to draw, which is all [steps] reads besides the domain.
 * @property transfer The grade the plan keeps, or null when it writes SDR.
 * @property steps [SpatialShader]'s steps for [spec] on a frame holding a domain's light, or codes when it is null.
 */
internal class SpatialGlEffect private constructor(
  val spec: EffectSpec,
  val transfer: HdrTransfer?,
  private val steps: (HdrColorMatrixUniforms?) -> List<SpatialStep>,
) : GlEffect {
  constructor(blur: GaussianBlur, transfer: HdrTransfer?) :
    this(blur, transfer, { SpatialShader.blurSteps(blur, it) })

  constructor(vignette: Vignette, transfer: HdrTransfer?) :
    this(vignette, transfer, { SpatialShader.vignetteSteps(vignette, it) })

  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    val domain =
      when {
        !useHdr -> null
        transfer != null -> transfer.media3SignalUniforms
        else -> throw VideoFrameProcessingException("${spec.id} keeps no grade, and this frame processor runs in HDR.")
      }
    return StepShaderProgram(
      stepsIn(domain),
      stepTargetFormat(GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()),
      useHighPrecisionColorComponents = useHdr,
    )
  }

  /**
   * The steps this effect runs on a graph holding [domain]'s light, or codes when it is null.
   */
  fun stepsIn(domain: HdrColorMatrixUniforms?): List<GlStep> =
    steps(domain).map { step ->
      GlStep(
        step.name,
        step.fragment,
        step.reads.mapValues { (_, source) -> if (source == SpatialShader.INPUT) StepShaderProgram.INPUT else source },
        step.targetAt,
      ) { _, width, height -> step.uniformsAt(width, height) }
    }

  // Compared by value so media3 keeps the program across clips that lower to the same pass.
  override fun equals(other: Any?): Boolean =
    this === other || (other is SpatialGlEffect && spec == other.spec && transfer == other.transfer)

  override fun hashCode(): Int = HASH * spec.hashCode() + transfer.hashCode()

  private companion object {
    const val HASH = 31
  }
}
