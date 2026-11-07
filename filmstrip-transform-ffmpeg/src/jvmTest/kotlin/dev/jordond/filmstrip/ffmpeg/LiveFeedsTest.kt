package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.startTick
import dev.jordond.filmstrip.ffmpeg.internal.process.LiveBinding
import dev.jordond.filmstrip.ffmpeg.internal.process.LiveFeedRequest
import dev.jordond.filmstrip.ffmpeg.internal.process.LiveFeeds
import dev.jordond.filmstrip.ffmpeg.internal.process.bindLiveFeeds
import dev.jordond.filmstrip.ffmpeg.internal.process.readerGone
import dev.jordond.filmstrip.ffmpeg.internal.process.writePlanes
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What a live feed does on its own socket, with a test standing where ffmpeg would.
 */
class LiveFeedsTest {
  private val opened = mutableListOf<LiveFeeds>()

  @AfterTest
  fun closeFeeds() = opened.forEach { it.close() }

  @Test
  fun `a feed started before anything connects opens on its first tick`() {
    val source = sourceOf { tick -> ByteArray(WIDTH * HEIGHT) { tick.toByte() } }
    val (feeds, url) = bound(source, START)

    feeds.start()
    Thread.sleep(SETTLE.inWholeMilliseconds)

    connect(url).use { socket ->
      val read = socket.getInputStream().readNBytes(FRAME_BYTES * 2)
      read[0] shouldBe START.toByte()
      read[WIDTH * HEIGHT] shouldBe NEUTRAL
      read[FRAME_BYTES - 1] shouldBe NEUTRAL
      read[FRAME_BYTES] shouldBe (START + 1).toByte()
    }
  }

  @Test
  fun `closing during a write leaves no socket and no thread`() {
    val drawn = AtomicInteger()
    val source =
      sourceOf { tick ->
        drawn.incrementAndGet()
        ByteArray(WIDTH * HEIGHT) { tick.toByte() }
      }
    val (feeds, url) = bound(source, START)
    feeds.start()

    // Nothing reads, so the socket's buffer fills and the write blocks with the feed drawing nothing more.
    connect(url).use {
      var last = -1
      val deadline = TimeSource.Monotonic.markNow() + BOUND
      while (last != drawn.get() && deadline.hasNotPassedNow()) {
        last = drawn.get()
        Thread.sleep(SETTLE.inWholeMilliseconds)
      }

      val started = TimeSource.Monotonic.markNow()
      feeds.close()
      (started.elapsedNow() < BOUND) shouldBe true
    }

    awaitNoFeedThreads(url)
    feeds.failure().shouldBeNull()
    assertFailsWith<ConnectException> { connect(url).close() }
  }

  @Test
  fun `closing before anything connects breaks the accept`() {
    val (feeds, url) = bound(sourceOf { ByteArray(WIDTH * HEIGHT) }, START)
    feeds.start()

    feeds.close()

    awaitNoFeedThreads(url)
    feeds.failure().shouldBeNull()
    assertFailsWith<ConnectException> { connect(url).close() }
  }

  @Test
  fun `closing a feed that never started frees its port`() {
    val (feeds, url) = bound(sourceOf { ByteArray(WIDTH * HEIGHT) }, START)

    feeds.close()

    assertFailsWith<ConnectException> { connect(url).close() }
  }

  @Test
  fun `the reader going away is a normal end`() {
    val (feeds, url) = bound(sourceOf { ByteArray(WIDTH * HEIGHT) }, START)
    feeds.start()

    connect(url).use { it.getInputStream().readNBytes(FRAME_BYTES) }

    awaitNoFeedThreads(url)
    feeds.failure().shouldBeNull()
  }

  @Test
  fun `a plane that cannot be drawn fails by name and ends the stream`() {
    val source =
      sourceOf { tick ->
        check(tick < START + 1) { "no plane at tick $tick" }
        ByteArray(WIDTH * HEIGHT)
      }
    val (feeds, url) = bound(source, START)
    feeds.start()

    connect(url).use { socket ->
      socket.getInputStream().readAllBytes().size shouldBe FRAME_BYTES
    }

    awaitNoFeedThreads(url)
    feeds.failure().shouldNotBeNull() shouldContain "no plane at tick ${START + 1}"
  }

  @Test
  fun `a plane of the wrong size fails by name`() {
    val (feeds, url) = bound(sourceOf { ByteArray(1) }, START)
    feeds.start()

    connect(url).use { it.getInputStream().readAllBytes().size shouldBe 0 }

    awaitNoFeedThreads(url)
    feeds.failure().shouldNotBeNull() shouldContain "holds 1 bytes"
  }

  @Test
  fun `a plane that throws an error fails the feed rather than ending it quietly`() {
    val (feeds, url) = bound(sourceOf { throw OutOfMemoryError("no room for a plane") }, START)
    feeds.start()

    connect(url).use { it.getInputStream().readAllBytes().size shouldBe 0 }

    awaitNoFeedThreads(url)
    feeds.failure().shouldNotBeNull() shouldContain "no room for a plane"
  }

  @Test
  fun `a reader resetting its socket is a normal end`() {
    val (feeds, url) = bound(sourceOf { ByteArray(WIDTH * HEIGHT) }, START)
    feeds.start()

    connect(url).use { socket ->
      socket.setSoLinger(true, 0)
      socket.getInputStream().readNBytes(FRAME_BYTES)
    }

    awaitNoFeedThreads(url)
    feeds.failure().shouldBeNull()
  }

  @Test
  fun `a failed write with the reader gone ends the feed with no failure`() {
    val drawn = AtomicInteger()
    val request =
      LiveFeedRequest(
        sourceOf {
          drawn.incrementAndGet()
          ByteArray(WIDTH * HEIGHT)
        },
        START,
      )

    writePlanes(request, failingWrites("Datenübergabe unterbrochen"), { false }, { true })

    drawn.get() shouldBe 1
  }

  @Test
  fun `a failed write with the reader still there fails by the write's own words`() {
    val request = LiveFeedRequest(sourceOf { ByteArray(WIDTH * HEIGHT) }, START)

    assertFailsWith<IOException> {
      writePlanes(request, failingWrites("No buffer space available"), { false }, { false })
    }.message shouldBe "No buffer space available"
  }

  @Test
  fun `a failed write while stopping never asks after the reader`() {
    val request = LiveFeedRequest(sourceOf { ByteArray(WIDTH * HEIGHT) }, START)

    writePlanes(request, failingWrites("Socket closed"), { failedOnce() }, { error("asked after the reader") })
  }

  @Test
  fun `a read running out of time means the reader is still there`() {
    val socket = FakeSocket { throw SocketTimeoutException("Read timed out") }

    readerGone(socket) shouldBe false
    socket.timeout shouldBeGreaterThan 0
  }

  @Test
  fun `the end of the stream means the reader has gone`() {
    readerGone(FakeSocket { -1 }) shouldBe true
  }

  @Test
  fun `a reset means the reader has gone`() {
    readerGone(FakeSocket { throw SocketException("Connection reset") }) shouldBe true
  }

  @Test
  fun `a byte from the reader means it is still there`() {
    readerGone(FakeSocket { 0 }) shouldBe false
  }

  @Test
  fun `a plane of the wrong size throws on its way to a closed reader`() {
    val request = LiveFeedRequest(sourceOf { ByteArray(1) }, START)

    assertFailsWith<IllegalStateException> {
      writePlanes(
        request,
        OutputStream.nullOutputStream(),
        { false },
        { false },
      )
    }.message
      .shouldNotBeNull() shouldContain "holds 1 bytes"
  }

  @Test
  fun `the first tick sits a second ahead of the clock, floored at zero`() {
    val source = { origin: Duration -> InputSource.Live(inputOf { ByteArray(0) }, origin) }

    source(Duration.ZERO).startTick(Duration.ZERO) shouldBe 0
    source(500.milliseconds).startTick(Duration.ZERO) shouldBe 0
    source(3.seconds).startTick(500.milliseconds) shouldBe 60
    source(Duration.ZERO).startTick(1250.milliseconds) shouldBe 6
  }

  private var stopChecks = 0

  // The loop's own check passes, and the one after the failed write reads as a stop.
  private fun failedOnce() = stopChecks++ > 0

  private fun failingWrites(message: String) =
    object : OutputStream() {
      override fun write(byte: Int) = throw IOException(message)
    }

  private class FakeSocket(
    private val read: () -> Int,
  ) : Socket() {
    var timeout = 0

    override fun setSoTimeout(timeout: Int) {
      this.timeout = timeout
    }

    override fun getInputStream(): InputStream =
      object : InputStream() {
        override fun read(): Int = this@FakeSocket.read()
      }
  }

  private fun sourceOf(plane: (Int) -> ByteArray) = InputSource.Live(inputOf(plane), Duration.ZERO)

  private fun inputOf(plane: (Int) -> ByteArray) =
    LiveInput("plane", WIDTH, HEIGHT, RATE, "test", plane, FilmSignal::grainTickOpening)

  private fun bound(
    source: InputSource.Live,
    start: Int,
  ): Pair<LiveFeeds, String> {
    val feeds =
      bindLiveFeeds(listOf(LiveFeedRequest(source, start))).shouldBeInstanceOf<LiveBinding.Bound>().feeds
    opened += feeds
    return feeds to feeds.urlOf(source)
  }

  private fun connect(url: String): Socket = URI(url).let { Socket(it.host, it.port) }

  private fun awaitNoFeedThreads(url: String) {
    val name = "filmstrip-ffmpeg-live-${URI(url).port}"
    val deadline = TimeSource.Monotonic.markNow() + BOUND
    while (Thread.getAllStackTraces().keys.any { it.name == name } && deadline.hasNotPassedNow()) {
      Thread.sleep(SETTLE.inWholeMilliseconds)
    }
    Thread.getAllStackTraces().keys.count { it.name == name } shouldBe 0
  }

  private companion object {
    const val WIDTH = 64
    const val HEIGHT = 36
    const val FRAME_BYTES = WIDTH * HEIGHT * 3 / 2
    const val RATE = 24.0
    const val START = 7
    const val NEUTRAL: Byte = -128
    val SETTLE = 50.milliseconds
    val BOUND = 5.seconds
  }
}
