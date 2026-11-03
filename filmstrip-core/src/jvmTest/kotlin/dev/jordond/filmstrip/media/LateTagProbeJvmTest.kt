package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.PlatformProber
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_DURATION
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_SIZE
import dev.jordond.filmstrip.picture.fixtures.LATE_PNG_SIZE
import dev.jordond.filmstrip.picture.fixtures.LATE_TIFF_SIZE
import dev.jordond.filmstrip.picture.fixtures.PAST_THE_HEAD
import dev.jordond.filmstrip.picture.fixtures.lateTaggedJpeg
import dev.jordond.filmstrip.picture.fixtures.lateTaggedPng
import dev.jordond.filmstrip.picture.fixtures.lateTaggedTiff
import dev.jordond.filmstrip.still.still
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * A still whose orientation tag sits past the first 64 KiB of the file is measured with that tag, from a file on disk
 * and from bytes in memory, for each container the JDK's own readers open.
 */
class LateTagProbeJvmTest {
  @Test
  fun `a tag past the head of a file on disk turns the measured still`() =
    runTest {
      for (still in stills) {
        for (exif in 1..8) {
          val file = temporaryDirectory.resolve("late-$exif.${still.format}").toFile()
          file.writeBytes(still.bytes(exif))

          val info = probe(ImageSource.of(file.path))

          withClue("${still.format} $exif") { info shouldBe expected(still, exif) }
          withClue("${still.format} $exif") { info.video?.displaySize shouldBe displayed(still.size, exif) }
        }
      }
    }

  @Test
  fun `a tag past the head of bytes in memory turns the measured still`() =
    runTest {
      for (still in stills) {
        for (exif in 1..8) {
          val info = probe(ImageSource.ofBytes(still.bytes(exif)))

          withClue("${still.format} $exif") { info shouldBe expected(still, exif) }
        }
      }
    }

  // ImageIO answers a PNG's bounds from its header alone, so a file cut inside its image data still measures, and the
  // tag that was cut off reads as no tag.
  @Test
  fun `a png cut short of its tag measures upright`() =
    runTest {
      val png = stills.first { it.format == "png" }
      val cut = png.bytes(6).copyOf(PAST_THE_HEAD)

      probe(ImageSource.ofBytes(cut)) shouldBe expected(png, EXIF_ORIENTATION_NORMAL)
    }

  private suspend fun probe(image: ImageSource) =
    PlatformProber()
      .probe(
        MediaSource.Image(image, IMAGE_PROBE_DURATION),
      ).shouldBeInstanceOf<ProbeResult.Success>()
      .info

  private fun expected(
    still: Still,
    exif: Int,
  ) = imageMediaInfoOf(still.size, exif, still.format, IMAGE_PROBE_DURATION)

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
      Still("tif", LATE_TIFF_SIZE) { lateTaggedTiff(it) },
    )

  private val temporaryDirectory = createTempDirectory("filmstrip-late-tag")

  private companion object {
    const val FIRST_SWAPPING = 5
  }
}
