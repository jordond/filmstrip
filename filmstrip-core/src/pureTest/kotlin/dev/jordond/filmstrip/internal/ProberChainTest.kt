package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaProberFactory
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ProberChainTest {
  private val source = MediaSource.of("/fixtures/clip.mp4")

  @Test
  fun `the last prober answers after every registered one declines`() =
    runTest {
      val asked = mutableListOf<String>()
      val components =
        ComponentRegistry
          .Builder()
          .addMediaProberFactory(MediaProberFactory { refusing("registered", asked) })
          .build()

      val result = chainedProber(components, last = refusing("last", asked)).probe(source)

      asked shouldBe listOf("registered", "last")
      result.shouldBeInstanceOf<ProbeResult.Failure>().error.message shouldBe "registered"
    }

  @Test
  fun `a prober registered and passed as the last one is asked once`() =
    runTest {
      val asked = mutableListOf<String>()
      val prober = refusing("shared", asked)
      val components = ComponentRegistry.Builder().addMediaProberFactory(MediaProberFactory { prober }).build()

      chainedProber(components, last = prober).probe(source)

      asked shouldBe listOf("shared")
    }

  private fun refusing(
    name: String,
    asked: MutableList<String>,
  ) = MediaProber {
    asked += name
    ProbeResult.Failure(FilmstripError.InvalidComposition(name))
  }
}
