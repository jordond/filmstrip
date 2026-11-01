package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.test.encodesHdr
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The video encoders VideoToolbox lists on macOS and on an iPhone, every one of them hardware, the HDR encoder both
 * advertise and the Display P3 HEIC ImageIO writes on both.
 *
 * The `appleHardwareTest` source set is shared by `macosArm64Test` and `iosArm64Test`. The simulator reports no
 * hardware flag, which AppleSimulatorEncoderTest holds on that target along with its own HDR and HEIC answers.
 * The HDR and HEIC pins are unmeasured on an iPhone, so the first device run confirms them.
 */
class AppleHardwareEncoderTest {
  @OptIn(ExperimentalNativeApi::class)
  @Test
  fun `every listed video encoder is hardware`() =
    runTest(timeout = TIMEOUT) {
      val result = withContext(Dispatchers.Default) { Filmstrip { avFoundationBackend() }.capabilities() }
      val video = assertIs<CapabilitiesResult.Success>(result).capabilities.video

      assertTrue(video.isNotEmpty(), "expected at least one video encoder")
      if (Platform.osFamily == OsFamily.IOS && !reportsHardware()) {
        assertTrue(
          video.all { it.isHardwareAccelerated != false },
          "VideoToolbox reports no hardware flag below iOS 17.4, so a listed encoder is unknown, not software: $video",
        )
      } else {
        assertTrue(video.all { it.isHardwareAccelerated == true }, "VideoToolbox listed a software encoder in $video")
      }
    }

  @Test
  fun `the device advertises an hdr encoder`() =
    runTest(timeout = TIMEOUT) {
      val encodesHdr = withContext(Dispatchers.Default) { Filmstrip { avFoundationBackend() }.encodesHdr() }
      assertTrue(encodesHdr, "the device advertises no HDR encoder")
    }

  @Test
  fun `imageio writes a display p3 heic`() {
    assertTrue(imageIoWrites("public.heic"), "ImageIO wrote no Display P3 HEIC")
  }

  // VideoToolbox's limit. The session key that says whether an encoder is hardware arrived in iOS 17.4.
  @OptIn(ExperimentalForeignApi::class)
  private fun reportsHardware(): Boolean =
    NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
      cValue<NSOperatingSystemVersion> {
        majorVersion = HARDWARE_FLAG_MAJOR
        minorVersion = HARDWARE_FLAG_MINOR
        patchVersion = 0
      },
    )

  private companion object {
    val TIMEOUT = 5.minutes
    const val HARDWARE_FLAG_MAJOR = 17L
    const val HARDWARE_FLAG_MINOR = 4L
  }
}
