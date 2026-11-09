package dev.jordond.filmstrip.thumbnail

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SerialThumbnailRunTest {
  @Test
  fun theNextRequestIsAskedForOnlyOnceTheOneBeforeItHasLanded() {
    val source = LaterSource()
    val delivered = mutableListOf<Int>()

    SerialThumbnailRun(source::requestThumbnail, requests(3)) { index, _ -> delivered += index }
    source.log shouldBe listOf("ask 0")

    source.answer()
    source.log shouldBe listOf("ask 0", "ask 1", "release 0")

    source.answer()
    source.answer()

    delivered shouldBe listOf(0, 1, 2)
    source.log shouldBe listOf("ask 0", "ask 1", "release 0", "ask 2", "release 1", "release 2")
  }

  // The previous handle is released after the next is issued, so a source holding a decoder open across the run is
  // never left with nothing outstanding.
  @Test
  fun aSourceAnsweringWhereItStandsHasEveryHandleReleasedInTheSameOrder() {
    val source = InlineSource()
    val delivered = mutableListOf<Int>()

    SerialThumbnailRun(source::requestThumbnail, requests(3)) { index, _ -> delivered += index }

    delivered shouldBe listOf(0, 1, 2)
    source.log shouldBe listOf("ask 0", "ask 1", "release 0", "ask 2", "release 1", "release 2")
  }

  @Test
  fun aLongRunAnsweredWhereItStandsIsServedWithoutGrowingTheStack() {
    val source = InlineSource(logging = false)
    var delivered = 0

    SerialThumbnailRun(source::requestThumbnail, requests(LONG_RUN)) { index, _ ->
      index shouldBe delivered
      delivered++
    }

    delivered shouldBe LONG_RUN
    source.released shouldBe LONG_RUN
  }

  @Test
  fun eachIndexIsDeliveredWithTheResultForItsOwnRequest() {
    val source = InlineSource()
    val positions = mutableListOf<Duration>()
    val runRequests = requests(4)

    SerialThumbnailRun(source::requestThumbnail, runRequests) { index, result ->
      positions += runRequests[index].position
      (result as ThumbnailResult.Failure).error.message shouldBe runRequests[index].position.toString()
    }

    positions shouldBe runRequests.map { it.position }
  }

  @Test
  fun cancellingReleasesTheOutstandingRequestAndAsksForNoMore() {
    val source = LaterSource()
    val delivered = mutableListOf<Int>()
    val run = SerialThumbnailRun(source::requestThumbnail, requests(3)) { index, _ -> delivered += index }

    source.answer()
    run.cancel()
    run.cancel()
    source.answer()

    delivered shouldBe listOf(0)
    source.log shouldBe listOf("ask 0", "ask 1", "release 0", "release 1")
  }

  @Test
  fun anEmptyRunAsksForNothing() {
    val source = LaterSource()

    SerialThumbnailRun(source::requestThumbnail, emptyList()) { _, _ -> error("nothing was asked for") }

    source.log shouldBe emptyList()
  }

  private fun requests(count: Int): List<ThumbnailRequest> =
    List(count) { ThumbnailRequest(COMPOSITION, (it * 40).milliseconds, 90, 0L, precise = false) }

  private companion object {
    const val LONG_RUN = 20_000

    val COMPOSITION: Composition = compositionOf { clip(MediaSource.of("/fixtures/strip.mp4")) }
  }
}

private fun failureFor(request: ThumbnailRequest): ThumbnailResult =
  ThumbnailResult.Failure(FilmstripError.InvalidComposition(request.position.toString()))

/**
 * A source that holds every request until [answer] lands the oldest one, logging each request and release.
 */
private class LaterSource : ThumbnailSource {
  val log: MutableList<String> = mutableListOf()

  private val pending: MutableList<() -> Unit> = mutableListOf()
  private var asked = 0

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    val id = asked++
    log += "ask $id"
    pending += { callback.onThumbnail(failureFor(request)) }
    return Cancellable { log += "release $id" }
  }

  fun answer() {
    pending.removeAt(0)()
  }
}

/**
 * A source that answers inside [requestThumbnail], before its handle has been returned.
 */
private class InlineSource(
  private val logging: Boolean = true,
) : ThumbnailSource {
  val log: MutableList<String> = mutableListOf()
  var released: Int = 0
    private set

  private var asked = 0

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    val id = asked++
    if (logging) log += "ask $id"
    callback.onThumbnail(failureFor(request))
    return Cancellable {
      released++
      if (logging) log += "release $id"
    }
  }
}
