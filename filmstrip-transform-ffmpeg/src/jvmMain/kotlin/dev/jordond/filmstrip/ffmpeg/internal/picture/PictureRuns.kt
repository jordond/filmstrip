package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.picture.heif.HeifCodec
import dev.jordond.filmstrip.picture.heif.HeifCodedImage
import java.io.OutputStream
import java.util.IdentityHashMap

/**
 * Coded images that share one decoder configuration, framed as one elementary stream ffmpeg reads with `-f [format]`
 * and decodes to one frame an image, in order.
 *
 * The stream is never held whole. [writeTo] writes [header] and then each image's [framed] writer, which writes
 * straight out of the image's own arrays, and an image named more than once shares one writer.
 *
 * @property format The name of the ffmpeg demuxer that reads the stream.
 */
internal class PictureRun(
  val format: String,
  private val header: (OutputStream) -> Unit,
  private val framed: List<(OutputStream) -> Unit>,
) {
  /**
   * How many images the stream holds.
   */
  val images: Int get() = framed.size

  /**
   * Writes the stream to [out], part by part.
   */
  fun writeTo(out: OutputStream) {
    header(out)
    for (image in framed) image(out)
  }
}

/**
 * The [length] bytes of [bytes] from [offset], one part of a [PictureRun]'s stream.
 */
internal class Span(
  val bytes: ByteArray,
  val offset: Int = 0,
  val length: Int = bytes.size - offset,
)

/**
 * [images] split into runs of the same codec and configuration, in order, each framed by [pictureRun], or null when
 * one of them does not frame.
 *
 * A change of codec or configuration starts a new run, so a run never needs a decoder set up twice.
 */
internal fun pictureRuns(images: List<HeifCodedImage>): List<PictureRun>? {
  val runs = mutableListOf<MutableList<HeifCodedImage>>()
  for (image in images) {
    val last = runs.lastOrNull()?.last()
    if (last != null && last.codec == image.codec && last.configuration.contentEquals(image.configuration)) {
      runs.last() += image
    } else {
      runs += mutableListOf(image)
    }
  }
  return runs.map { pictureRun(it) ?: return null }
}

/**
 * [images], which share the first one's codec and configuration, framed as one elementary stream, or null when a
 * configuration or sample does not parse.
 *
 * HEVC is written as Annex B: every parameter set the `hvcC` arrays hold, then each sample's length prefixed NAL units,
 * each behind a four byte start code. AV1 is written as a low overhead OBU stream: each image is a temporal unit of a
 * temporal delimiter, the `av1C` configOBUs and the sample's OBUs, and a last OBU that runs to the end of its sample
 * without a size field is written with one, since the stream has no sample to end it. JPEG images are written one
 * after another. Each distinct image is checked once, however many times [images] holds it, and the parts it is
 * written from are walked again as it is written rather than held.
 */
internal fun pictureRun(images: List<HeifCodedImage>): PictureRun? {
  val first = images.first()
  val header: (OutputStream) -> Unit
  val frame: (HeifCodedImage) -> ((OutputStream) -> Unit)?
  val format =
    when (first.codec) {
      HeifCodec.Hevc -> {
        val hvcc = first.configuration
        val lengthBytes = hevcParameterSets(hvcc, Parts.UNWRITTEN) ?: return null
        header = { out -> hevcParameterSets(hvcc, Parts(out::write)) }
        frame = { image ->
          val sample = image.sample
          if (hevcUnits(sample, lengthBytes, Parts.UNWRITTEN)) {
            { out ->
              hevcUnits(sample, lengthBytes, Parts(out::write))
            }
          } else {
            null
          }
        }
        HEVC_FORMAT
      }
      HeifCodec.Av1 -> {
        if (first.configuration.size < AV1C_FIELDS) return null
        val unit = listOf(Span(TEMPORAL_DELIMITER)) + (av1Units(first.configuration, AV1C_FIELDS) ?: return null)
        header = {}
        frame = { image -> av1Units(image.sample, 0)?.let { spans -> spansWriter(unit + spans) } }
        AV1_FORMAT
      }
      HeifCodec.Jpeg -> {
        header = {}
        frame = { image -> spansWriter(listOf(Span(image.sample))) }
        JPEG_FORMAT
      }
    }
  val framings = IdentityHashMap<HeifCodedImage, (OutputStream) -> Unit>()
  val framed = images.map { image -> framings.getOrPut(image) { frame(image) ?: return null } }
  return PictureRun(format, header, framed)
}

// Where the parts of a stream go as it is walked, kept to plain ints so a walk boxes nothing.
private fun interface Parts {
  fun part(
    bytes: ByteArray,
    offset: Int,
    length: Int,
  )

  companion object {
    // Parts only checked, never written.
    val UNWRITTEN = Parts { _, _, _ -> }
  }
}

private fun spansWriter(spans: List<Span>): (OutputStream) -> Unit =
  { out -> for (span in spans) out.write(span.bytes, span.offset, span.length) }

// Hands each parameter set [hvcc] lists to [each] behind a start code, giving back how wide its samples' NAL lengths
// are, or null when it does not parse.
private fun hevcParameterSets(
  hvcc: ByteArray,
  each: Parts,
): Int? {
  if (hvcc.size <= HVCC_ARRAY_COUNT) return null
  val lengthBytes = (hvcc[HVCC_LENGTH_SIZE].toInt() and LENGTH_SIZE_MASK) + 1
  if (lengthBytes == UNUSED_LENGTH_BYTES) return null
  var at = HVCC_ARRAY_COUNT + 1
  repeat(hvcc[HVCC_ARRAY_COUNT].toInt() and BYTE) {
    if (at + ARRAY_HEADER > hvcc.size) return null
    val units = hvcc.unsigned(at + 1, 2)
    at += ARRAY_HEADER
    repeat(units) {
      if (at + 2 > hvcc.size) return null
      val length = hvcc.unsigned(at, 2)
      at += 2
      if (at + length > hvcc.size) return null
      each.part(START_CODE, 0, START_CODE.size)
      each.part(hvcc, at, length)
      at += length
    }
  }
  return lengthBytes
}

// Hands each NAL unit of [sample], length prefixed by [lengthBytes] bytes, to [each] behind a start code, false when
// one runs past the sample's end.
private fun hevcUnits(
  sample: ByteArray,
  lengthBytes: Int,
  each: Parts,
): Boolean {
  var at = 0
  while (at < sample.size) {
    if (at + lengthBytes > sample.size) return false
    val length = sample.unsigned(at, lengthBytes)
    at += lengthBytes
    if (length < 0 || length > sample.size - at) return false
    each.part(START_CODE, 0, START_CODE.size)
    each.part(sample, at, length)
    at += length
  }
  return true
}

// The OBUs of [bytes] from [from], or null when one runs past the end. A last OBU with no size field, which runs to the
// end, is written with its header marked as sized and its size behind it.
private fun av1Units(
  bytes: ByteArray,
  from: Int,
): List<Span>? {
  var at = from
  while (at < bytes.size) {
    val header = bytes[at].toInt() and BYTE
    val headerBytes = if (header and OBU_EXTENSION_FLAG == 0) 1 else 2
    if (header and OBU_FORBIDDEN_BIT != 0 || at + headerBytes > bytes.size) return null
    val payload = at + headerBytes
    if (header and OBU_SIZE_FLAG == 0) {
      val sized = byteArrayOf((header or OBU_SIZE_FLAG).toByte()) + bytes.copyOfRange(at + 1, payload)
      val leading = Span(bytes, from, at - from)
      return listOf(leading, Span(sized + leb128(bytes.size - payload)), Span(bytes, payload))
    }
    var size = 0L
    var sizeBytes = 0
    do {
      if (payload + sizeBytes >= bytes.size || sizeBytes == MAX_LEB128_BYTES) return null
      val next = bytes[payload + sizeBytes].toInt() and BYTE
      size = size or ((next and LEB128_VALUE).toLong() shl (LEB128_BITS * sizeBytes))
      sizeBytes++
    } while (next and LEB128_MORE != 0)
    if (size > bytes.size - payload - sizeBytes) return null
    at = payload + sizeBytes + size.toInt()
  }
  return listOf(Span(bytes, from))
}

// [value] as the fewest leb128 bytes that hold it.
private fun leb128(value: Int): ByteArray {
  val bytes = mutableListOf<Byte>()
  var left = value
  do {
    val low = left and LEB128_VALUE
    left = left ushr LEB128_BITS
    bytes += (if (left == 0) low else low or LEB128_MORE).toByte()
  } while (left != 0)
  return bytes.toByteArray()
}

// The big-endian number of [bytes] bytes at [at], negative when four bytes pass an Int.
private fun ByteArray.unsigned(
  at: Int,
  bytes: Int,
): Int {
  var value = 0L
  for (index in at until at + bytes) value = value shl BITS or (this[index].toLong() and BYTE.toLong())
  return if (value > Int.MAX_VALUE) -1 else value.toInt()
}

private val START_CODE = byteArrayOf(0, 0, 0, 1)
private val TEMPORAL_DELIMITER = byteArrayOf(0x12, 0)
private const val HEVC_FORMAT = "hevc"
private const val AV1_FORMAT = "obu"
private const val JPEG_FORMAT = "mjpeg"

// Where lengthSizeMinusOne and numOfArrays sit in an hvcC record, and each array's type and count ahead of its units.
private const val HVCC_LENGTH_SIZE = 21
private const val HVCC_ARRAY_COUNT = 22
private const val ARRAY_HEADER = 3
private const val LENGTH_SIZE_MASK = 3
private const val UNUSED_LENGTH_BYTES = 3

// The av1C fields ahead of its configOBUs.
private const val AV1C_FIELDS = 4

// The bits of an OBU header byte, and how leb128 spends each byte, seven bits of value and one that says more follow.
private const val OBU_FORBIDDEN_BIT = 0x80
private const val OBU_EXTENSION_FLAG = 0x04
private const val OBU_SIZE_FLAG = 0x02
private const val MAX_LEB128_BYTES = 8
private const val LEB128_VALUE = 0x7F
private const val LEB128_MORE = 0x80
private const val LEB128_BITS = 7
private const val BYTE = 0xFF
private const val BITS = 8
