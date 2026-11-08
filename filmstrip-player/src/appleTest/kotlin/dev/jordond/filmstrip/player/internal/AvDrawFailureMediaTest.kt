package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.appleExportLowering
import dev.jordond.filmstrip.player.appleFixtureComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.pumpMainRunLoopDuringContracts
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class AvDrawFailureMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a failure the current chain reports is held and handed on`() =
    contractTest { scope ->
      val chain = fixtureChain()
      val failed = mutableListOf<PlaybackError>()
      val draw = AvDrawFailure(scope, { chain }, { failed += it })

      draw.watch(chain)
      chain.onChainFailure.shouldNotBeNull().invoke(FilmstripError.SourceUnreadable(SOURCE, WHY))
      awaitContract("the failure to reach the engine's dispatcher") { failed.isNotEmpty() }

      failed.single().shouldBeInstanceOf<PlaybackError.SourceUnreadable>().message shouldBe WHY
      draw.error shouldBe failed.single()
    }

  @Test
  fun `a failure that names no source reads as a format the preview cannot draw`() =
    contractTest { scope ->
      val chain = fixtureChain()
      val draw = AvDrawFailure(scope, { chain }, { })

      draw.watch(chain)
      chain.onChainFailure.shouldNotBeNull().invoke(
        FilmstripError.InvalidComposition(WHY),
      )
      awaitContract("the failure to be held") { draw.error != null }

      draw.error.shouldBeInstanceOf<PlaybackError.UnsupportedFormat>().message shouldBe WHY
    }

  @Test
  fun `a failure from a chain a rebuild replaced is dropped`() =
    contractTest { scope ->
      val replaced = fixtureChain()
      val current = fixtureChain()
      val failed = mutableListOf<PlaybackError>()
      val draw = AvDrawFailure(scope, { current }, { failed += it })

      draw.watch(replaced)
      replaced.onChainFailure.shouldNotBeNull().invoke(FilmstripError.SourceUnreadable(SOURCE, WHY))
      settle()

      failed.shouldBeEmpty()
      draw.error.shouldBeNull()
    }

  @Test
  fun `clearing drops what was held`() =
    contractTest { scope ->
      val chain = fixtureChain()
      val draw = AvDrawFailure(scope, { chain }, { })
      draw.watch(chain)
      chain.onChainFailure.shouldNotBeNull().invoke(FilmstripError.SourceUnreadable(SOURCE, WHY))
      awaitContract("the failure to be held") { draw.error != null }

      draw.clear()

      draw.error.shouldBeNull()
    }

  private suspend fun fixtureChain(): AvComposition =
    appleExportLowering(appleFixtureComposition()).toAvComposition().also { it.renderSize.shouldNotBeNull() }

  private companion object {
    const val SOURCE = "apple_export_a.mp4"
    const val WHY = "The picture is too large to draw at its color."
  }
}
