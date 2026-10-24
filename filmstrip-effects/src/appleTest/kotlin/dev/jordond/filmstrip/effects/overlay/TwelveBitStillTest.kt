package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.unreadSamples
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AvifColourPictures
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicColourPictures
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Twelve-bit 4:2:0 AVIF and HEIC stills read by the Apple overlay raster, under every matrix
 * [AppleStillSamples.twelveBit] covers and at both ranges.
 *
 * A still [AppleStillSamples.landing] lands reads back at sixteen bits, deeper than eight, and within rounding of its
 * model. Its overlay resolves exactly when the landing is exact and degraded by [unreadMatrix] otherwise, the cause
 * ending on core's figures. A still refused before decoding is unsupported with core's reason or [pastBound], and a
 * HEIC ImageIO hands out no samples for is refused saying so. Every still that misses is one line of the failure, so
 * one run reports all of them.
 */
@OptIn(InternalFilmstripApi::class)
class TwelveBitStillTest {
  @Test
  fun `every twelve bit still reads at sixteen bits where apple's decoder lands it or is refused`() {
    val misses =
      stills().mapNotNull { still ->
        val read = ImageSource.ofBytes(still.bytes).read()
        AppleStillSamples.missOrUnmeasured {
          val samples = (read as? PictureRead.Read)?.value?.centreSamples()
          val models = AppleStillSamples.twelveBit(still.nclx, still.heic, samples)
          val refusal = refusalOf(still.nclx, unsubsampled = false, TWELVE_BITS)
          when {
            refusal != null -> unsupportedMiss(still.name, read, refusal)
            models.isEmpty() -> withheldMiss(still.name, read)
            read !is PictureRead.Read -> "${still.name} came to $read, where Apple's decoder lands it"
            samples == null -> null.also { println("SKIPPED ${still.name}'s samples: the raster reads none back here") }
            else -> sampleMiss(still.name, samples, models)
          }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `an overlay on a still apple's decoder does not read reports the landing`() {
    val misses =
      stills().mapNotNull { still ->
        val resolved = overlayResolution(still.bytes)
        AppleStillSamples.missOrUnmeasured {
          val landed = AppleStillSamples.landing(still.nclx, unsubsampled = false, TWELVE_BITS)
          val expected =
            when {
              landed == null -> "unsupported: ${refusalOf(still.nclx, unsubsampled = false, TWELVE_BITS)}"
              AppleStillSamples.twelveBit(still.nclx, still.heic).isEmpty() -> "unsupported: $WITHHELD"
              landed.exact -> "resolved"
              else -> "degraded: ${listOf(unreadMatrix(landed).message)}"
            }
          val landing = describe(resolved)
          if (landing == expected) null else "${still.name} resolved $landing, where it should be $expected"
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun `a degraded landing names the matrix and ends on core's figures`() {
    val nclx = CicpTag(1, 13, SMPTE_240M, true)
    val landed = checkNotNull(AppleStillSamples.landing(nclx, unsubsampled = false, TWELVE_BITS))
    val message = unreadMatrix(landed).message

    assertTrue(!landed.exact && landed.withinBound, "SMPTE 240M lands through $landed")
    assertTrue(message.contains("matrix ${landed.matrix}"), message)
    assertTrue(message.endsWith("${landed.figures}."), message)
  }

  @Test
  fun `the raster reads the matrices and evaluates the depths the model does`() {
    assertEquals(AppleStillSamples.READABLE_MATRICES, IMAGE_IO_MATRICES)
    assertEquals(AppleStillSamples.EVALUATED_DEPTHS, EVALUATED_DEPTHS)
  }

  private fun unsupportedMiss(
    name: String,
    read: PictureRead<OverlayPixels>,
    refusal: String,
  ): String? {
    val message = (read as? PictureRead.Unsupported)?.message
    return if (message == refusal) null else "$name came to $read, where it is unsupported: $refusal"
  }

  private fun withheldMiss(
    name: String,
    read: PictureRead<OverlayPixels>,
  ): String? {
    val reason = (read as? PictureRead.Refused)?.reason
    return if (reason == SAMPLES_WITHHELD) null else "$name came to $read, where ImageIO hands out none of its samples"
  }

  private fun sampleMiss(
    name: String,
    samples: ShortArray,
    models: List<ShortArray>,
  ): String? {
    val read = samples.take(RGB).map { it.toInt() and SAMPLE_MASK }
    if (read.all { it % EIGHT_BIT_STEP == 0 }) return "$name read back $read, no deeper than eight bits"
    val codes = read.map { it / EIGHT_BIT_STEP.toFloat() }
    val near =
      models.any { model ->
        codes.indices.all { abs(codes[it] - (model[it].toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP.toFloat()) <= TOL }
      }
    return if (near) null else "$name read back $codes, where Apple's decodes of it are ${models.map { it.toList() }}"
  }

  /**
   * A twelve-bit still, its bytes, whether it is a HEIC and the `nclx` it carries.
   */
  private class Still(
    val name: String,
    val bytes: ByteArray,
    val heic: Boolean,
    val nclx: CicpTag,
  )

  private fun stills(): List<Still> =
    AppleStillSamples.TWELVE_BIT_MATRICES.flatMap { matrix ->
      listOf(true, false).flatMap { full ->
        val nclx = CicpTag(1, 13, matrix, full)
        listOf(
          Still("the twelve-bit AVIF $nclx", AvifColourPictures.twelveBit(nclx).bytes(), heic = false, nclx),
          Still("the twelve-bit HEIC $nclx", HeicColourPictures.twelveBit(nclx), heic = true, nclx),
        )
      }
    }

  private companion object {
    const val RGB = 3
    const val SAMPLE_MASK = 0xFFFF
    const val EIGHT_BIT_STEP = 257
    const val SMPTE_240M = 7
    const val TWELVE_BITS = 12
    const val TOL = AppleStillSamples.TOLERANCE
    val WITHHELD = unreadSamples(SAMPLES_WITHHELD)
  }
}
