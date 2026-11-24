package dev.jordond.filmstrip.media3

import android.os.Build
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media3.internal.Media3Driver
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Every driver in a process reads one encoder capability answer.
 *
 * Two separately constructed drivers hand back the same codec lists, instance for instance, which is
 * what says the codec list was walked once rather than once per driver. The answer itself is derived
 * per driver from that read, because whether HDR survives depends on what the driver can draw. It runs
 * on a device because the host stubs answer nothing for MediaCodecList.
 */
class AndroidEncoderCacheTest {
  @Test
  fun twoDriversShareOneCapabilityRead() =
    runTest {
      val first = Media3Driver(prober = UNUSED_PROBER, components = ComponentRegistry.Builder().build()).capabilities()
      val second = Media3Driver(prober = UNUSED_PROBER, components = ComponentRegistry.Builder().build()).capabilities()

      withClue("each driver probed the video encoders for itself") { second.video shouldBeSameInstanceAs first.video }
      withClue("each driver probed the audio encoders for itself") { second.audio shouldBeSameInstanceAs first.audio }
    }

  @Test
  fun aDriverAsksTheDeviceOnceForWhatItReportsAndWhatItRefuses() =
    runTest {
      var asked = 0
      val driver =
        Media3Driver(
          prober = UNUSED_PROBER,
          components = ComponentRegistry.Builder().build(),
          drawsYuvTarget = { true.also { asked++ } },
        )

      driver.capabilities()
      val first = driver.toneMapLimit(emptyList())
      val second = driver.toneMapLimit(emptyList())

      second shouldBe first
      // Below API 29 nothing is drawn, so nothing is asked.
      if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.Q
      ) {
        withClue("the driver asked the device again") { asked shouldBe 1 }
      }
    }

  private companion object {
    // Reading the capabilities never touches a source, so the driver only needs a prober to exist.
    val UNUSED_PROBER = MediaProber { error("the encoder probe must not read a source") }
  }
}
