@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.LATE_PNG_SIZE
import dev.jordond.filmstrip.internal.LATE_WEBP_SIZE
import dev.jordond.filmstrip.internal.PlatformProber
import dev.jordond.filmstrip.internal.lateTaggedJpeg
import dev.jordond.filmstrip.internal.lateTaggedPng
import dev.jordond.filmstrip.internal.lateTaggedWebp
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_DURATION
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_SIZE
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A still whose orientation tag sits past the first 64 KiB of the file is measured with that tag and its stored bounds,
 * from bytes and from slices of a fetched blob, for each container a browser decodes and a test can write.
 *
 * A browser turns a PNG only for an `eXIf` ahead of its image data and may not turn a WebP at all, so these tags are
 * ones its own bounds may not follow. The stored bounds come from the container either way.
 */
class LateTagProbeWebTest {
  @Test
  fun aTagPastTheHeadTurnsTheMeasuredStill() =
    runTest {
      for (still in stills) {
        for (exif in 1..8) {
          val bytes = still.bytes(exif)
          val url = URL.createObjectURL(blobOf(bytes, "image/${still.format}"))
          try {
            for (image in listOf(ImageSource.ofBytes(bytes), ImageSource.ofUri(url))) {
              val source = MediaSource.Image(image, IMAGE_PROBE_DURATION)

              val info = assertIs<ProbeResult.Success>(PlatformProber().probe(source)).info

              val expected = imageMediaInfoOf(still.size, exif, still.format, IMAGE_PROBE_DURATION)
              assertEquals(expected, info, "${still.format} $exif from $image")
              assertEquals(displayed(still.size, exif), info.video?.displaySize, "${still.format} $exif from $image")
            }
          } finally {
            URL.revokeObjectURL(url)
          }
        }
      }
    }

  private fun displayed(
    stored: Size,
    exif: Int,
  ): Size = if (exif >= FIRST_SWAPPING) Size(stored.height, stored.width) else stored

  private class Still(
    val format: String,
    val size: Size,
    val bytes: (Int) -> ByteArray,
  )

  private val stills =
    listOf(
      Still("png", LATE_PNG_SIZE, ::lateTaggedPng),
      Still("jpeg", IMAGE_PROBE_SIZE, ::lateTaggedJpeg),
      Still("webp", LATE_WEBP_SIZE, ::lateTaggedWebp),
    )

  private companion object {
    const val FIRST_SWAPPING = 5
  }
}
