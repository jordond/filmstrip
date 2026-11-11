package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.transform.internal.composition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How one phase of a negotiation hands its outcome to the next, and the run a laid timeline covers.
 */
class NegotiationPhasesTest {
  @Test
  fun `a phase that settled hands its value on without refusing`() {
    val settled: Phase<Int> = Phase.Next(42)

    settled.orElse { error("a settled phase refused with $it") } shouldBe 42
  }

  @Test
  fun `a phase that refused hands its own error to the refusal`() {
    val error = FilmstripError.InvalidComposition("refused here")
    val refused: Phase<Int> = Phase.Refused(error)

    val thrown = assertFailsWith<Refusal> { refused.orElse { throw Refusal(it) } }

    thrown.error shouldBeSameInstanceAs error
  }

  @Test
  fun `a timeline spans from the start of the composition to its duration`() {
    Timeline(2_500.milliseconds, emptyList(), emptyList(), emptyList()).span shouldBe
      TimeRange.of(Duration.ZERO, 2_500.milliseconds)
  }

  private class Refusal(
    val error: FilmstripError,
  ) : Exception()
}
