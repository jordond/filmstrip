package dev.jordond.filmstrip.avfoundation

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.posix.AF_INET
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval

/**
 * Serves [bytes] over plain HTTP on the loopback address, answering the byte range requests AVFoundation streams a
 * remote file with.
 *
 * A source at [url] is one only AVFoundation can read, since nothing else in the prober opens a URL that is not a file.
 */
@OptIn(ExperimentalForeignApi::class)
internal class RangeServer(
  private val bytes: ByteArray,
  name: String,
) : AutoCloseable {
  private val listener = socket(AF_INET, SOCK_STREAM, 0)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Where the served file is reached.
   */
  val url: String

  init {
    check(listener >= 0) { "could not open a socket" }
    val port =
      memScoped {
        val reuse = alloc<IntVar>().apply { value = 1 }
        setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().convert())
        val address = alloc<sockaddr_in>()
        address.sin_len = sizeOf<sockaddr_in>().convert()
        address.sin_family = AF_INET.convert()
        address.sin_port = 0u
        address.sin_addr.s_addr = LOOPBACK
        check(bind(listener, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "could not bind" }
        check(listen(listener, BACKLOG) == 0) { "could not listen" }
        val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        getsockname(listener, address.ptr.reinterpret(), length.ptr)
        swapBytes(address.sin_port.toInt())
      }
    url = "http://127.0.0.1:$port/$name"
    scope.launch {
      while (true) {
        val client = accept(listener, null, null)
        if (client < 0) break
        launch { answer(client) }
      }
    }
  }

  override fun close() {
    shutdown(listener, SHUT_RDWR)
    close(listener)
    scope.cancel()
  }

  private fun answer(client: Int) {
    try {
      memScoped {
        val noSignal = alloc<IntVar>().apply { value = 1 }
        setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, noSignal.ptr, sizeOf<IntVar>().convert())
        // A client that connects and sends nothing would otherwise hold this thread for good.
        val wait =
          alloc<timeval>().apply {
            tv_sec = READ_TIMEOUT_SECONDS.convert()
            tv_usec = 0
          }
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, wait.ptr, sizeOf<timeval>().convert())
      }
      val request = readRequest(client) ?: return
      val range = RANGE.find(request)
      val first = range?.groupValues?.get(1)?.toLong() ?: 0L
      if (first >= bytes.size) {
        sendAll(
          client,
          "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${bytes.size}\r\n\r\n".encodeToByteArray(),
        )
        return
      }
      val last =
        range
          ?.groupValues
          ?.get(2)
          ?.takeIf { it.isNotEmpty() }
          ?.toLong()
          ?.coerceAtMost(bytes.size - 1L)
          ?: (bytes.size - 1L)
      val body = bytes.copyOfRange(first.toInt(), last.toInt() + 1)
      val status = if (range == null) "200 OK" else "206 Partial Content"
      val head =
        buildString {
          append("HTTP/1.1 $status\r\n")
          append("Content-Type: video/mp4\r\n")
          append("Accept-Ranges: bytes\r\n")
          if (range != null) append("Content-Range: bytes $first-$last/${bytes.size}\r\n")
          append("Content-Length: ${body.size}\r\n")
          append("Connection: close\r\n\r\n")
        }
      sendAll(client, head.encodeToByteArray())
      if (!request.startsWith("HEAD")) sendAll(client, body)
    } finally {
      close(client)
    }
  }

  private fun readRequest(client: Int): String? {
    val buffer = ByteArray(REQUEST_LIMIT)
    var filled = 0
    while (filled < buffer.size) {
      val read = buffer.usePinned { recv(client, it.addressOf(filled), (buffer.size - filled).convert(), 0).toInt() }
      if (read <= 0) return null
      filled += read
      val text = buffer.decodeToString(0, filled)
      if (text.contains("\r\n\r\n")) return text
    }
    return null
  }

  private fun sendAll(
    client: Int,
    data: ByteArray,
  ) {
    var sent = 0
    data.usePinned { pinned ->
      while (sent < data.size) {
        val wrote = send(client, pinned.addressOf(sent), (data.size - sent).convert(), 0).toInt()
        if (wrote <= 0) return
        sent += wrote
      }
    }
  }

  private companion object {
    // 127.0.0.1 in network order, on the little endian hosts Apple ships.
    const val LOOPBACK = 0x0100007Fu
    const val BACKLOG = 16
    const val REQUEST_LIMIT = 16 * 1024
    const val READ_TIMEOUT_SECONDS = 10
    val RANGE = Regex("""(?i)range:\s*bytes=(\d+)-(\d*)""")

    fun swapBytes(port: Int): Int = ((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)
  }
}
