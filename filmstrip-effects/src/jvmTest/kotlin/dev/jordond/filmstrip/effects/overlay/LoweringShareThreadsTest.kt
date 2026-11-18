package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test

class LoweringShareThreadsTest {
  @Test
  fun overlaysAskingTogetherWaitForOneBuild() {
    val image = ImageSource.Path("/fixtures/shared.gif")
    val share = LoweringShare<Unit, Any>()
    share.readFor(
      Composition(tracks = listOf(Track(emptyList())), effects = listOf(ImageOverlay(image, Corner.TopStart))),
    )
    val builds = AtomicInteger()
    val start = CountDownLatch(1)
    val answers = arrayOfNulls<Any>(THREADS)

    val threads =
      List(THREADS) { index ->
        thread {
          start.await()
          answers[index] =
            share.valueFor(image, Unit) {
              Thread.sleep(BUILD_MILLIS)
              builds.incrementAndGet()
              Any()
            }
        }
      }
    start.countDown()
    threads.forEach { it.join() }

    builds.get() shouldBe 1
    answers.toSet().size shouldBe 1
  }

  private companion object {
    const val THREADS = 8
    const val BUILD_MILLIS = 50L
  }
}
