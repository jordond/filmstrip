package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.CodestreamSignal

// Reads the video signal an HEVC sequence parameter set carries in its video usability information, following the
// syntax of ITU-T H.265 section 7.3.2.2.1 and Annex E.2.1.

/**
 * The [SpsLayout] of the first sequence parameter set an `hvcC` box holds, its payload running from [start] to the end
 * of [hvcC], or null when the box holds none or the first one does not parse.
 *
 * The arrays are read only as far as the first sequence parameter set, so a broken array after it does not hide it.
 */
internal fun hvccSpsLayout(
  hvcC: ByteView,
  start: Int,
): SpsLayout? = deferring { firstSps(hvcC, start) }?.let { spsLayout(unescape(it)) }

private fun firstSps(
  hvcC: ByteView,
  start: Int,
): ByteArray? {
  hvcC.hvccUnits(start) { type, unit, end -> if (type == SPS_NAL) return hvcC.bytes.copyOfRange(unit, end) }
  return null
}

/**
 * Walks the parameter set arrays of an `hvcC` whose payload starts at [start], in the order they are stored. Calls
 * [visit] with each NAL unit's array type and the positions its bytes run from and up to, after its two byte length.
 *
 * A unit or array header that runs past the end of the view throws [MalformedPicture], after every unit before it has
 * been visited.
 */
internal inline fun ByteView.hvccUnits(
  start: Int,
  visit: (type: Int, unit: Int, end: Int) -> Unit,
) {
  var pos = start + HVCC_ARRAYS
  repeat(u8(pos - 1)) {
    val type = u8(pos) and NAL_TYPE_MASK
    val count = u16(pos + 1)
    pos += 3
    repeat(count) {
      val unit = pos + 2
      pos = span(unit, u16(pos).toLong())
      visit(type, unit, pos)
    }
  }
}

/**
 * The video signal [nal], a whole sequence parameter set NAL unit with its two byte header, carries, or null when it
 * is not a base layer sequence parameter set or does not parse as far as its colour description.
 *
 * Every field before the video usability information is read, and each count, size, depth, offset and index among
 * them is checked against the range H.265 gives it, so a unit that is cut short or breaks the syntax gives null
 * instead of a signal read from the wrong bits. Limits that only a level sets, like the largest picture size, are not
 * checked. A unit without video usability information, or whose information carries no video signal type, gives a
 * signal of all nulls.
 */
internal fun spsSignal(nal: ByteArray): CodestreamSignal? = spsLayout(unescape(nal))?.signal

/**
 * Where the video signal sits in a sequence parameter set's RBSP, as bit positions counted from its first header bit.
 *
 * @property signal The video signal [spsSignal] reads.
 * @property vuiFlag The position of `vui_parameters_present_flag`.
 * @property signalType The position of `video_signal_type_present_flag`, or -1 without video usability information.
 * @property signalTypeEnd The position just past the video signal type and its colour description, [signalType] plus
 * one when the type is absent, or -1 without video usability information.
 * @property videoFormat The `video_format` the video signal type carries, or null without one.
 * @property chromaFormat The unit's own `chroma_format_idc`, numbered as [ChromaFormat] is.
 * @property bitDepth The unit's own luma bit depth, 8 to 16.
 */
internal class SpsLayout(
  val signal: CodestreamSignal,
  val chromaFormat: Int,
  val bitDepth: Int,
  val vuiFlag: Long,
  val signalType: Long = -1,
  val signalTypeEnd: Long = -1,
  val videoFormat: Int? = null,
)

/**
 * The [SpsLayout] of [rbsp], a whole sequence parameter set NAL unit with its emulation prevention bytes dropped, or
 * null when it does not parse as far as [spsSignal] reads.
 */
internal fun spsLayout(rbsp: ByteArray): SpsLayout? = deferring { RbspReader(rbsp).sequenceParameterSet() }

private fun RbspReader.sequenceParameterSet(): SpsLayout {
  if (bits(1) != 0 || bits(6) != SPS_NAL || bits(6) != 0 || bits(3) == 0) malformed()
  skip(4)
  val subLayers = bits(3)
  if (subLayers > MAX_SUB_LAYERS_MINUS1) malformed()
  skip(1)
  profileTierLevel(subLayers)
  ue(MAX_SPS_ID)
  val chromaFormat = ue(MAX_CHROMA_FORMAT)
  if (chromaFormat == ChromaFormat.YUV444) skip(1)
  val width = ue()
  val height = ue()
  if (width == 0 || height == 0) malformed()
  if (flag()) conformanceWindow(width, height, chromaFormat)
  val lumaDepth = ue(MAX_BIT_DEPTH_MINUS8) + 8
  val chromaDepth = ue(MAX_BIT_DEPTH_MINUS8) + 8
  val pocLsbBits = ue(MAX_POC_LSB_MINUS4) + 4
  val maxBuffering = subLayerOrdering(if (flag()) subLayers + 1 else 1)
  val (minCb, ctb) = blockSizes()
  if (width % (1 shl minCb) != 0 || height % (1 shl minCb) != 0) malformed()
  if (flag() && flag()) scalingListData()
  skip(2)
  if (flag()) pcm(lumaDepth, chromaDepth, minCb, ctb)
  shortTermRefPicSets(ue(MAX_SHORT_TERM_SETS), maxBuffering)
  if (flag()) repeat(ue(MAX_LONG_TERM_PICS)) { skip(pocLsbBits + 1) }
  skip(2)
  val vuiFlag = at
  if (flag()) return videoSignal(chromaFormat, lumaDepth, vuiFlag)
  return SpsLayout(NO_SIGNAL, chromaFormat, lumaDepth, vuiFlag)
}

// The cropped area keeps at least one sample in each direction, its offsets counted in chroma samples.
private fun RbspReader.conformanceWindow(
  width: Int,
  height: Int,
  chromaFormat: Int,
) {
  val subWidth = if (chromaFormat == 1 || chromaFormat == 2) 2L else 1L
  val subHeight = if (chromaFormat == 1) 2L else 1L
  if (subWidth * (ue().toLong() + ue()) >= width) malformed()
  if (subHeight * (ue().toLong() + ue()) >= height) malformed()
}

// Gives the last sps_max_dec_pic_buffering_minus1 read, which bounds the size of every short-term set.
private fun RbspReader.subLayerOrdering(count: Int): Int {
  var buffering = 0
  var reorder = 0
  repeat(count) {
    buffering = ue(MAX_BUFFERING_MINUS1).also { if (it < buffering) malformed() }
    reorder = ue(buffering).also { if (it < reorder) malformed() }
    ueLong()
  }
  return buffering
}

// Gives the log2 minimum coding block size and the log2 coding tree block size.
private fun RbspReader.blockSizes(): Pair<Int, Int> {
  val minCb = ue(MAX_CTB_LOG2 - 3) + 3
  val ctb = minCb + ue(MAX_CTB_LOG2 - minCb)
  if (ctb < MIN_CTB_LOG2) malformed()
  val minTb = ue(minCb - 3) + 2
  ue(minOf(ctb, MAX_TB_LOG2) - minTb)
  repeat(2) { ue(ctb - minTb) }
  return minCb to ctb
}

private fun RbspReader.pcm(
  lumaDepth: Int,
  chromaDepth: Int,
  minCb: Int,
  ctb: Int,
) {
  if (bits(4) + 1 > lumaDepth || bits(4) + 1 > chromaDepth) malformed()
  val largest = minOf(ctb, MAX_PCM_LOG2)
  val smallest = ue(largest - 3) + 3
  if (smallest < minOf(minCb, MAX_PCM_LOG2)) malformed()
  ue(largest - smallest)
  skip(1)
}

private fun RbspReader.profileTierLevel(subLayers: Int) {
  skip(GENERAL_PROFILE_BITS + LEVEL_BITS)
  val present = BooleanArray(subLayers * 2) { flag() }
  if (subLayers > 0) skip(2 * (MAX_SUB_LAYERS - subLayers))
  for (layer in 0 until subLayers) {
    if (present[layer * 2]) skip(GENERAL_PROFILE_BITS)
    if (present[layer * 2 + 1]) skip(LEVEL_BITS)
  }
}

private fun RbspReader.scalingListData() {
  for (sizeId in 0 until 4) {
    val step = if (sizeId == 3) 3 else 1
    for (matrixId in 0 until 6 step step) {
      if (!flag()) {
        ue(matrixId / step)
        continue
      }
      if (sizeId > 1) se(MIN_DC_COEFFICIENT, MAX_DC_COEFFICIENT)
      repeat(minOf(MAX_COEFFICIENTS, 1 shl (4 + (sizeId shl 1)))) { se(MIN_DELTA_COEFFICIENT, MAX_DELTA_COEFFICIENT) }
    }
  }
}

// Each set's negative and positive picture order count deltas, kept since a later set may be predicted from it.
private fun RbspReader.shortTermRefPicSets(
  count: Int,
  maxBuffering: Int,
) {
  val sets = ArrayList<Pair<IntArray, IntArray>>(count)
  for (index in 0 until count) {
    sets += if (index != 0 && flag()) predictedSet(sets.last(), maxBuffering) else explicitSet(maxBuffering)
  }
}

private fun RbspReader.explicitSet(maxBuffering: Int): Pair<IntArray, IntArray> {
  val negativeCount = ue(maxBuffering)
  val positiveCount = ue(maxBuffering - negativeCount)
  var poc = 0
  val negative =
    IntArray(negativeCount) {
      poc -= ue(MAX_DELTA_POC_MINUS1) + 1
      skip(1)
      poc
    }
  poc = 0
  val positive =
    IntArray(positiveCount) {
      poc += ue(MAX_DELTA_POC_MINUS1) + 1
      skip(1)
      poc
    }
  return negative to positive
}

// A set predicted from the one before it, derived as H.265 equations 7-61 and 7-62 lay out.
private fun RbspReader.predictedSet(
  reference: Pair<IntArray, IntArray>,
  maxBuffering: Int,
): Pair<IntArray, IntArray> {
  val (s0, s1) = reference
  val sign = if (flag()) -1 else 1
  val deltaRps = sign * (ue(MAX_DELTA_POC_MINUS1) + 1)
  val count = s0.size + s1.size
  val useDelta = BooleanArray(count + 1) { flag() || flag() }
  val negative = mutableListOf<Int>()
  for (j in s1.indices.reversed()) if (s1[j] + deltaRps < 0 && useDelta[s0.size + j]) negative += s1[j] + deltaRps
  if (deltaRps < 0 && useDelta[count]) negative += deltaRps
  for (j in s0.indices) if (s0[j] + deltaRps < 0 && useDelta[j]) negative += s0[j] + deltaRps
  val positive = mutableListOf<Int>()
  for (j in s0.indices.reversed()) if (s0[j] + deltaRps > 0 && useDelta[j]) positive += s0[j] + deltaRps
  if (deltaRps > 0 && useDelta[count]) positive += deltaRps
  for (j in s1.indices) if (s1[j] + deltaRps > 0 && useDelta[s0.size + j]) positive += s1[j] + deltaRps
  if (negative.size + positive.size > maxBuffering) malformed()
  return negative.toIntArray() to positive.toIntArray()
}

private fun RbspReader.videoSignal(
  chromaFormat: Int,
  bitDepth: Int,
  vuiFlag: Long,
): SpsLayout {
  if (flag() && bits(8) == EXTENDED_SAR) skip(32)
  if (flag()) skip(1)
  val signalType = at
  if (!flag()) return SpsLayout(NO_SIGNAL, chromaFormat, bitDepth, vuiFlag, signalType, at)
  val videoFormat = bits(3)
  val fullRange = flag()
  val described = flag()
  val signal =
    if (described) {
      CodestreamSignal(bits(8), bits(8), bits(8), fullRange)
    } else {
      CodestreamSignal(null, null, null, fullRange)
    }
  return SpsLayout(signal, chromaFormat, bitDepth, vuiFlag, signalType, at, videoFormat)
}

/**
 * The RBSP [nal] carries. Every emulation prevention byte, a 3 that follows two zero bytes, is dropped and every other
 * byte is kept in order.
 */
internal fun unescape(nal: ByteArray): ByteArray {
  val out = ByteArray(nal.size)
  var size = 0
  var zeros = 0
  for (byte in nal) {
    if (zeros >= 2 && byte.toInt() == 3) {
      zeros = 0
      continue
    }
    zeros = if (byte.toInt() == 0) zeros + 1 else 0
    out[size++] = byte
  }
  return out.copyOf(size)
}

// Reads an RBSP bit by bit, throwing MalformedPicture on a read past its end or a value past what its field allows.
private class RbspReader(
  private val bytes: ByteArray,
) {
  private val length = bytes.size.toLong() * 8
  private var position = 0L

  val at: Long get() = position

  fun bits(count: Int): Int {
    if (position + count > length) malformed()
    var value = 0
    repeat(count) {
      val at = position.toInt()
      value = (value shl 1) or (bytes[at ushr 3].toInt() ushr (7 - (at and 7)) and 1)
      position++
    }
    return value
  }

  fun flag(): Boolean = bits(1) == 1

  fun skip(count: Int) {
    if (position + count > length) malformed()
    position += count
  }

  fun ue(max: Int = Int.MAX_VALUE): Int {
    val value = ueLong()
    if (value > max) malformed()
    return value.toInt()
  }

  // An exp-Golomb code of up to 32 bits, so any value up to 2^32 - 2.
  fun ueLong(): Long {
    var zeros = 0
    while (bits(1) == 0) {
      zeros++
      if (zeros > MAX_LEADING_ZEROS) malformed()
    }
    return (1L shl zeros) - 1 + bits(zeros).toLong()
  }

  fun se(
    min: Int,
    max: Int,
  ): Int {
    val code = ue()
    val value = if (code and 1 == 1) (code + 1) / 2 else -(code / 2)
    if (value !in min..max) malformed()
    return value
  }
}

private val NO_SIGNAL = CodestreamSignal(null, null, null, null)

// The hvcC fields ahead of its parameter set arrays, the array count being the last of them.
internal const val HVCC_ARRAYS = 23
internal const val NAL_TYPE_MASK = 0x3F
internal const val SPS_NAL = 33

private const val MAX_SUB_LAYERS = 8
private const val MAX_SUB_LAYERS_MINUS1 = 6

// Profile space, tier, profile, 32 compatibility flags, 4 source flags and 44 constraint and reserved bits.
private const val GENERAL_PROFILE_BITS = 88
private const val LEVEL_BITS = 8

private const val MAX_SPS_ID = 15
private const val MAX_CHROMA_FORMAT = 3
private const val MAX_BIT_DEPTH_MINUS8 = 8
private const val MAX_POC_LSB_MINUS4 = 12
private const val MAX_BUFFERING_MINUS1 = 15

private const val MIN_CTB_LOG2 = 4
private const val MAX_CTB_LOG2 = 6
private const val MAX_TB_LOG2 = 5
private const val MAX_PCM_LOG2 = 5

private const val MAX_COEFFICIENTS = 64
private const val MIN_DELTA_COEFFICIENT = -128
private const val MAX_DELTA_COEFFICIENT = 127
private const val MIN_DC_COEFFICIENT = -7
private const val MAX_DC_COEFFICIENT = 247

private const val MAX_SHORT_TERM_SETS = 64
private const val MAX_LONG_TERM_PICS = 32
private const val MAX_DELTA_POC_MINUS1 = 32767

private const val EXTENDED_SAR = 255
private const val MAX_LEADING_ZEROS = 31
