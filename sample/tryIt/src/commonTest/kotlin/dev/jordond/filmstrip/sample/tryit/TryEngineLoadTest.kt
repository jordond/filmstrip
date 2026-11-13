@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.SamplePreset
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TryEngineLoadTest {
  private val source = MediaSource.ofUri("blob:beach")
  private val asked = mutableListOf<SamplePreset>()

  private fun TestScope.engine(load: suspend () -> MediaSource = { source }) =
    TryEngine(
      backgroundScope,
      exporter = { _, _ -> flowOf() },
      loadClip = { preset ->
        asked += preset
        load()
      },
      save = {},
      release = {},
    )

  @Test
  fun `load fetches the beach clip`() =
    runTest {
      val engine = engine()
      engine.load()
      runCurrent()

      asked shouldBe listOf(beachClip)
      engine.state.value.source shouldBe source
    }

  @Test
  fun `a failed load shows its text and retry fetches again`() =
    runTest {
      var loads = 0
      val engine =
        engine {
          if (++loads == 1) throw IllegalStateException("The download answered HTTP 503.")
          source
        }
      engine.load()
      runCurrent()
      statusOf(engine.state.value).error shouldBe "The download answered HTTP 503."
      engine.state.value.source
        .shouldBeNull()

      engine.apply(Control.Retry)
      runCurrent()

      loads shouldBe 2
      engine.state.value.source shouldBe source
      engine.state.value.error
        .shouldBeNull()
    }

  @Test
  fun `a clip already here is not fetched again by a retry and gets a new attempt`() =
    runTest {
      var loads = 0
      val engine =
        engine {
          loads++
          source
        }
      engine.load()
      runCurrent()
      val attempt = engine.state.value.attempt

      engine.apply(Control.Retry)
      runCurrent()

      loads shouldBe 1
      engine.state.value.attempt shouldBe attempt + 1
    }

  @Test
  fun `a retry pressed while the download runs joins it`() =
    runTest {
      val arriving = CompletableDeferred<MediaSource>()
      var loads = 0
      val engine =
        engine {
          loads++
          arriving.await()
        }
      engine.load()
      runCurrent()

      engine.apply(Control.Retry)
      arriving.complete(source)
      runCurrent()

      loads shouldBe 1
      engine.state.value.source shouldBe source
    }

  @Test
  fun `a download that stalls gives up and a retry fetches again`() =
    runTest {
      var loads = 0
      val engine =
        engine {
          if (++loads == 1) awaitCancellation()
          source
        }
      engine.load()
      advanceTimeBy(6.minutes)
      runCurrent()
      statusOf(engine.state.value).error shouldBe "The clip took too long to load."

      engine.apply(Control.Retry)
      runCurrent()

      loads shouldBe 2
      engine.state.value.source shouldBe source
    }

  @Test
  fun `a download that gives up is aborted before a retry fetches again`() =
    runTest {
      val running = mutableListOf<Int>()
      var loads = 0
      var mostAtOnce = 0
      val engine =
        engine {
          val load = ++loads
          running += load
          mostAtOnce = maxOf(mostAtOnce, running.size)
          try {
            if (load == 1) awaitCancellation()
            source
          } finally {
            running -= load
          }
        }
      engine.load()
      advanceTimeBy(6.minutes)
      runCurrent()
      running.shouldBeEmpty()

      engine.apply(Control.Retry)
      runCurrent()

      loads shouldBe 2
      mostAtOnce shouldBe 1
    }

  @Test
  fun `a retry gets the whole limit`() =
    runTest {
      val arriving = CompletableDeferred<MediaSource>()
      val engine = engine { arriving.await() }
      engine.load()
      advanceTimeBy(4.minutes)
      engine.apply(Control.Retry)
      advanceTimeBy(4.minutes)
      runCurrent()
      engine.state.value.error
        .shouldBeNull()

      arriving.complete(source)
      runCurrent()

      engine.state.value.source shouldBe source
    }

  @Test
  fun `a retry runs out at its own limit`() =
    runTest {
      val engine = engine { awaitCancellation() }
      engine.load()
      advanceTimeBy(4.minutes)
      engine.apply(Control.Retry)
      advanceTimeBy(5.minutes + 1.seconds)
      runCurrent()

      engine.state.value.error shouldBe "The clip took too long to load."
    }

  @Test
  fun `a clip that arrived outlasts the limit`() =
    runTest {
      var loads = 0
      val engine =
        engine {
          loads++
          source
        }
      engine.load()
      runCurrent()
      advanceTimeBy(6.minutes)
      engine.load()
      runCurrent()

      loads shouldBe 1
      engine.state.value.source shouldBe source
    }
}
