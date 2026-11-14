package dev.jordond.filmstrip.effects.spatial

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import dev.jordond.filmstrip.effects.color.IDENTITY_GRADE_LIGHT_GLSL
import dev.jordond.filmstrip.effects.color.displayLightGradeGlsl
import dev.jordond.filmstrip.effects.color.displayLightGradeUniforms

// A blur or a vignette as one browser pass running SpatialShader's steps. On a kept grade the compositor's frames hold
// linear display light with reference white at one, which the steps carry into the light the compositor grades in.
internal fun GaussianBlur.toPass(attributes: Attributes): EffectResolution =
  pass(
    id.value,
    SpatialShader.blurSteps(
      this,
      attributes.hdrTransfer?.displayLightGradeUniforms(),
      gradeLight = attributes.gradeLight(),
    ),
  )

internal fun Vignette.toPass(attributes: Attributes): EffectResolution =
  pass(
    id.value,
    SpatialShader.vignetteSteps(
      this,
      attributes.hdrTransfer?.displayLightGradeUniforms(),
      gradeLight = attributes.gradeLight(),
    ),
  )

private fun Attributes.gradeLight(): String = hdrTransfer?.displayLightGradeGlsl() ?: IDENTITY_GRADE_LIGHT_GLSL

private fun pass(
  programKey: String,
  steps: List<SpatialStep>,
): EffectResolution =
  EffectResolution.Resolved(
    PlatformEffect(
      WebGlPass(
        programKey,
        steps.map { step ->
          WebGlStep(
            name = step.name,
            fragmentSource = step.fragment,
            reads = step.reads.mapValues { (_, source) -> source.asWebGlRead() },
            targetAt = step.targetAt,
          ) { _, width, height -> step.uniformsAt(width, height) }
        },
      ),
    ),
  )

private fun String.asWebGlRead(): String = if (this == SpatialShader.INPUT) WebGlStep.INPUT else this
