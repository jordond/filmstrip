package dev.jordond.filmstrip.player.internal

/**
 * The outcome of planning one edit for a preview or a thumbnail.
 */
internal sealed interface PlanResult<out P, out E> {
  /**
   * The edit lowered.
   *
   * @property plan What the backend builds its graph from.
   */
  class Ready<out P>(
    val plan: P,
  ) : PlanResult<P, Nothing>

  /**
   * The edit cannot be rendered here.
   *
   * @property error Why it cannot.
   */
  class Refused<out E>(
    val error: E,
  ) : PlanResult<Nothing, E>
}
