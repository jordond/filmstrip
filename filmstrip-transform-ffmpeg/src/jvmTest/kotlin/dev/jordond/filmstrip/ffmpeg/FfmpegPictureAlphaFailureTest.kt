package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.UNLISTED_ALPHA
import dev.jordond.filmstrip.ffmpeg.internal.alphaSizeReason
import dev.jordond.filmstrip.ffmpeg.internal.pictureAlpha
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.HeifAlpha
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AlphaPlanes
import dev.jordond.filmstrip.test.ColourPictures
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How a decode answers when the child reading an AVIF's alpha item fails, runs long or is interrupted.
 *
 * Each decoder runs a stub in place of ffmpeg that hands every colour read on to the ffmpeg on the path and answers
 * the alpha read, the one that maps a stream, with what the test gives it. So every branch runs on any ffmpeg that
 * decodes an AVIF.
 */
class FfmpegPictureAlphaFailureTest {
  private val scratch = Files.createTempDirectory("filmstrip-alpha-stub").toFile()

  @AfterTest
  fun cleanUp() {
    scratch.deleteRecursively()
  }

  @Test
  fun `an alpha plane of another size than the colour refuses the picture`() {
    val alpha =
      "exec ffmpeg -nostdin -loglevel error -f lavfi -i color=c=gray:s=4x4 -frames:v 1 -c:v png -f image2pipe -"
    val read = stubbed(alpha).decode(ImageSource.ofBytes(STILL))
    val refused = assertIs<OverlayPictureRead.Refused>(read, "$read")
    assertEquals(alphaSizeReason(Size(4, 4), Size(8, 8)), refused.reason)
  }

  @Test
  fun `an alpha read that fails for any other reason refuses the picture`() {
    val read = stubbed("echo 'Invalid data found when processing input' >&2; exit 1").decode(ImageSource.ofBytes(STILL))
    val refused = assertIs<OverlayPictureRead.Refused>(read, "$read")
    assertTrue("Invalid data found" in refused.reason, refused.reason)
  }

  @Test
  fun `an alpha item no stream is listed for draws the picture opaque and names its limit`() {
    val unlisted = "echo \"Stream map '0:#2' matches no streams.\" >&2; exit 1"
    val read = stubbed(unlisted).decode(ImageSource.ofBytes(STILL))
    val drawn = assertIs<OverlayPictureRead.Read<DecodedPicture>>(read, "$read").value
    assertEquals(listOf(UNLISTED_ALPHA), drawn.limits)
    assertTrue(assertIs<StoredSamples.Argb>(drawn.samples).argb.all { it ushr ALPHA_SHIFT == BYTE })
  }

  @Test
  fun `the colour and alpha reads share one deadline`() {
    val decoder = stubbed("sleep 2; exit 1", colour = "sleep 2", timeoutMillis = 3_000)
    val read = assertIs<OverlayPictureRead.Refused>(decoder.decode(ImageSource.ofBytes(STILL)))
    assertTrue("after 3000 ms" in read.reason, read.reason)
  }

  @Test
  fun `an interrupted alpha plane throws and clears the interrupt`() {
    val plane = DecodedPicture(Size(2, 2), StoredSamples.Rgba16(ShortArray(16)), null, emptyList())
    Thread.currentThread().interrupt()
    assertFailsWith<InterruptedException> { pictureAlpha(plane, HeifAlpha(2L, null, false, true, 8)) }
    assertFalse(Thread.currentThread().isInterrupted)
  }

  // A decoder whose ffmpeg runs [alpha] for the alpha read and [colour] ahead of handing any other read on.
  private fun stubbed(
    alpha: String,
    colour: String = ":",
    timeoutMillis: Long = 30_000,
  ): FfmpegPictureDecoder {
    val stub = File(scratch, "ffmpeg")
    stub.writeText(
      """
      |#!/bin/sh
      |for arg in "$@"; do
      |  if [ "${'$'}arg" = "-map" ]; then
      |    $alpha
      |  fi
      |done
      |$colour
      |exec ffmpeg "$@"
      |
      """.trimMargin(),
    )
    stub.setExecutable(true)
    return FfmpegPictureDecoder(stub.absolutePath, timeoutMillis = timeoutMillis)
  }

  private companion object {
    val STILL = alphaStill(ColourPictures.NCLX_UNSPECIFIED.thumbnail(), AlphaPlanes.avif())
    const val ALPHA_SHIFT = 24
    const val BYTE = 0xFF
  }
}
