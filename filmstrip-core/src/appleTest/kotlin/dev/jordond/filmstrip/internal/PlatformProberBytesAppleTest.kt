package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.MediaBytesFile
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a probe of a buffer says when the buffer can't be written to a file, and where it waits while one is written.
 */
@OptIn(
  ExperimentalForeignApi::class,
  ExperimentalAtomicApi::class,
  ExperimentalCoroutinesApi::class,
  DelicateCoroutinesApi::class,
)
class PlatformProberBytesAppleTest {
  @Test
  fun `a buffer that cannot be written is unreadable`() =
    runTest {
      val blocked = NSTemporaryDirectory() + "filmstrip-blocked-root-" + NSProcessInfo.processInfo.globallyUniqueString
      NSFileManager.defaultManager.createFileAtPath(blocked, null, null) shouldBe true
      try {
        val failure =
          PlatformProber(blocked).probe(MediaSource.Bytes(ByteArray(16))).shouldBeInstanceOf<ProbeResult.Failure>()

        failure.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldContain "temporary file"
      } finally {
        NSFileManager.defaultManager.removeItemAtPath(blocked, null)
      }
    }

  // The thread pool this test runs on is its own, so it can still report once every Default worker is parked. Default
  // is Grand Central Dispatch here, which grows past the core count, so it takes more probes than its 64 thread width.
  @Test
  fun `probes waiting on a writer leave Default free`() =
    runTest(timeout = 2.minutes) {
      val source = MediaSource.Bytes(ByteArray(16))
      val root = uniqueRoot()
      val elsewhere = uniqueRoot()
      val name = Path(MediaBytesFile.fileFor(elsewhere, source)).name
      val entered = AtomicInt(0)
      val released = AtomicInt(0)
      newFixedThreadPoolContext(2, "held-writer").use { own ->
        withContext(own) {
          val writer =
            launch {
              MediaBytesFile.write(root, source.bytes, name.substringBefore('.'), name.substringAfter('.')) { _, _ ->
                entered.store(1)
                while (released.load() == 0) usleep(POLL_MICROSECONDS)
              }
            }
          try {
            while (entered.load() == 0) delay(POLL)
            val probes = List(PROBES) { launch(Dispatchers.Default) { PlatformProber(root).probe(source) } }
            delay(SETTLE)

            val free = withTimeoutOrNull(FREE_WITHIN) { withContext(Dispatchers.Default) { true } }

            released.store(1)
            free shouldBe true
            (probes + writer).joinAll()
          } finally {
            released.store(1)
          }
        }
      }
      NSFileManager.defaultManager.removeItemAtPath(root, null)
      NSFileManager.defaultManager.removeItemAtPath(elsewhere, null)
    }

  private fun uniqueRoot(): String =
    NSTemporaryDirectory() + "filmstrip-prober-root-" + NSProcessInfo.processInfo.globallyUniqueString

  private companion object {
    const val POLL_MICROSECONDS = 1_000u
    const val PROBES = 128
    val POLL = 10.milliseconds
    val SETTLE = 500.milliseconds
    val FREE_WITHIN = 5.seconds
  }
}
