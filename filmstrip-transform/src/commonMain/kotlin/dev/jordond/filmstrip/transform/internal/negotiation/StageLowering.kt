package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.inCanonicalOrder
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.FoldedSpec
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.fusedColorMatrices
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.geometry.OpaqueTracker
import dev.jordond.filmstrip.transform.internal.geometry.frameAfter
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect

/**
 * The frame, rate and grade every effect in one negotiation is lowered against.
 *
 * @property outputSize The frame the whole composition lands on, which every normalized parameter
 *   is measured against.
 * @property hdrTransfer The transfer the encoder is handed, or null when the output is SDR.
 */
internal class LoweringTarget(
  val outputSize: Size,
  val capabilities: RenderCapabilities,
  val frameRate: Int,
  val hdrTransfer: HdrTransfer?,
)

/**
 * Lowers a chain of effect stages onto platform effects through the resolver chain.
 *
 * Runs of color effects fold into one matrix first, except around an effect a resolver registered
 * ahead of the built-in catalogue claims for itself. [unclaimedMessage] names the resolver an
 * effect nobody claimed would have needed.
 */
internal class StageLowering(
  private val resolvers: List<EffectResolver>,
  private val unclaimedMessage: (String) -> String,
) {
  // Where the built-in catalogue sits, or -1 when it was never registered.
  private val catalogue: Int = resolvers.indexOfFirst { it is BuiltInEffectResolver }

  // The resolvers registered ahead of the catalogue. Folding a run of color effects rewrites it
  // into one ColorMatrix, which the catalogue claims, so a resolver that outranks the catalogue for
  // one of the effects the caller wrote is asked for that effect and the run folds around it.
  // Without this its lowering would disappear into the fold with nothing reported.
  //
  // Empty with no catalogue registered: there is no claim to outrank then, and the run folds the
  // way it does for every backend.
  private val overriding: List<EffectResolver> = if (catalogue < 0) emptyList() else resolvers.take(catalogue)

  /**
   * Folds, orders and lowers [stages] in canonical order, one entry per effect that reached a
   * resolver.
   *
   * [inputSize] and [layoutSize] are chained through every geometry effect in turn, so an effect
   * late in the chain is measured against the frame an export would hand it. [storedSize] is the
   * clip's frame as its pixels are stored, and gives way to the chained size once an effect changes it
   * or an effect at a stage [takesSquarePixels] names is reached. [sourceHasAlpha] says the clip's own pixels may be
   * clear, so no effect is told where its frame is opaque.
   */
  fun lowerStages(
    stages: List<List<EffectSpec>>,
    inputSize: Size,
    layoutSize: Size,
    span: TimeRange,
    target: LoweringTarget,
    storedSize: Size = inputSize,
    sourceHasAlpha: Boolean = false,
  ): List<LoweredEffect> {
    var size = inputSize
    var layout = layoutSize
    var stored = storedSize
    // A kept grade is written in BT.2020, so a resolver reading the color space off an HDR export
    // sees the one the encoder is handed rather than the SDR default.
    val colorSpace = if (target.hdrTransfer != null) ColorSpace.Bt2020 else ColorSpace.Bt709
    val attributesAt = { frame: Size, laidOut: Size, held: Size, opaque: NormalizedRect? ->
      Attributes(
        frame,
        target.outputSize,
        laidOut,
        colorSpace,
        target.hdrTransfer,
        target.frameRate.toFloat(),
        span,
        held,
        opaque,
      )
    }
    val ordered = stages.flatMap { it.inCanonicalOrder() }
    val tracker = OpaqueTracker(sourceHasAlpha)
    val chain =
      foldedChain(
        ordered,
        overriddenColor(ordered, inputSize, layoutSize, storedSize, target.capabilities, sourceHasAlpha, attributesAt),
      )

    return chain.entries.mapIndexed { position, entry ->
      val spec = entry.spec
      stored = storedAt(spec, size, stored)
      val attributes = attributesAt(size, layout, stored, tracker.before(spec))
      val resolution =
        chain.resolutions[position]
          ?: resolvers.firstNotNullOfOrNull { it.resolve(spec, target.capabilities, attributes) }

      stored = storedAfter(spec, size, stored)
      tracker.advance(spec, size, frameAfter(spec, size))
      size = frameAfter(spec, size)
      layout = frameAfter(spec, layout)

      when (resolution) {
        is EffectResolution.Resolved -> {
          LoweredEffect(spec, entry.sources, resolution.effect, null)
        }
        is EffectResolution.Degraded -> {
          LoweredEffect(spec, entry.sources, resolution.effect, null, resolution.causes.map { it.message })
        }
        is EffectResolution.Unsupported -> {
          LoweredEffect(spec, entry.sources, null, resolution.message)
        }
        is EffectResolution.Failed -> {
          LoweredEffect(spec, entry.sources, null, null, failed = resolution.error)
        }
        null -> {
          LoweredEffect(spec, entry.sources, null, unclaimedMessage(entry.sources.authoredIds()))
        }
      }
    }
  }

  /**
   * The effects an [overriding] resolver claims out of the runs the fold would otherwise swallow,
   * keyed by their place in [ordered], with the resolution it gave.
   *
   * Only an effect inside a run of two or more is asked about, since a run of one folds to the
   * effect the caller wrote and reaches its resolver unchanged. The resolution is kept, so no
   * resolver is asked for the same effect twice.
   */
  private fun overriddenColor(
    ordered: List<EffectSpec>,
    inputSize: Size,
    layoutSize: Size,
    storedSize: Size,
    capabilities: RenderCapabilities,
    sourceHasAlpha: Boolean,
    attributesAt: (Size, Size, Size, NormalizedRect?) -> Attributes,
  ): Map<Int, EffectResolution> {
    if (overriding.isEmpty()) return emptyMap()

    val color = ordered.map { colorMatrixOf(it) != null }
    val claimed = mutableMapOf<Int, EffectResolution>()
    var size = inputSize
    var layout = layoutSize
    var stored = storedSize
    val tracker = OpaqueTracker(sourceHasAlpha)

    ordered.forEachIndexed { index, spec ->
      val folds = color[index] && (color.getOrElse(index - 1) { false } || color.getOrElse(index + 1) { false })
      stored = storedAt(spec, size, stored)
      if (folds) {
        val attributes = attributesAt(size, layout, stored, tracker.before(spec))
        overriding.firstNotNullOfOrNull { it.resolve(spec, capabilities, attributes) }?.let { claimed[index] = it }
      }
      stored = storedAfter(spec, size, stored)
      tracker.advance(spec, size, frameAfter(spec, size))
      size = frameAfter(spec, size)
      layout = frameAfter(spec, layout)
    }

    return claimed
  }
}

// A stage that takes square pixels is handed the frame squared, so the stored frame is the frame itself from there.
private fun storedAt(
  spec: EffectSpec,
  size: Size,
  stored: Size,
): Size = if (spec.stage.takesSquarePixels) size else stored

// An effect that changes the frame's size writes the size it was asked for, so the stored frame is that size after it.
private fun storedAfter(
  spec: EffectSpec,
  size: Size,
  stored: Size,
): Size = frameAfter(spec, size).let { after -> if (after == size) stored else after }

/**
 * The chain a backend lowers: every run of color effects folded, except around the effects an
 * overriding resolver claimed, with the resolutions those already gave keyed by their place here.
 */
private fun foldedChain(
  ordered: List<EffectSpec>,
  overridden: Map<Int, EffectResolution>,
): FoldedChain {
  if (overridden.isEmpty()) return FoldedChain(ordered.fusedColorMatrices(), emptyMap())

  val entries = mutableListOf<FoldedSpec>()
  val resolutions = mutableMapOf<Int, EffectResolution>()
  var start = 0

  overridden.keys.sorted().forEach { index ->
    entries += ordered.subList(start, index).fusedColorMatrices()
    resolutions[entries.size] = overridden.getValue(index)
    entries += FoldedSpec(ordered[index], listOf(ordered[index]))
    start = index + 1
  }
  entries += ordered.subList(start, ordered.size).fusedColorMatrices()

  return FoldedChain(entries, resolutions)
}

private class FoldedChain(
  val entries: List<FoldedSpec>,
  val resolutions: Map<Int, EffectResolution>,
)

// What a backend calls the effect it was handed. A folded run stands for several authored effects,
// and naming the one the fold made up puts an id in a message that the caller never wrote.
private fun List<EffectSpec>.authoredIds(): String = joinToString(", ") { it.id }

/**
 * One effect, after the resolver chain has been asked.
 *
 * @property sources The effects the caller wrote that [spec] stands for, which is [spec] alone
 *   unless a run of color effects folded into it.
 * @property unsupported Why the effect cannot be realised here, or null when it can or its resolution failed.
 * @property failed The error the effect's resolution failed with, or null when it did not fail.
 * @property degraded Each authored id paired with each thing given up, in the order the resolver gave the causes,
 * when a resolver realised the effect but not exactly as declared.
 */
internal class LoweredEffect(
  val spec: EffectSpec,
  val sources: List<EffectSpec>,
  effect: PlatformEffect?,
  val unsupported: String?,
  degradedMessages: List<String> = emptyList(),
  val failed: FilmstripError? = null,
) {
  val degraded: List<Pair<String, String>> =
    sources.flatMap { source -> degradedMessages.map { source.id to it } }

  // Sources come from the canonically ordered list, so a fused run never spans stages and every
  // source names the same one.
  val resolvedEffect: ResolvedEffect? =
    effect?.let { ResolvedEffect(sources.authoredIds(), it, sources.first().stage) }
}
