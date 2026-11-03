package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.CodestreamSignal

// Builds AV1 sequence header OBUs field by field, following the AV1 specification's sequence_header_obu syntax.

/**
 * One operating point of a [SequenceShape].
 *
 * @property displayDelay The `initial_display_delay_minus_1`, or null when the point carries none.
 */
internal data class OperatingPoint(
  val idc: Int = 0,
  val level: Int = 0,
  val tier: Boolean = false,
  val decoderModel: Boolean = false,
  val displayDelay: Int? = null,
)

/**
 * Every field of an AV1 sequence header, each value written as the syntax lays it out.
 *
 * [timing] adds timing info, with [ticksPerPicture] as its `num_ticks_per_picture_minus_1` when that is set, and
 * [decoderModel] decoder model info after it. [subsampling] is only written by a twelve-bit profile 2 header, and
 * [padding] counts the zero bytes after the trailing bits.
 */
internal data class SequenceShape(
  val profile: Int = 0,
  val still: Boolean = true,
  val reduced: Boolean = true,
  val timing: Boolean = false,
  val ticksPerPicture: Long? = null,
  val decoderModel: Boolean = false,
  val initialDisplayDelay: Boolean = false,
  val operatingPoints: List<OperatingPoint> = listOf(OperatingPoint()),
  val frameIds: Boolean = false,
  val orderHint: Boolean = false,
  val chooseScreenContent: Boolean = true,
  val forceScreenContent: Boolean = false,
  val chooseIntegerMv: Boolean = true,
  val highBitDepth: Boolean = false,
  val twelveBit: Boolean = false,
  val mono: Boolean = false,
  val description: Triple<Int, Int, Int>? = null,
  val fullRange: Boolean = true,
  val subsampling: Pair<Int, Int> = 1 to 1,
  val chromaPosition: Int = 0,
  val separateUvDeltaQ: Boolean = false,
  val filmGrain: Boolean = false,
  val padding: Int = 0,
) {
  private val srgb get() = description == Triple(1, 13, 0)

  /**
   * The subsampling the header ends up with, as `color_config` derives it.
   */
  val effectiveSubsampling: Pair<Int, Int>
    get() =
      when {
        mono -> 1 to 1
        srgb -> 0 to 0
        profile == 0 -> 1 to 1
        profile == 1 -> 0 to 0
        twelveBit -> subsampling
        else -> 1 to 0
      }

  /**
   * The signal core's reader gives for this header.
   */
  val signal: CodestreamSignal
    get() =
      CodestreamSignal(description?.first, description?.second, description?.third, fullRange || (srgb && !mono))

  /**
   * This header with [target]'s color description and range in place of its own.
   */
  fun stating(target: CodestreamSignal): SequenceShape =
    copy(description = Triple(target.primaries!!, target.transfer!!, target.matrix!!), fullRange = target.fullRange!!)

  fun payload(): ByteArray {
    val bits = Bits()
    bits.u(3, profile).flag(still).flag(reduced)
    if (reduced) bits.u(5, operatingPoints.first().level) else bits.operatingPoints()
    bits
      .u(4, FRAME_SIZE_BITS - 1)
      .u(4, FRAME_SIZE_BITS - 1)
      .u(FRAME_SIZE_BITS, 5)
      .u(FRAME_SIZE_BITS, 6)
    if (!reduced) {
      bits.flag(frameIds)
      if (frameIds) bits.u(4, 9).u(3, 5)
    }
    bits.flag(true).flag(false).flag(true)
    if (!reduced) bits.tools()
    bits.flag(false).flag(true).flag(true)
    bits.colorConfig()
    bits.flag(filmGrain)
    return bits.rbsp() + ByteArray(padding)
  }

  /**
   * The header as a sequence header OBU whose `obu_size` takes [sizeBytes] bytes, or none when [sizeBytes] is 0.
   */
  fun obu(
    sizeBytes: Int = 1,
    extension: Boolean = false,
  ): ByteArray = obu(SEQUENCE_HEADER, payload(), sizeBytes, extension)

  private fun Bits.operatingPoints() {
    flag(timing)
    if (timing) {
      u(32, 0x00012345L).u(32, 0x0ABCDEF1L).flag(ticksPerPicture != null)
      ticksPerPicture?.let { uvlc(it) }
      flag(decoderModel)
      if (decoderModel) u(5, BUFFER_DELAY_BITS - 1).u(32, 0x00C0FFEEL).u(5, 21).u(5, 11)
    }
    flag(initialDisplayDelay)
    u(5, operatingPoints.size - 1)
    for (point in operatingPoints) {
      u(12, point.idc).u(5, point.level)
      if (point.level > 7) flag(point.tier)
      if (timing && decoderModel) {
        flag(point.decoderModel)
        if (point.decoderModel) u(BUFFER_DELAY_BITS, 0x2A5).u(BUFFER_DELAY_BITS, 0x15A).flag(true)
      }
      if (initialDisplayDelay) {
        flag(point.displayDelay != null)
        point.displayDelay?.let { u(4, it) }
      }
    }
  }

  private fun Bits.tools() {
    flag(true)
      .flag(false)
      .flag(true)
      .flag(false)
      .flag(orderHint)
    if (orderHint) flag(true).flag(false)
    flag(chooseScreenContent)
    if (!chooseScreenContent) flag(forceScreenContent)
    if (chooseScreenContent || forceScreenContent) {
      flag(chooseIntegerMv)
      if (!chooseIntegerMv) flag(true)
    }
    if (orderHint) u(3, 6)
  }

  private fun Bits.colorConfig() {
    flag(highBitDepth)
    if (profile == 2 && highBitDepth) flag(twelveBit)
    if (profile != 1) flag(mono)
    flag(description != null)
    description?.let { (primaries, transfer, matrix) -> u(8, primaries).u(8, transfer).u(8, matrix) }
    if (mono) {
      flag(fullRange)
      return
    }
    if (!srgb) {
      flag(fullRange)
      if (profile == 2 && twelveBit) {
        u(1, subsampling.first)
        if (subsampling.first == 1) u(1, subsampling.second)
      }
      if (effectiveSubsampling == (1 to 1)) u(2, chromaPosition)
    }
    flag(separateUvDeltaQ)
  }

  private fun Bits.uvlc(value: Long) {
    val code = value + 1
    val length = Long.SIZE_BITS - code.countLeadingZeroBits()
    u(length - 1, 0).u(length, code)
  }

  private companion object {
    const val FRAME_SIZE_BITS = 4
    const val BUFFER_DELAY_BITS = 10
  }
}

/**
 * An OBU of [type] around [payload], with an `obu_size` padded to [sizeBytes] bytes, or none when [sizeBytes] is 0.
 */
internal fun obu(
  type: Int,
  payload: ByteArray,
  sizeBytes: Int = 1,
  extension: Boolean = false,
): ByteArray {
  val header = (type shl 3) or (if (extension) 0x04 else 0) or (if (sizeBytes > 0) 0x02 else 0)
  val size =
    ByteArray(sizeBytes) { index ->
      val more = if (index < sizeBytes - 1) 0x80 else 0
      ((payload.size ushr (7 * index) and 0x7F) or more).toByte()
    }
  return byteArrayOf(header.toByte()) + (if (extension) byteArrayOf(0x28) else ByteArray(0)) + size + payload
}

/**
 * A frame OBU whose payload stands in for a coded frame. Nothing reads past its header, so the bytes inside are never
 * decoded.
 */
internal val STAND_IN_FRAME = obu(6, byteArrayOf(0x10, 0, 0, 0, 0x2D, 0x59, 0x08, 0x16, 0x28))
