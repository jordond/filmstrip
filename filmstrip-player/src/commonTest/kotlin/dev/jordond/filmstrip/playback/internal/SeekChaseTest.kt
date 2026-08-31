package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.player.SeekAccuracy
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SeekChaseTest {
  private val platform = FakePlatformSeek()
  private val resolved = mutableListOf<Pair<Duration, SeekResolution>>()

  private val chase =
    SeekChase(
      platformSeek = platform,
      isReady = { platform.isReady },
      onResolved = { position, resolution -> resolved += position to resolution },
    )

  // The exit criterion. Two and fifty are the endpoints and both agree under a wrong reading, so
  // the sizes that matter are the ones in between, where a burst dispatches, coalesces, drains and
  // dispatches again.
  @Test
  fun `every request in a burst resolves exactly once`() {
    for (burst in listOf(2, 3, 4, 5, 7, 11, 23, 50)) {
      platform.issued.clear()
      resolved.clear()

      val requests = (1..burst).map { it.milliseconds }
      requests.forEach { chase.request(it, SeekAccuracy.Exact) }
      platform.drain()

      resolved.size shouldBe burst
      resolved.map { it.first }.sorted() shouldBe requests

      // Only the first and the last survive coalescing, whatever the burst size.
      platform.issued.map { it.position } shouldBe listOf(requests.first(), requests.last())
      resolved.filter { it.second == SeekResolution.Landed }.map { it.first } shouldBe
        listOf(requests.first(), requests.last())
    }
  }

  // Seeking from inside a completion is the pattern seekTo's KDoc names, and the superseded branch
  // is the one that used to resolve the outgoing request twice while dropping the incoming one.
  @Test
  fun `a request issued from inside a superseded resolution survives`() {
    lateinit var reentrant: SeekChase
    var seekOnce = true

    reentrant =
      SeekChase(
        platformSeek = platform,
        isReady = { platform.isReady },
        onResolved = { position, resolution ->
          resolved += position to resolution
          if (resolution == SeekResolution.Superseded && seekOnce) {
            seekOnce = false
            reentrant.request(99.milliseconds, SeekAccuracy.Exact)
          }
        },
      )

    reentrant.request(10.milliseconds, SeekAccuracy.Exact)
    reentrant.request(20.milliseconds, SeekAccuracy.Exact)
    reentrant.request(30.milliseconds, SeekAccuracy.Exact)
    platform.drain()

    resolved.map { it.first }.sorted() shouldBe
      listOf(10.milliseconds, 20.milliseconds, 30.milliseconds, 99.milliseconds)
    // The request made from the callback is the one the platform ends on, not the one it superseded.
    platform.issued.last().position shouldBe 99.milliseconds
  }

  @Test
  fun `a request arriving while one is in flight waits rather than running beside it`() {
    chase.request(10.milliseconds, SeekAccuracy.Exact)
    chase.request(20.milliseconds, SeekAccuracy.Exact)

    platform.issued.size shouldBe 1
    resolved shouldBe emptyList()

    platform.issued[0].complete()

    platform.issued.map { it.position } shouldBe listOf(10.milliseconds, 20.milliseconds)
    resolved shouldBe listOf(10.milliseconds to SeekResolution.Landed)

    platform.issued[1].complete()
    resolved.size shouldBe 2
  }

  @Test
  fun `a request superseded twice before anything completes still resolves twice`() {
    chase.request(10.milliseconds, SeekAccuracy.Exact)
    chase.request(20.milliseconds, SeekAccuracy.Exact)
    chase.request(30.milliseconds, SeekAccuracy.Exact)
    chase.request(40.milliseconds, SeekAccuracy.Exact)

    resolved shouldBe
      listOf(
        20.milliseconds to SeekResolution.Superseded,
        30.milliseconds to SeekResolution.Superseded,
      )

    platform.drain()

    resolved.size shouldBe 4
    platform.issued.map { it.position } shouldBe listOf(10.milliseconds, 40.milliseconds)
  }

  // The narrowest window in the machine: the request lands between clearing the in-flight seek and
  // dispatching whatever was queued behind it.
  @Test
  fun `a request arriving from inside a completion resolves and dispatches once`() {
    val reentrant = mutableListOf<Duration>()
    lateinit var reentrantChase: SeekChase
    reentrantChase =
      SeekChase(
        platformSeek = platform,
        isReady = { platform.isReady },
        onResolved = { position, resolution ->
          resolved += position to resolution
          if (position == 10.milliseconds && resolution == SeekResolution.Landed) {
            reentrant += position
            reentrantChase.request(90.milliseconds, SeekAccuracy.Exact)
          }
        },
      )

    reentrantChase.request(10.milliseconds, SeekAccuracy.Exact)
    reentrantChase.request(20.milliseconds, SeekAccuracy.Exact)
    platform.issued[0].complete()

    reentrant.size shouldBe 1
    resolved shouldBe
      listOf(
        10.milliseconds to SeekResolution.Landed,
        20.milliseconds to SeekResolution.Superseded,
      )
    platform.issued.map { it.position } shouldBe listOf(10.milliseconds, 90.milliseconds)

    platform.drain()
    resolved.size shouldBe 3
    reentrantChase.isSeeking shouldBe false
  }

  @Test
  fun `a request made before the platform can seek waits for onReady`() {
    platform.isReady = false

    chase.request(10.milliseconds, SeekAccuracy.Exact)
    chase.request(20.milliseconds, SeekAccuracy.Exact)

    platform.issued shouldBe emptyList()
    resolved shouldBe listOf(10.milliseconds to SeekResolution.Superseded)
    chase.isSeeking shouldBe true

    platform.isReady = true
    chase.onReady()
    platform.drain()

    resolved.size shouldBe 2
    platform.issued.map { it.position } shouldBe listOf(20.milliseconds)
  }

  @Test
  fun `release resolves what is outstanding and ignores the callback that arrives afterwards`() {
    chase.request(10.milliseconds, SeekAccuracy.Exact)
    chase.request(20.milliseconds, SeekAccuracy.Exact)

    chase.release()

    resolved shouldBe
      listOf(
        10.milliseconds to SeekResolution.Superseded,
        20.milliseconds to SeekResolution.Superseded,
      )
    chase.isSeeking shouldBe false

    platform.issued[0].complete()
    resolved.size shouldBe 2
  }

  @Test
  fun `a platform calling back twice for one seek resolves it once`() {
    chase.request(10.milliseconds, SeekAccuracy.Exact)
    platform.issued[0].complete()
    platform.issued[0].completeAgain()

    resolved shouldBe listOf(10.milliseconds to SeekResolution.Landed)
  }

  // Release leaves a cancelled seek behind that the platform may still answer. Its callback must
  // not resolve the request that replaced it.
  @Test
  fun `a stale callback does not resolve the request that took its place`() {
    chase.request(10.milliseconds, SeekAccuracy.Exact)
    chase.release()
    chase.request(20.milliseconds, SeekAccuracy.Exact)

    platform.issued[0].completeAgain()

    resolved shouldBe listOf(10.milliseconds to SeekResolution.Superseded)
    chase.isSeeking shouldBe true

    platform.issued[1].complete()
    resolved.size shouldBe 2
  }

  @Test
  fun `the accuracy of the request that survives is the one the platform is given`() {
    chase.request(10.milliseconds, SeekAccuracy.Nearest)
    chase.request(20.milliseconds, SeekAccuracy.Nearest)
    chase.request(30.milliseconds, SeekAccuracy.Exact)
    platform.drain()

    platform.issued.map { it.position to it.accuracy } shouldBe
      listOf(
        10.milliseconds to SeekAccuracy.Nearest,
        30.milliseconds to SeekAccuracy.Exact,
      )
  }

  @Test
  fun `isSeeking holds until the last outstanding request resolves`() {
    chase.isSeeking shouldBe false

    chase.request(10.milliseconds, SeekAccuracy.Exact)
    chase.isSeeking shouldBe true

    chase.request(20.milliseconds, SeekAccuracy.Exact)
    platform.issued[0].complete()
    chase.isSeeking shouldBe true

    platform.issued[1].complete()
    chase.isSeeking shouldBe false
  }
}
