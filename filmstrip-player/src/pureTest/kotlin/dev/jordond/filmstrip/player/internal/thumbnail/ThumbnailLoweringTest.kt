package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.player.internal.PlanResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * How a thumbnail's lowering still answers its request when part of the lowering throws instead of refusing.
 */
class ThumbnailLoweringTest {
  @Test
  fun `a lowering that answers is handed back as it is`() =
    runTest {
      val ready = PlanResult.Ready("plan")
      val refused = PlanResult.Refused(FilmstripError.InvalidComposition("refused on its own"))

      lowerThumbnail { ready } shouldBeSameInstanceAs ready
      lowerThumbnail<String> { refused } shouldBeSameInstanceAs refused
    }

  @Test
  fun `a throw becomes an underlying refusal carrying its message`() =
    runTest {
      val result = lowerThumbnail<String> { throw IllegalStateException("the prober fell over") }

      val error =
        (result.shouldBeInstanceOf<PlanResult.Refused<FilmstripError>>().error)
          .shouldBeInstanceOf<FilmstripError.Underlying>()
      error.platformCode shouldBe FilmstripError.Underlying.NO_PLATFORM_CODE
      error.message shouldBe "the prober fell over"
    }

  @Test
  fun `a throw with no message is named by what was thrown`() =
    runTest {
      val thrown = UnsupportedOperationException()

      val result = lowerThumbnail<String> { throw thrown }

      val error =
        (result.shouldBeInstanceOf<PlanResult.Refused<FilmstripError>>().error)
          .shouldBeInstanceOf<FilmstripError.Underlying>()
      error.message shouldBe thrown.toString()
    }

  @Test
  fun `a cancellation is let through rather than answered`() =
    runTest {
      val cancelled = CancellationException("the handle cancelled it")

      val thrown = assertFailsWith<CancellationException> { lowerThumbnail<String> { throw cancelled } }

      thrown shouldBeSameInstanceAs cancelled
    }
}
