package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal actual fun bindLiveFeeds(requests: List<LiveFeedRequest>): LiveBinding {
  val listeners = mutableListOf<ServerSocket>()
  try {
    repeat(requests.size) { listeners += ServerSocket(ANY_PORT, ONE_CONNECTION, LOOPBACK) }
  } catch (refused: IOException) {
    listeners.forEach { runCatching { it.close() } }
    return LiveBinding.Refused(refused.message.orEmpty())
  } catch (refused: SecurityException) {
    listeners.forEach { runCatching { it.close() } }
    return LiveBinding.Refused(refused.message.orEmpty())
  }
  return LiveBinding.Bound(
    JvmLiveFeeds(requests.mapIndexed { index, request -> JvmLiveFeed(request, listeners[index]) }),
  )
}

private class JvmLiveFeeds(
  private val feeds: List<JvmLiveFeed>,
) : LiveFeeds {
  private val started = AtomicBoolean(false)

  override fun urlOf(input: InputSource.Live): String = feeds.first { it.request.source === input }.url

  override fun start() {
    if (started.compareAndSet(false, true)) feeds.forEach { it.start() }
  }

  override fun failure(): String? = feeds.firstNotNullOfOrNull { it.failure }

  override fun close() {
    feeds.forEach { it.stop() }
    feeds.forEach { it.await() }
  }
}

private class JvmLiveFeed(
  val request: LiveFeedRequest,
  private val listener: ServerSocket,
) {
  val url = "tcp://$LOOPBACK_NAME:${listener.localPort}"

  @Volatile
  var failure: String? = null
    private set

  @Volatile
  private var stopping = false

  @Volatile
  private var connection: Socket? = null

  private val thread =
    Thread(::run, "filmstrip-ffmpeg-live-${listener.localPort}").apply { isDaemon = true }

  fun start() = thread.start()

  // Closing a socket breaks the accept or the write a feed sits in, which are the only places it waits.
  fun stop() {
    stopping = true
    runCatching { listener.close() }
    runCatching { connection?.close() }
  }

  fun await() {
    if (thread.isAlive) runCatching { thread.join(LIVE_STOP_GRACE_MILLIS) }
  }

  private fun run() {
    try {
      val socket = listener.accept()
      connection = socket
      listener.close()
      if (!stopping) writePlanes(request, socket.getOutputStream(), { stopping }, { readerGone(socket) })
    } catch (failed: IOException) {
      if (!stopping) failure = failed.message ?: failed.toString()
    } catch (failed: Throwable) {
      // A plane that could not be drawn. The graph sees its stream end, so this is what reports it.
      failure = failed.message ?: failed.toString()
    } finally {
      runCatching { listener.close() }
      runCatching { connection?.close() }
    }
  }
}

/**
 * Writes one plane per tick to [output] until [stopping] or until the reader goes away.
 *
 * A failed write returns normally when [readerGone], which is how every run ends, and throws when the reader is still
 * there. A failure drawing a plane or a plane of the wrong size throws.
 */
internal fun writePlanes(
  request: LiveFeedRequest,
  output: OutputStream,
  stopping: () -> Boolean,
  readerGone: () -> Boolean,
) {
  val input = request.source.input
  val chromaBytes = (input.width + 1) / 2 * ((input.height + 1) / 2) * 2
  val bytes = input.width * input.height + if (input.carriesChroma) chromaBytes else 0
  val chroma = ByteArray(if (input.carriesChroma) 0 else chromaBytes) { NEUTRAL_CHROMA }
  var tick = request.startTick
  while (!stopping()) {
    val plane = input.planeAt(tick)
    check(plane.size == bytes) { "A plane of ${input.key} holds ${plane.size} bytes, expected $bytes." }
    try {
      output.write(plane)
      output.write(chroma)
    } catch (failed: IOException) {
      if (stopping() || readerGone()) return
      throw failed
    }
    tick++
  }
}

/**
 * Whether the far end of [socket] has gone, asked after a failed write.
 *
 * The end or a reset of the stream means it has. A reader that is still there sends nothing, so the read runs out of
 * time.
 */
internal fun readerGone(socket: Socket): Boolean =
  try {
    socket.soTimeout = READER_PROBE_MILLIS
    socket.getInputStream().read() == END_OF_STREAM
  } catch (_: SocketTimeoutException) {
    false
  } catch (_: IOException) {
    true
  }

private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
private const val LOOPBACK_NAME = "127.0.0.1"
private const val ANY_PORT = 0
private const val ONE_CONNECTION = 1
private const val NEUTRAL_CHROMA: Byte = -128
private const val LIVE_STOP_GRACE_MILLIS = 1_000L
private const val READER_PROBE_MILLIS = 100
private const val END_OF_STREAM = -1
