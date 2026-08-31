package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.test.Test

/**
 * Which callers share a runtime, and which do not.
 *
 * The sharing is what keeps a preview off a second toolchain resolution and a second probe cache,
 * and neither of those is visible in a return value, so it is pinned on the instance itself.
 */
class FfmpegRuntimeTest {
  // Structural equality, so two callers that spelled the same configuration reach one runtime
  // without having to pass the same object around.
  @Test
  fun `an equal config reaches one runtime`() {
    val first = FfmpegRuntime.of(FfmpegConfig(executablePath = SOMEWHERE))
    val second = FfmpegRuntime.of(FfmpegConfig(executablePath = SOMEWHERE))

    first shouldBeSameInstanceAs second
  }

  // A different binary is a different toolchain and a different answer to what it can encode, so
  // the two must not share what either learned.
  @Test
  fun `a different config gets a runtime of its own`() {
    val first = FfmpegRuntime.of(FfmpegConfig(executablePath = SOMEWHERE))
    val second = FfmpegRuntime.of(FfmpegConfig(executablePath = ELSEWHERE))

    first shouldNotBeSameInstanceAs second
  }

  @Test
  fun `an unshared runtime is nobody else's`() {
    val config = FfmpegConfig(executablePath = SOMEWHERE)

    FfmpegRuntime.unshared(config) shouldNotBeSameInstanceAs FfmpegRuntime.of(config)
  }

  // The shared map lives as long as the process, so a caller that keeps spelling fresh
  // configurations drops the one it has gone longest without rather than growing the map forever.
  @Test
  fun `a runtime nobody has asked for lately is dropped once the cap is passed`() {
    val stale = FfmpegConfig(executablePath = STALE)
    val dropped = FfmpegRuntime.of(stale)

    val churn = List(FfmpegRuntime.MAX_RUNTIMES) { FfmpegConfig(executablePath = "$CHURN$it/ffmpeg") }
    val newest = churn.map { FfmpegRuntime.of(it) }.last()

    FfmpegRuntime.of(stale) shouldNotBeSameInstanceAs dropped
    FfmpegRuntime.of(churn.last()) shouldBeSameInstanceAs newest
  }

  private companion object {
    // Never spawned. Nothing here resolves a toolchain, and a path that exists would make the
    // answer depend on what the machine has installed.
    const val SOMEWHERE = "/nonexistent/one/ffmpeg"
    const val ELSEWHERE = "/nonexistent/two/ffmpeg"
    const val STALE = "/nonexistent/stale/ffmpeg"
    const val CHURN = "/nonexistent/churn-"
  }
}
