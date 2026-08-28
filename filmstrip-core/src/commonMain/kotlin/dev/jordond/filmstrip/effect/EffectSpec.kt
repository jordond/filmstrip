package dev.jordond.filmstrip.effect

/**
 * A declaration of an effect: pure, serializable data describing intent rather than realization.
 *
 * Contains no platform types and no rendering logic. An [EffectResolver] turns one spec into a
 * platform object, once per pipeline.
 *
 * Implementations must be `@Serializable` so edit lists persist and move between devices, and must
 * carry a stable [id] so a persisted list survives a refactor.
 */
public interface EffectSpec {
  /**
   * Stable identifier, used for persistence and diagnostics.
   *
   * Namespace it like a package, `acme.filmgrain`, so two libraries cannot collide. Never derive it
   * from the class name, which changes under refactoring and under Objective-C export.
   */
  public val id: String

  /**
   * Where in the pipeline this effect runs.
   *
   * Stages run in enum order, and effects within a stage run in a fixed rank filmstrip owns, so
   * call order is ignored. Defaults to [EffectStage.Color]. An effect that belongs elsewhere places
   * itself by overriding this, and lands at the end of that stage.
   */
  public val stage: EffectStage
    get() = EffectStage.Color
}

/**
 * The fixed pipeline stages, in execution order.
 *
 * Filmstrip owns the order, so the same edit produces the same pipeline on both platforms.
 * [Geometry] has finished before [Composite] starts, which is what makes normalised coordinates well-defined.
 *
 * A composition-scope effect always runs on the composed frame before the fill is painted into any
 * region that frame does not cover, so a solid bar or a timeline gap keeps exactly the colour it was
 * given. A blurred background is made of the clip's own pixels rather than a named colour, so it
 * carries the same grade the frame does.
 */
public enum class EffectStage {
  /**
   * Rotate, flip, crop, scale. The output frame is decided here, and nothing later changes it.
   */
  Geometry,

  /**
   * Per-pixel colour, independent of position. Ranked to fuse into as few GPU passes as possible.
   */
  Color,

  /**
   * Effects that sample neighbouring pixels. Runs after [Color] so a grade applies to sharp pixels.
   */
  Spatial,

  /**
   * Background and overlays. Normalised coordinates here are fractions of the frame [Geometry] produced.
   */
  Composite,
}

/**
 * The identifiers of filmstrip's built-in effects.
 *
 * They are part of the persisted contract, so an edit list written against them keeps
 * deserializing.
 */
public object EffectIds {
  public const val ROTATE: String = "filmstrip.rotate"
  public const val FLIP: String = "filmstrip.flip"
  public const val CROP: String = "filmstrip.crop"
  public const val CROP_RECT: String = "filmstrip.cropRect"
  public const val SCALE: String = "filmstrip.scale"
  public const val BRIGHTNESS: String = "filmstrip.brightness"
  public const val WATERMARK: String = "filmstrip.watermark"
  public const val TEXT: String = "filmstrip.text"
}

/**
 * Sorts [this] into the canonical pipeline order.
 *
 * Effects sort by stage, then by filmstrip's rank within that stage, then by the order they were
 * declared. Anything without a rank lands after the ranked effects in its stage, so a third-party
 * effect's position is stable without a priority number.
 *
 * @return the effects in the order the pipeline runs them.
 */
public fun List<EffectSpec>.inCanonicalOrder(): List<EffectSpec> =
  withIndex()
    .sortedWith(
      compareBy(
        { (_, spec) -> spec.stage.ordinal },
        { (_, spec) -> CANONICAL_RANK[spec.id] ?: UNRANKED },
        { (index, _) -> index },
      ),
    ).map { it.value }

// Rank within a stage. Ids with no entry sort after every ranked one. Rotate runs before crop so the
// corners rotation adds can be cropped away, and scale is last because it sets the output size.
private val CANONICAL_RANK: Map<String, Int> =
  mapOf(
    EffectIds.ROTATE to 0,
    EffectIds.FLIP to 1,
    EffectIds.CROP to 2,
    EffectIds.CROP_RECT to 2,
    EffectIds.SCALE to 3,
    EffectIds.BRIGHTNESS to 0,
    EffectIds.WATERMARK to 0,
    EffectIds.TEXT to 1,
  )

private const val UNRANKED = Int.MAX_VALUE
