package dev.jordond.filmstrip.avfoundation.internal.interop

import dev.jordond.filmstrip.avfoundation.fixture
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMFormatDescriptionGetMediaType
import platform.CoreMedia.kCMMediaType_Audio
import platform.CoreMedia.kCMMediaType_Video
import platform.Foundation.NSURL
import kotlin.test.Test

/**
 * The format hint a passthrough writer input is handed, read off the first track of a fixture clip.
 */
@OptIn(ExperimentalForeignApi::class)
class CmFormatTest {
  @Test
  fun `the first track hands over its own format for video and for audio`() {
    for ((name, mediaType, expected) in CASES) {
      val tracks = AVURLAsset(uRL = NSURL.fileURLWithPath(fixture(name)), options = null).tracksWithMediaType(mediaType)

      val format = withClue(name) { tracks.copyFormatHint().shouldNotBeNull() }
      try {
        CMFormatDescriptionGetMediaType(format) shouldBe expected
      } finally {
        CFRelease(format)
      }
    }
  }

  @Test
  fun `no track and something other than a track hand over no format`() {
    emptyList<Any>().copyFormatHint().shouldBeNull()
    listOf("not a track").copyFormatHint().shouldBeNull()
  }

  private companion object {
    val CASES =
      listOf(
        Triple("apple_export_a.mp4", AVMediaTypeVideo, kCMMediaType_Video),
        Triple("tone_bed_880.mp4", AVMediaTypeAudio, kCMMediaType_Audio),
      )
  }
}
