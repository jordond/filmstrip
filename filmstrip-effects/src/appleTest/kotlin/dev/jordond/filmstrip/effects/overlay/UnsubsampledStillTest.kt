package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.ycbcrReading
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicIdentityPictures
import dev.jordond.filmstrip.test.ycbcrToRgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Eight and ten-bit 4:4:4 HEIC stills read by the Apple overlay raster under every matrix code point from 0 to 15, and
 * 12 and 13 on BT.2020 and Display P3 primaries too, at both ranges.
 *
 * A still Apple's decoder reads exactly is decoded through its own matrix and lands within [IMAGE_IO_TOLERANCE] of
 * [ycbcrToRgb]. Any other still core reads is restated to the identity matrix and drawn at
 * [AppleStillSamples.evaluated], core's evaluator on its stored samples, and its overlay resolves exactly. A still
 * core refuses is unsupported with core's reason. Every still that misses is one line of the failure.
 */
@OptIn(InternalFilmstripApi::class)
class UnsubsampledStillTest {
  @Test
  fun `every 4 4 4 still lands through its own matrix or core's evaluator or is refused`() {
    val misses =
      stills().mapNotNull { still ->
        val read = ImageSource.ofBytes(still.bytes).read()
        AppleStillSamples.missOrUnmeasured {
          val samples = (read as? PictureRead.Read)?.value?.centreSamples()
          val landed = AppleStillSamples.landing(still.nclx, unsubsampled = true, still.bitDepth)
          val nclx = still.nclx
          val refusal = (ycbcrReading(nclx.matrix, nclx.primaries, nclx.transfer) as? YcbcrReading.Refused)?.reason
          val resolution = describe(overlayResolution(still.bytes))
          when {
            refusal != null -> if (resolution == "unsupported: $refusal") null else "${still.name} resolved $resolution"
            resolution != "resolved" -> "${still.name} resolved $resolution, where it lands exactly"
            samples == null -> null.also { println("SKIPPED ${still.name}'s samples: the raster reads none back here") }
            landed?.exact == true -> decodeMiss(still, samples)
            else -> evaluatedMiss(still, samples)
          }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  private fun decodeMiss(
    still: Still,
    samples: ShortArray,
  ): String? {
    val (y, cb, cr) = still.ycbcr
    val nclx = still.nclx
    val expected = ycbcrToRgb(y, cb, cr, nclx.matrix, nclx.primaries, nclx.transfer, nclx.fullRange, still.bitDepth)
    val codes = samples.take(RGB).map { (it.toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP }
    val near = codes.indices.all { abs(codes[it] - expected[it]) <= IMAGE_IO_TOLERANCE }
    return if (near) null else "${still.name} read back $codes, where its own matrix gives ${expected.toList()}"
  }

  private fun evaluatedMiss(
    still: Still,
    samples: ShortArray,
  ): String? {
    val expected = AppleStillSamples.evaluated(still.nclx, still.bitDepth, still.ycbcr)
    val read = samples.map { it.toInt() and SAMPLE_MASK }
    val near = (0 until RGB).all { abs(read[it] - (expected[it].toInt() and SAMPLE_MASK)) <= SIXTEEN_BIT_ROUNDING }
    val model = expected.map { it.toInt() and SAMPLE_MASK }
    return if (near) null else "${still.name} read back $read, where core's evaluator gives $model"
  }

  /**
   * A 4:4:4 still, its bytes, the `nclx` it carries and its stored samples and their depth.
   */
  private class Still(
    val name: String,
    val bytes: ByteArray,
    val nclx: CicpTag,
    val ycbcr: List<Int>,
    val bitDepth: Int,
  )

  private fun stills(): List<Still> {
    val tags =
      (0..15).map { CicpTag(1, 13, it, true) } +
        listOf(9, 12).flatMap { primaries -> listOf(12, 13).map { CicpTag(primaries, 1, it, true) } }
    return tags.flatMap { tag ->
      listOf(true, false).flatMap { full ->
        val nclx = CicpTag(tag.primaries, tag.transfer, tag.matrix, full)
        listOf(
          Still(
            "the eight-bit 4:4:4 HEIC $nclx",
            HeicIdentityPictures.bytes(nclx),
            nclx,
            HeicIdentityPictures.YCBCR,
            8,
          ),
          Still(
            "the ten-bit 4:4:4 HEIC $nclx",
            HeicIdentityPictures.tenBitBytes(nclx),
            nclx,
            HeicIdentityPictures.TEN_BIT_YCBCR,
            10,
          ),
        )
      }
    }
  }

  private companion object {
    const val RGB = 3
    const val SAMPLE_MASK = 0xFFFF
    const val EIGHT_BIT_STEP = 257
    const val SIXTEEN_BIT_ROUNDING = 1

    // ImageIO's limit, not filmstrip's. It turns YCbCr into whole codes at the picture's depth.
    const val IMAGE_IO_TOLERANCE = 1
  }
}
