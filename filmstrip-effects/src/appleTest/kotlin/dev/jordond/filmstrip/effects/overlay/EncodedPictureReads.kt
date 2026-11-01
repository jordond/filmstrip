package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.SAMPLE_MASK
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.SIZE
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.TEXEL
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.AppleDisplayP3
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AvifPicture
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeicPicture
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.test.assertNotNull

/**
 * The pixels [bytes] decode to handed over as written rather than through the raster's rewrite, decoded the way the
 * raster decodes them, or why they could not be read.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun writtenPixels(bytes: ByteArray): PictureRead<OverlayPixels> {
  val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
  return data.overlayCodes().map { OverlayPixels(it) }
}

/**
 * The straight sixteen-bit samples [writtenPixels] read back at the centre of [bytes] through the raster's own code
 * reader, or null when they could not be read.
 */
internal fun writtenSamples(bytes: ByteArray): ShortArray? =
  (writtenPixels(bytes) as? PictureRead.Read)?.value?.centreSamples()

/**
 * One read in a determinism case, named by [name], that hands back the samples the raster read at the centre or null
 * when it read none.
 */
internal class OrderedRead(
  val name: String,
  val samples: () -> ShortArray?,
) {
  override fun toString(): String = name
}

/**
 * Every read the determinism cases take, in the order they take them. Each of [EncodedPictures.STILLS], each of
 * [EncodedPictures.WRITTEN_HEICS] handed over as written, then a picture ImageIO writes as each of [written], failing
 * the calling test when ImageIO writes one of those as none.
 */
internal fun orderedReads(written: List<String>): List<OrderedRead> =
  EncodedPictures.STILLS.map { still ->
    OrderedRead(still.name) {
      ImageSource
        .ofBytes(still.bytes)
        .read()
        .orFail(still.name)
        .centreSamples()
    }
  } +
    EncodedPictures.WRITTEN_HEICS.map { heic -> OrderedRead("$heic as written") { writtenSamples(heic.bytes()) } } +
    written.map(::imageIoRead)

/**
 * The read of a [EncodedPictures.SIZE] picture ImageIO writes as [type] in Display P3, failing the calling test when
 * ImageIO writes none.
 */
private fun imageIoRead(type: String): OrderedRead {
  val name = "$type written by ImageIO"
  val bytes = assertNotNull(AppleDisplayP3.picture(type, SIZE, TEXEL), "ImageIO wrote no $type")
  return OrderedRead(name) {
    ImageSource
      .ofBytes(bytes)
      .read()
      .orFail(name)
      .centreSamples()
  }
}

/**
 * Why [reads] did not each read one answer, taken [ORDER_ROUNDS] times forwards and backwards in turn, empty when every
 * read gave the same samples every time.
 */
internal fun orderMisses(reads: List<OrderedRead>): List<String> {
  val answers = reads.associateWith { mutableSetOf<List<Int>>() }
  val misses = mutableListOf<String>()
  repeat(ORDER_ROUNDS) { round ->
    (if (round % 2 == 0) reads else reads.asReversed()).forEach { next ->
      val read = next.samples()
      if (read == null) {
        misses += "$next read no samples"
      } else {
        answers.getValue(next) += read.map { it.toInt() and SAMPLE_MASK }
      }
    }
  }
  answers.filterValues { it.size > 1 }.forEach { (picture, seen) ->
    misses += "$picture read ${seen.size} ways, $seen"
  }
  return misses
}

private const val ORDER_ROUNDS = 24

/**
 * One AVIF or HEIC still, its bytes and every texel Apple's decoder may hand back for it, given the texel this
 * process's decoder read.
 */
internal class Still(
  val name: String,
  val bytes: ByteArray,
  val decodes: (ShortArray?) -> List<ShortArray>,
)

// The picture size, texel, stills and decode bound the encoded picture cases share, and the eight-bit code a
// sixteen-bit sample reads as.
@OptIn(InternalFilmstripApi::class)
internal object EncodedPictures {
  val SIZE = Size(16, 8)
  const val TEXEL = 0xFFC08040.toInt()

  // Each representative still, and HEIC_NCLX_BT709_FULL as a two by two grid of its codestream.
  val STILLS by lazy {
    ColourPictures.RASTER_REPRESENTATIVES.map { still ->
      val name = if (still is AvifPicture) "AVIF $still" else "$still"
      Still(name, still.bytes()) { AppleStillSamples.of(still, it) }
    } +
      ColourPictures.HEIC_NCLX_BT709_FULL.let { heic ->
        Still("$heic grid", heic.gridBytes()) { AppleStillSamples.of(heic, it) }
      }
  }

  // The representative HEICs, which the as written and determinism cases read handed over as written.
  val WRITTEN_HEICS by lazy { ColourPictures.RASTER_REPRESENTATIVES.filterIsInstance<HeicPicture>() }

  // A decode lands off its model, or a JPEG XL off its stored codes, by rounding alone.
  const val TOLERANCE = AppleStillSamples.TOLERANCE
  const val SAMPLE_MASK = 0xFFFF
  const val EIGHT_BIT_STEP = 257f

  fun Short.toCode(): Float = (toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP
}
