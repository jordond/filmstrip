package dev.jordond.filmstrip.effects.filters.internal

import dev.jordond.filmstrip.effect.WebGlStep
import kotlin.time.DurationUnit

// A filter's chain as browser steps, each reading its uniforms off the frame's composition time and size, with
// fixed added to every step's uniforms.
internal fun List<FilterStep>.toWebGlSteps(fixed: Map<String, FloatArray> = emptyMap()): List<WebGlStep> =
  map { step ->
    WebGlStep(
      name = step.name,
      fragmentSource = step.fragment,
      reads = step.reads.mapValues { (_, source) -> if (source == FilterStep.INPUT) WebGlStep.INPUT else source },
      targetAt = step.targetAt,
    ) { time, width, height -> step.uniformsAt(time.toDouble(DurationUnit.SECONDS), width, height) + fixed }
  }
