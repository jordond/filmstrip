package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.ChromaFormat
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.color.IDENTITY_MATRIX
import dev.jordond.filmstrip.picture.bytes.BitWriter
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.bytes.malformed

// Reads and rewrites the color config of an AV1 sequence header, following the syntax of the AV1 Bitstream and
// Decoding Process Specification sections 5.3 and 5.5, and walks the OBUs an AVIF item or av1C carries.

/**
 * An OBU read out of an AVIF item or an `av1C`. Its header starts at [start] and its payload runs from [payload] up
 * to [end], after [sizeBytes] bytes of `obu_size`, zero when the OBU has no size field and runs to the end of its
 * container.
 */
internal class Obu(
  val type: Int,
  val start: Int,
  val payload: Int,
  val end: Int,
  val sizeBytes: Int,
)

/**
 * The OBUs from [start] up to [stop] that come ahead of the first frame, frame header or tile data, in the order they
 * are stored. Only sequence headers, temporal delimiters, metadata and padding come ahead, so the walk stops at the
 * first OBU of any other type.
 *
 * An OBU without a size field runs up to [stop] and is the last. A header or size that runs past [stop], a set
 * forbidden bit or an `obu_size` past 2^32 - 1 throws [MalformedPicture].
 */
internal fun ByteView.leadingObus(
  start: Int,
  stop: Int,
): List<Obu> {
  val view = within(stop)
  val found = mutableListOf<Obu>()
  var pos = start
  while (pos < view.end) {
    val header = view.u8(pos)
    if (header and FORBIDDEN_BIT != 0) malformed()
    val type = header ushr 3 and OBU_TYPE_MASK
    if (type !in LEADING_TYPES) break
    val sizeAt = view.span(pos + 1, if (header and EXTENSION_FLAG != 0) 1L else 0L)
    val obu =
      if (header and SIZE_FLAG != 0) {
        val (size, sizeBytes) = view.leb128(sizeAt)
        Obu(type, pos, sizeAt + sizeBytes, view.span(sizeAt + sizeBytes, size), sizeBytes)
      } else {
        Obu(type, pos, sizeAt, view.end, 0)
      }
    found += obu
    pos = obu.end
  }
  return found
}

/**
 * The [SequenceHeaderLayout] of each sequence header among the [leadingObus] from [start] up to [stop], null for one
 * that does not parse.
 */
internal fun ByteView.sequenceHeaderLayouts(
  start: Int,
  stop: Int,
): List<SequenceHeaderLayout?> =
  leadingObus(start, stop)
    .filter { it.type == SEQUENCE_HEADER }
    .map { sequenceHeaderLayout(bytes.copyOfRange(it.payload, it.end)) }

/**
 * The header and `obu_size` of [obu] for a payload of [payloadSize] bytes, or null when the size does not fit.
 *
 * The header bytes are copied as they were. A size field keeps its width when [payloadSize] fits in it, padded the way
 * LEB128 allows, and otherwise takes the fewest bytes that hold it. An OBU without a size field gets none, since it
 * still runs to the end of its container.
 */
internal fun ByteView.obuHeader(
  obu: Obu,
  payloadSize: Int,
): ByteArray? {
  val header = bytes.copyOfRange(obu.start, obu.payload - obu.sizeBytes)
  if (obu.sizeBytes == 0) return header
  val needed = generateSequence(1) { it + 1 }.first { payloadSize.toLong() ushr (7 * it) == 0L }
  val width = maxOf(needed, obu.sizeBytes)
  if (width > MAX_LEB128_BYTES) return null
  val size =
    ByteArray(width) { index ->
      val more = if (index < width - 1) LEB128_MORE else 0
      ((payloadSize ushr (7 * index) and LEB128_VALUE) or more).toByte()
    }
  return header + size
}

/**
 * Where the color config sits in a sequence header OBU's payload, as bit positions counted from its first bit, with
 * the fields a rewrite has to keep.
 *
 * @property signal The color description and range the header states. Without a color description the primaries,
 * transfer and matrix are null. The range is always read, being implied full for BT.709 primaries with the sRGB
 * transfer and the identity matrix.
 * @property profile The `seq_profile`, 0 to 2.
 * @property twelveBit Whether the samples are twelve bits deep, which only profile 2 signals.
 * @property bitDepth How deep the samples are, 8, 10 or 12.
 * @property mono Whether the header names a monochrome picture.
 * @property subsamplingX The horizontal chroma subsampling, 1 for halved.
 * @property subsamplingY The vertical chroma subsampling, 1 for halved.
 * @property chromaPosition The `chroma_sample_position`, or null when the header carries none.
 * @property description The position of `color_description_present_flag`.
 * @property resume The position just past `color_range` and the subsampling and chroma position bits after it.
 * @property end The position just past `film_grain_params_present`, where the trailing bits start.
 * @property paddingBytes The whole zero bytes after the byte the trailing bits end in.
 */
internal class SequenceHeaderLayout(
  val signal: CodestreamSignal,
  val profile: Int,
  val twelveBit: Boolean,
  val bitDepth: Int,
  val mono: Boolean,
  val subsamplingX: Int,
  val subsamplingY: Int,
  val chromaPosition: Int?,
  val description: Long,
  val resume: Long,
  val end: Long,
  val paddingBytes: Int,
) {
  /**
   * The chroma layout the header states, numbered as [ChromaFormat] is.
   */
  val chromaFormat: Int?
    get() = av1ChromaFormat(mono, subsamplingX == 1, subsamplingY == 1)
}

/**
 * The bit depth AV1's `high_bitdepth` and `twelve_bit` give, 8, 10 or 12.
 */
internal fun av1BitDepth(
  highBitDepth: Boolean,
  twelveBit: Boolean,
): Int =
  when {
    !highBitDepth -> EIGHT_BITS
    !twelveBit -> TEN_BITS
    else -> TWELVE_BITS
  }

/**
 * The chroma layout AV1's `mono_chrome` and subsampling give, numbered as [ChromaFormat] is, or null for chroma
 * subsampled vertically alone, which AV1 has no layout for.
 */
internal fun av1ChromaFormat(
  mono: Boolean,
  subsampledX: Boolean,
  subsampledY: Boolean,
): Int? =
  when {
    mono -> ChromaFormat.MONOCHROME
    subsampledX && subsampledY -> ChromaFormat.YUV420
    subsampledX -> ChromaFormat.YUV422
    subsampledY -> null
    else -> ChromaFormat.YUV444
  }

/**
 * The [SequenceHeaderLayout] of [payload], a sequence header OBU's payload, or null when it does not parse.
 *
 * Every field before the color config is read: the timing and decoder model info, each operating point with its
 * level, tier, decoder model and initial display delay, the frame size, the frame id numbers and the tool flags. A
 * `seq_profile` past 2, a reduced still picture header on a picture that is not still, and trailing bits that are not
 * a one followed by zeros to the end of the payload all give null.
 */
internal fun sequenceHeaderLayout(payload: ByteArray): SequenceHeaderLayout? =
  deferring { BitReader(payload).sequenceHeader() }

/**
 * [payload], a sequence header OBU's payload, with its color config stating [target]'s primaries, transfer, matrix
 * and range, or null when it does not parse, [target] cannot be stated for its samples, or the result does not read
 * back that way.
 *
 * Gives [payload] itself when it already states all four fields of [target]. Otherwise `color_description_present_flag`
 * is set and the description and `color_range` are written, then the subsampling and chroma position the header had. A
 * header that stated the identity matrix gets subsampling bits that keep its unsubsampled samples, or null when its
 * profile cannot signal them.
 *
 * The identity matrix is written only for unsubsampled color samples, the only ones AV1 allows it for, and a
 * monochrome or subsampled header gives null. With BT.709 primaries and the sRGB transfer it is written without a
 * `color_range` or subsampling bits, since AV1 reads that description as full range 4:4:4, and limited range gives
 * null. Every other bit is copied as it was and the trailing bits are written again.
 *
 * The rewritten payload is read back, and must give [target] with its color description at the same bit and the
 * same profile, depth, subsampling and chroma position, so every field before it is unchanged.
 *
 * @param target The signal to state, every field set, each code point in 0 to 255.
 */
internal fun sequenceHeaderStating(
  payload: ByteArray,
  target: CodestreamSignal,
): ByteArray? {
  val layout = sequenceHeaderLayout(payload) ?: return null
  if (layout.signal == target) return payload
  val primaries = target.primaries ?: return null
  val transfer = target.transfer ?: return null
  val matrix = target.matrix ?: return null
  val fullRange = target.fullRange ?: return null
  if (matrix == IDENTITY_MATRIX && layout.chromaFormat != ChromaFormat.YUV444) return null
  val srgb = primaries == BT709_PRIMARIES && transfer == SRGB_TRANSFER && matrix == IDENTITY_MATRIX
  if (srgb && !fullRange) return null
  val out = BitWriter(payload.size + DESCRIPTION_BYTES)
  out.copy(payload, 0, layout.description)
  out.write(1, 1)
  out.write(8, primaries)
  out.write(8, transfer)
  out.write(8, matrix)
  if (!srgb) out.write(1, if (fullRange) 1 else 0)
  if (!srgb && !layout.mono && !out.subsampling(layout)) return null
  out.copy(payload, layout.resume, layout.end)
  out.write(1, 1)
  val rewritten = out.aligned() + ByteArray(layout.paddingBytes)
  val check = sequenceHeaderLayout(rewritten) ?: return null
  val kept =
    check.description == layout.description && check.profile == layout.profile &&
      check.twelveBit == layout.twelveBit && check.mono == layout.mono &&
      check.subsamplingX == layout.subsamplingX && check.subsamplingY == layout.subsamplingY &&
      check.chromaPosition == layout.chromaPosition && check.end - check.resume == layout.end - layout.resume
  return rewritten.takeIf { kept && check.signal == target }
}

// Writes the subsampling bits that give layout's subsampling under its profile and depth, then its chroma position,
// false when the profile cannot signal that subsampling.
private fun BitWriter.subsampling(layout: SequenceHeaderLayout): Boolean {
  val x = layout.subsamplingX
  val y = layout.subsamplingY
  val implied =
    when {
      layout.profile == 0 -> 1 to 1
      layout.profile == 1 -> 0 to 0
      layout.twelveBit -> null
      else -> 1 to 0
    }
  if (implied != null && implied != (x to y)) return false
  if (implied == null) {
    if (x == 0 && y == 1) return false
    write(1, x)
    if (x == 1) write(1, y)
  }
  if (x == 1 && y == 1) write(2, layout.chromaPosition ?: 0)
  return true
}

private fun BitReader.sequenceHeader(): SequenceHeaderLayout {
  val profile = bits(3)
  if (profile > MAX_PROFILE) malformed()
  val still = flag()
  val reduced = flag()
  if (reduced && !still) malformed()
  if (reduced) skip(LEVEL_BITS) else operatingPoints()
  val widthBits = bits(4) + 1
  val heightBits = bits(4) + 1
  skip(widthBits + heightBits)
  if (!reduced && flag()) skip(FRAME_ID_LENGTH_BITS)
  skip(SUPERBLOCK_AND_INTRA_FLAGS)
  if (!reduced) interTools()
  skip(SUPERRES_CDEF_RESTORATION_FLAGS)
  return colorConfig(profile)
}

private fun BitReader.operatingPoints() {
  var decoderModel = false
  var bufferDelayBits = 0
  if (flag()) {
    skip(TIMING_TICK_BITS)
    if (flag()) uvlc()
    decoderModel = flag()
    if (decoderModel) {
      bufferDelayBits = bits(5) + 1
      skip(DECODER_MODEL_BITS)
    }
  }
  val initialDisplayDelay = flag()
  repeat(bits(5) + 1) {
    skip(OPERATING_POINT_IDC_BITS)
    if (bits(LEVEL_BITS) > MAX_LEVEL_WITHOUT_TIER) skip(1)
    if (decoderModel && flag()) skip(2 * bufferDelayBits + 1)
    if (initialDisplayDelay && flag()) skip(4)
  }
}

private fun BitReader.interTools() {
  skip(COMPOUND_WARP_FILTER_FLAGS)
  val orderHint = flag()
  if (orderHint) skip(2)
  val screenContent = if (flag()) SELECT_SCREEN_CONTENT_TOOLS else bits(1)
  if (screenContent > 0 && !flag()) skip(1)
  if (orderHint) skip(3)
}

private fun BitReader.colorConfig(profile: Int): SequenceHeaderLayout {
  val highBitDepth = flag()
  val twelveBit = profile == 2 && highBitDepth && flag()
  val mono = profile != 1 && flag()
  val description = at
  val described = flag()
  val primaries = if (described) bits(8) else null
  val transfer = if (described) bits(8) else null
  val matrix = if (described) bits(8) else null
  val srgb = primaries == BT709_PRIMARIES && transfer == SRGB_TRANSFER && matrix == IDENTITY_MATRIX
  val fullRange = (srgb && !mono) || flag()
  val (x, y) =
    when {
      mono -> 1 to 1
      srgb -> 0 to 0
      profile == 0 -> 1 to 1
      profile == 1 -> 0 to 0
      twelveBit -> bits(1).let { first -> first to if (first == 1) bits(1) else 0 }
      else -> 1 to 0
    }
  val chromaPosition = if (!mono && !srgb && x == 1 && y == 1) bits(2) else null
  val resume = at
  if (!mono) skip(1)
  skip(1)
  val end = at
  if (!flag()) malformed()
  while (at % 8 != 0L) if (flag()) malformed()
  val padding = remainingBytes()
  if (padding.any { it != 0.toByte() }) malformed()
  val signal = CodestreamSignal(primaries, transfer, matrix, fullRange)
  return SequenceHeaderLayout(
    signal = signal,
    profile = profile,
    twelveBit = twelveBit,
    bitDepth = av1BitDepth(highBitDepth, twelveBit),
    mono = mono,
    subsamplingX = x,
    subsamplingY = y,
    chromaPosition = chromaPosition,
    description = description,
    resume = resume,
    end = end,
    paddingBytes = padding.size,
  )
}

// An unsigned variable length code, whose value is not needed.
private fun BitReader.uvlc() {
  var zeros = 0
  while (!flag()) zeros++
  if (zeros < UVLC_MAX_ZEROS) skip(zeros)
}

// A LEB128 value of up to eight bytes with how many bytes it takes, throwing past 2^32 - 1.
private fun ByteView.leb128(at: Int): Pair<Long, Int> {
  var value = 0L
  for (index in 0 until MAX_LEB128_BYTES) {
    val byte = u8(at + index)
    value = value or ((byte and LEB128_VALUE).toLong() shl (7 * index))
    if (byte and LEB128_MORE == 0) {
      if (value > MAX_OBU_SIZE) malformed()
      return value to index + 1
    }
  }
  malformed()
}

// Reads bits most significant first, throwing MalformedPicture on a read past the end.
private class BitReader(
  private val bytes: ByteArray,
) {
  private val length = bytes.size.toLong() * 8
  var at = 0L
    private set

  fun bits(count: Int): Int {
    skip(count)
    var value = 0
    for (position in at - count until at) {
      val index = position.toInt()
      value = (value shl 1) or (bytes[index ushr 3].toInt() ushr (7 - (index and 7)) and 1)
    }
    return value
  }

  fun flag(): Boolean = bits(1) == 1

  fun skip(count: Int) {
    if (count < 0 || at + count > length) malformed()
    at += count
  }

  // The bytes after the one the position sits in, for a position on a byte boundary.
  fun remainingBytes(): ByteArray = bytes.copyOfRange((at / 8).toInt(), bytes.size)
}

internal const val SEQUENCE_HEADER = 1
private const val TEMPORAL_DELIMITER = 2
private const val METADATA = 5
private const val PADDING = 15
private val LEADING_TYPES = setOf(SEQUENCE_HEADER, TEMPORAL_DELIMITER, METADATA, PADDING)

private const val FORBIDDEN_BIT = 0x80
private const val OBU_TYPE_MASK = 0xF
private const val EXTENSION_FLAG = 0x04
private const val SIZE_FLAG = 0x02
private const val MAX_LEB128_BYTES = 8
private const val LEB128_VALUE = 0x7F
private const val LEB128_MORE = 0x80
private const val MAX_OBU_SIZE = 0xFFFFFFFFL

private const val MAX_PROFILE = 2
private const val LEVEL_BITS = 5
private const val MAX_LEVEL_WITHOUT_TIER = 7
private const val OPERATING_POINT_IDC_BITS = 12

// num_units_in_display_tick and time_scale.
private const val TIMING_TICK_BITS = 64

// num_units_in_decoding_tick, buffer_removal_time_length_minus_1 and frame_presentation_time_length_minus_1.
private const val DECODER_MODEL_BITS = 42

// delta_frame_id_length_minus_2 and additional_frame_id_length_minus_1.
private const val FRAME_ID_LENGTH_BITS = 7

// use_128x128_superblock, enable_filter_intra and enable_intra_edge_filter.
private const val SUPERBLOCK_AND_INTRA_FLAGS = 3

// enable_interintra_compound, enable_masked_compound, enable_warped_motion and enable_dual_filter.
private const val COMPOUND_WARP_FILTER_FLAGS = 4

// enable_superres, enable_cdef and enable_restoration.
private const val SUPERRES_CDEF_RESTORATION_FLAGS = 3

private const val SELECT_SCREEN_CONTENT_TOOLS = 2
private const val UVLC_MAX_ZEROS = 32

private const val BT709_PRIMARIES = 1
private const val SRGB_TRANSFER = 13
private const val EIGHT_BITS = 8
private const val TEN_BITS = 10
private const val TWELVE_BITS = 12

// color_description_present_flag's three code points, plus a byte for color_range and the subsampling bits.
private const val DESCRIPTION_BYTES = 4
