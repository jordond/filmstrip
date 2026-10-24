package dev.jordond.filmstrip.internal

// Builders for HEVC sequence parameter sets written bit by bit, following ITU-T H.265 section 7.3.2.2.1, and for
// the hvcC boxes that carry them.

/**
 * A bit string written most significant bit first, with the fixed length and exp-Golomb codes H.265 uses.
 */
internal class Bits {
  private val bits = ArrayList<Boolean>()

  val size: Int get() = bits.size

  fun u(
    count: Int,
    value: Long,
  ): Bits = apply { for (bit in count - 1 downTo 0) bits += (value ushr bit) and 1L == 1L }

  fun u(
    count: Int,
    value: Int,
  ): Bits = u(count, value.toLong())

  fun flag(set: Boolean): Bits = u(1, if (set) 1 else 0)

  fun ue(value: Int): Bits = ue(value.toLong())

  fun ue(value: Long): Bits {
    val code = value + 1
    val length = Long.SIZE_BITS - code.countLeadingZeroBits()
    return u(length - 1, 0).u(length, code)
  }

  fun se(value: Int): Bits = ue(if (value > 0) 2 * value - 1 else -2 * value)

  /**
   * The first [count] bits packed into bytes, then the RBSP stop bit when [stop] is set, then zeros to the byte end.
   */
  fun rbsp(
    count: Int = size,
    stop: Boolean = true,
  ): ByteArray {
    val written = bits.subList(0, count) + if (stop) listOf(true) else emptyList()
    return ByteArray((written.size + 7) / 8) { at ->
      (0 until 8)
        .fold(0) { byte, bit -> (byte shl 1) or if (written.getOrElse(at * 8 + bit) { false }) 1 else 0 }
        .toByte()
    }
  }
}

/**
 * [rbsp] with an emulation prevention byte before every byte of 3 or less that follows two zero bytes, as a NAL unit
 * carries it.
 */
internal fun escape(rbsp: ByteArray): ByteArray {
  val out = ArrayList<Byte>()
  var zeros = 0
  for (byte in rbsp) {
    val value = byte.toInt() and 0xFF
    if (zeros >= 2 && value <= 3) {
      out += 3
      zeros = 0
    }
    out += byte
    zeros = if (value == 0) zeros + 1 else 0
  }
  return out.toByteArray()
}

/**
 * The shape of a sequence parameter set, each field set to the value a test needs and the rest to a 64 by 64 Main
 * Still Picture.
 *
 * @property subLayers Whether each sub-layer past the first carries its profile and its level.
 * @property ordering The decoded picture buffering minus 1, reorder count and latency increase plus 1 of each
 * sub-layer, the last one repeated for any sub-layer past the end, and only the last one written without
 * [orderingForAll].
 * @property blockSizes The four log2 coding and transform block size fields, then the two transform hierarchy depths.
 * @property scalingLists Writes the scaling list data present flag and the data, or null for scaling lists off.
 * @property pcm The PCM luma and chroma bit depths minus 1, the log2 smallest PCM block size minus 3 and the log2
 * difference to the largest, or null for no PCM.
 * @property shortTermSets Writes the set count and the sets themselves.
 * @property longTermPocs The least significant picture order count bits of each long-term picture, or null for none.
 * @property vui Writes the video usability information, or null for none.
 */
internal class SpsShape(
  val subLayers: List<Pair<Boolean, Boolean>> = emptyList(),
  val spsId: Int = 0,
  val chromaFormat: Int = 1,
  val width: Int = SIDE,
  val height: Int = SIDE,
  val conformanceWindow: List<Int>? = null,
  val bitDepthMinus8: Int = 0,
  val pocLsbMinus4: Int = 4,
  val orderingForAll: Boolean = false,
  val ordering: List<Triple<Int, Int, Long>> = listOf(Triple(15, 0, 0L)),
  val blockSizes: List<Int> = listOf(0, 3, 0, 3, 0, 0),
  val scalingLists: (Bits.() -> Unit)? = null,
  val pcm: List<Int>? = null,
  val shortTermSets: Bits.() -> Unit = { ue(0) },
  val longTermPocs: List<Int>? = null,
  val vui: (Bits.() -> Unit)? = null,
  val nalType: Int = 33,
  val layer: Int = 0,
) {
  fun bits(): Bits =
    Bits().apply {
      u(1, 0).u(6, nalType).u(6, layer).u(3, 1)
      u(4, 0).u(3, subLayers.size).flag(true)
      profile().u(8, MAIN_LEVEL)
      subLayers.forEach { (hasProfile, hasLevel) -> flag(hasProfile).flag(hasLevel) }
      if (subLayers.isNotEmpty()) repeat(8 - subLayers.size) { u(2, 0) }
      subLayers.forEach { (hasProfile, hasLevel) ->
        if (hasProfile) profile()
        if (hasLevel) u(8, MAIN_LEVEL - 3)
      }
      ue(spsId).ue(chromaFormat)
      if (chromaFormat == 3) flag(false)
      ue(width).ue(height)
      flag(conformanceWindow != null)
      conformanceWindow?.forEach { ue(it) }
      ue(bitDepthMinus8).ue(bitDepthMinus8).ue(pocLsbMinus4).flag(orderingForAll)
      val layers = if (orderingForAll) subLayers.size + 1 else 1
      val first = if (orderingForAll) 0 else ordering.lastIndex
      repeat(layers) {
        val (buffering, reorder, latency) = ordering.getOrElse(first + it) { ordering.last() }
        ue(buffering).ue(reorder).ue(latency)
      }
      blockSizes.forEach { ue(it) }
      flag(scalingLists != null)
      scalingLists?.invoke(this)
      flag(false).flag(true).flag(pcm != null)
      pcm?.let { (lumaDepth, chromaDepth, smallest, difference) ->
        u(4, lumaDepth).u(4, chromaDepth)
        ue(smallest).ue(difference).flag(true)
      }
      shortTermSets()
      flag(longTermPocs != null)
      longTermPocs?.let { pocs ->
        ue(pocs.size)
        pocs.forEach { u(pocLsbMinus4 + 4, it).flag(true) }
      }
      flag(true).flag(false).flag(vui != null)
      vui?.invoke(this)
    }

  /**
   * The whole NAL unit, its stop bit after the last field and its emulation prevention bytes in place.
   */
  fun nal(): ByteArray = escape(bits().rbsp())

  // Profile space and tier, Main Still Picture with its compatibility flag, the source flags and the constraint flags.
  private fun Bits.profile(): Bits = u(8, 3).u(32, 0x10000000L).u(4, 0b1001).u(44, 0)

  private companion object {
    const val SIDE = 64
    const val MAIN_LEVEL = 93
  }
}

// PCM samples as deep as eight bit video, in blocks of 8 to 16 luma samples.
internal val PCM = listOf(7, 7, 0, 1)

/**
 * Scaling list data that alternates copying a reference list, at the furthest one allowed, with explicit coefficients
 * spread over their range, or only the flag that leaves the lists at their defaults when [present] is false.
 *
 * [dc] replaces the first DC coefficient, [coefficient] the first delta coefficient, and [reference] the reference
 * delta of the last 32 by 32 list, which may reach back only one list.
 */
internal fun scalingLists(
  present: Boolean = true,
  dc: Int? = null,
  coefficient: Int? = null,
  reference: Int? = null,
): Bits.() -> Unit =
  {
    flag(present)
    var firstDc = true
    var firstCoefficient = true
    if (present) {
      for (sizeId in 0 until 4) {
        val step = if (sizeId == 3) 3 else 1
        for (matrixId in 0 until 6 step step) {
          if ((sizeId + matrixId / step) % 2 == 0) {
            flag(false).ue(if (sizeId == 3 && reference != null) reference else matrixId / step)
            continue
          }
          flag(true)
          if (sizeId > 1) se(dc?.takeIf { firstDc } ?: if (matrixId % 2 == 0) -7 else 247).also { firstDc = false }
          repeat(minOf(64, 1 shl (4 + sizeId * 2))) {
            se(coefficient?.takeIf { firstCoefficient } ?: ((it * 37 + matrixId) % 256 - 128))
            firstCoefficient = false
          }
        }
      }
    }
  }

/**
 * The video usability information up to its video signal type, with the fields after it left at zero.
 *
 * @param aspect The aspect ratio idc, 255 for an explicit sample aspect ratio, or null for none.
 * @param overscan The overscan appropriate flag, or null for no overscan information.
 * @param fullRange The video full range flag, or null for no video signal type.
 * @param colour The primaries, transfer and matrix of a colour description, or null for none.
 */
internal fun vui(
  aspect: Int? = null,
  overscan: Boolean? = null,
  fullRange: Boolean? = null,
  colour: Triple<Int, Int, Int>? = null,
): Bits.() -> Unit =
  {
    flag(aspect != null)
    aspect?.let { idc ->
      u(8, idc)
      if (idc == 255) u(16, 4).u(16, 3)
    }
    flag(overscan != null)
    overscan?.let { flag(it) }
    flag(fullRange != null)
    if (fullRange != null) {
      u(3, 5).flag(fullRange).flag(colour != null)
      colour?.let { (primaries, transfer, matrix) -> u(8, primaries).u(8, transfer).u(8, matrix) }
    }
    repeat(4) { flag(false) }
  }

/**
 * An `hvcC` box for 4:2:0 eight bit samples holding [units], one array per unit typed from the unit's own header.
 *
 * [lengthSizeMinusOne] is one less than the width in bytes of the length ahead of each unit in the item data, 3 for
 * the four byte lengths most files use.
 */
internal fun hvccOf(
  vararg units: ByteArray,
  lengthSizeMinusOne: Int = 3,
): ByteArray =
  box(
    "hvcC",
    bytes {
      val lengthSize = 0x0C or lengthSizeMinusOne
      raw(ByteArray(22).also { it[0] = 1 }.also { it[16] = 0xFD.toByte() }.also { it[21] = lengthSize.toByte() })
      u8(units.size)
      units.forEach { unit -> u8(0x80 or (unit[0].toInt() ushr 1 and 0x3F)).u16(1).u16(unit.size).raw(unit) }
    },
  )

internal val VPS_NAL = byteArrayOf(0x40, 0x01, 0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte())
internal val PPS_NAL = byteArrayOf(0x44, 0x01, 0xC1.toByte(), 0x71, 0x81.toByte(), 0x12)
