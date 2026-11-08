package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import dev.jordond.filmstrip.thumbnail.ThumbnailSourceFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What [ThumbnailDispatcher] answers when no source claims a request, or the one that does produces no frame.
 */
class ThumbnailDispatcherFailureTest {
  @Test
  fun aFrameNoSourceClaimsNamesThePlayerArtifactAndTheFactoriesAsked() =
    runTest {
      val declining = ThumbnailSourceFactory { _, _ -> null }
      val dispatcher = ThumbnailDispatcher(registryOf(declining, declining))

      val result = dispatcher.frame(requestAt(1.seconds))

      val missing =
        result
          .shouldBeInstanceOf<FrameResult.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.BackendMissing>()
      missing.artifact shouldBe "dev.jordond.filmstrip:filmstrip-player"
      withClue(missing.message) { ("2 factories were consulted" in missing.message).shouldBeTrue() }
    }

  @Test
  fun aStripNoSourceClaimsFailsOncePerPosition() =
    runTest {
      val dispatcher = ThumbnailDispatcher(registryOf())

      val results = dispatcher.frames(COMPOSITION, POSITIONS, 90, ExportSpec()).toList()

      results.size shouldBe POSITIONS.size
      for (result in results) {
        result
          .shouldBeInstanceOf<FrameResult.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.BackendMissing>()
      }
    }

  @Test
  fun anEmptyStripAsksNoFactory() =
    runTest {
      var asked = 0
      val dispatcher =
        ThumbnailDispatcher(
          registryOf(
            ThumbnailSourceFactory { _, _ ->
              asked++
              null
            },
          ),
        )

      val results = dispatcher.frames(COMPOSITION, emptyList(), 90, ExportSpec()).toList()

      results shouldBe emptyList()
      asked shouldBe 0
    }

  // The factory is asked once for the whole run, with the run's first request, so one source serves every position.
  @Test
  fun aStripAsksItsFactoryOnceWithTheFirstPositionAndTheCompositionsRevision() =
    runTest {
      val claimedWith = mutableListOf<ThumbnailRequest>()
      val asked = mutableListOf<ThumbnailRequest>()
      val dispatcher =
        ThumbnailDispatcher(
          registryOf(
            ThumbnailSourceFactory { request, _ ->
              claimedWith += request
              failingSource(asked)
            },
          ),
        )

      dispatcher.frames(COMPOSITION, POSITIONS, 90, ExportSpec()).toList()

      claimedWith.map { it.position } shouldBe listOf(POSITIONS.first())
      asked.map { it.position } shouldBe POSITIONS
      asked.all { it.effectsRevision == COMPOSITION.effectsRevision() && it.heightPx == 90 }.shouldBeTrue()
    }

  @Test
  fun aSourcesFailureReachesTheCallerAsItWasGiven() =
    runTest {
      val source = failingSource(mutableListOf())
      val dispatcher = ThumbnailDispatcher(registryOf(ThumbnailSourceFactory { _, _ -> source }))

      val single = dispatcher.frame(requestAt(1.seconds))
      val strip = dispatcher.frames(COMPOSITION, POSITIONS, 90, ExportSpec()).toList()

      single.shouldBeInstanceOf<FrameResult.Failure>().error shouldBeSameInstanceAs SOURCE_FAILURE
      for (result in strip) result.shouldBeInstanceOf<FrameResult.Failure>().error shouldBeSameInstanceAs SOURCE_FAILURE
    }

  private fun registryOf(vararg factories: ThumbnailSourceFactory): ComponentRegistry {
    val builder = ComponentRegistry.Builder()
    factories.reversed().forEach { builder.addThumbnailSourceFactory(it) }
    return builder.build()
  }

  private fun requestAt(position: Duration): ThumbnailRequest =
    ThumbnailRequest(COMPOSITION, position, null, COMPOSITION.effectsRevision(), precise = true)

  private fun failingSource(asked: MutableList<ThumbnailRequest>): ThumbnailSource =
    ThumbnailSource { request, callback ->
      asked += request
      callback.onThumbnail(ThumbnailResult.Failure(SOURCE_FAILURE))
      Cancellable { }
    }

  private companion object {
    val COMPOSITION: EditComposition = compositionOf { clip(MediaSource.of("/fixtures/strip.mp4")) }

    val POSITIONS = listOf(0.milliseconds, 400.milliseconds, 800.milliseconds)

    val SOURCE_FAILURE = FilmstripError.SourceUnreadable("/fixtures/strip.mp4", "The decoder went away.")
  }
}
