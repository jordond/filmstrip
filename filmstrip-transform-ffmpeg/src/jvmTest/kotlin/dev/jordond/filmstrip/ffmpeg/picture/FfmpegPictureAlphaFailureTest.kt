package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.alphaSizeReason
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureAlpha
import dev.jordond.filmstrip.ffmpeg.support.alphaStill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.heif.HeifAlpha
import dev.jordond.filmstrip.test.AlphaPlanes
import dev.jordond.filmstrip.test.ColorPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * How a decode answers when the child reading an AVIF's alpha item fails, runs long or is interrupted.
 *
 * Each decoder runs a stub in place of ffmpeg that hands every color read on to the ffmpeg on the path and answers
 * the alpha read, the one that takes the plane to sixteen-bit grey, with what the test gives it. So every branch runs
 * on any ffmpeg that decodes an AVIF.
 */
class FfmpegPictureAlphaFailureTest {
  private val scratch = Files.createTempDirectory("filmstrip-alpha-stub").toFile()

  @AfterTest
  fun cleanUp() {
    scratch.deleteRecursively()
  }

  @Test
  fun `an alpha plane of another size than the color refuses the picture`() {
    val alpha =
      "exec ffmpeg -nostdin -loglevel error -f lavfi -i color=c=gray:s=4x4 -frames:v 1 -c:v png -f image2pipe -"
    val read = stubbed(alpha).decode(ImageSource.ofBytes(STILL))
    val refused = withClue("$read") { read.shouldBeInstanceOf<OverlayPictureRead.Refused>() }
    refused.reason shouldBe alphaSizeReason(Size(4, 4), Size(8, 8))
  }

  @Test
  fun `an alpha read that fails for any other reason refuses the picture`() {
    val read = stubbed("echo 'Invalid data found when processing input' >&2; exit 1").decode(ImageSource.ofBytes(STILL))
    val refused = withClue("$read") { read.shouldBeInstanceOf<OverlayPictureRead.Refused>() }
    withClue(refused.reason) { ("Invalid data found" in refused.reason).shouldBeTrue() }
  }

  @Test
  fun `the color and alpha reads share one deadline`() {
    val decoder = stubbed("sleep 2; exit 1", color = "sleep 2", timeoutMillis = 3_000)
    val read = decoder.decode(ImageSource.ofBytes(STILL)).shouldBeInstanceOf<OverlayPictureRead.Refused>()
    withClue(read.reason) { ("after 3000 ms" in read.reason).shouldBeTrue() }
  }

  @Test
  fun `an interrupted alpha plane throws and clears the interrupt`() {
    val plane = DecodedPicture(Size(2, 2), StoredSamples.Rgba16(ShortArray(16)), null, emptyList())
    Thread.currentThread().interrupt()
    assertFailsWith<InterruptedException> { pictureAlpha(plane, HeifAlpha(2L, null, false, true, 8)) }
    Thread.currentThread().isInterrupted.shouldBeFalse()
  }

  // A decoder whose ffmpeg runs [alpha] for the alpha read and [color] ahead of handing any other read on.
  private fun stubbed(
    alpha: String,
    color: String = ":",
    timeoutMillis: Long = 30_000,
  ): FfmpegPictureDecoder {
    val stub = File(scratch, "ffmpeg")
    stub.writeText(
      """
      |#!/bin/sh
      |for arg in "$@"; do
      |  case "${'$'}arg" in
      |    *gray16le*) $alpha ;;
      |  esac
      |done
      |$color
      |exec ffmpeg "$@"
      |
      """.trimMargin(),
    )
    stub.setExecutable(true)
    return FfmpegPictureDecoder(stub.absolutePath, timeoutMillis = timeoutMillis)
  }

  private companion object {
    val STILL = alphaStill(ColorPictures.NCLX_UNSPECIFIED.thumbnail(), AlphaPlanes.avif())
  }
}
