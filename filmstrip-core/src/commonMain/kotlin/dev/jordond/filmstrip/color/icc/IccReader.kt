package dev.jordond.filmstrip.color.icc

/**
 * Reads [bytes] as an ICC profile, or returns null when they are not one an RGB or grey picture can carry.
 *
 * Null covers a malformed profile, a device link or abstract profile, and a CMYK or Lab color space.
 */
internal fun parseIccProfile(bytes: ByteArray): IccProfile? =
  try {
    IccReader(bytes).profile()
  } catch (_: MalformedIccException) {
    null
  }

private class MalformedIccException : Exception()

private class IccReader(
  private val bytes: ByteArray,
) {
  private val tags = HashMap<String, IntRange>()

  fun profile(): IccProfile? {
    ensure(bytes.size >= HEADER_SIZE + 4 && signature(MAGIC_OFFSET) == "acsp")
    if (signature(CLASS_OFFSET) in setOf("link", "abst", "nmcl")) return null
    readTagTable()
    return when (signature(COLOR_SPACE_OFFSET)) {
      "GRAY" -> GreyProfile(curveAt(tag("kTRC").first).first)
      "RGB " -> rgbProfile()
      else -> null
    }
  }

  // A2B1 wins over A2B0 and either wins over the matrix and curves, and each one that fails to read falls through to
  // the next.
  private fun rgbProfile(): IccProfile {
    listOfNotNull(tags["A2B1"], tags["A2B0"]).forEach { lut ->
      readOrNull { lutProfile(lut.first) }?.let { return it }
    }
    val colorants =
      listOf("rXYZ", "gXYZ", "bXYZ").flatMap { name ->
        val at = tag(name).first
        ensure(signature(at) == "XYZ ")
        (0 until 3).map { s32(at + 8 + it * 4) }
      }
    val curves = listOf("rTRC", "gTRC", "bTRC").map { curveAt(tag(it).first).first }
    return MatrixTrcProfile(DoubleArray(9) { colorants[it] / S15_FIXED_16_ONE }, curves)
  }

  private fun lutProfile(at: Int): IccProfile {
    ensure(u8(at + 8) == 3 && u8(at + 9) == 3)
    val lab = signature(PCS_OFFSET) == "Lab "
    return when (signature(at)) {
      "mft1" -> legacyLut(at, wide = false, pcs = if (lab) PcsEncoding.Lab else PcsEncoding.Xyz)
      "mft2" -> legacyLut(at, wide = true, pcs = if (lab) PcsEncoding.LegacyLab else PcsEncoding.Xyz)
      "mAB " -> mabLut(at, pcs = if (lab) PcsEncoding.Lab else PcsEncoding.Xyz)
      else -> throw MalformedIccException()
    }
  }

  private fun legacyLut(
    at: Int,
    wide: Boolean,
    pcs: PcsEncoding,
  ): IccProfile {
    val grid = u8(at + 10)
    ensure(grid >= 2)
    val inputEntries = if (wide) u16(at + 48) else 256
    val outputEntries = if (wide) u16(at + 50) else 256
    ensure(inputEntries >= 2 && outputEntries >= 2)
    val width = if (wide) 2 else 1
    var cursor = at + if (wide) 52 else 48
    val inputs = List(3) { IccCurve.Table(samples(cursor + it * inputEntries * width, inputEntries, width)) }
    cursor += 3 * inputEntries * width
    val sampleCount = grid * grid * grid * 3
    val clut = Clut(intArrayOf(grid, grid, grid), samples(cursor, sampleCount, width))
    cursor += sampleCount * width
    val outputs = List(3) { IccCurve.Table(samples(cursor + it * outputEntries * width, outputEntries, width)) }
    return LutProfile(inputs, clut, null, null, outputs, pcs)
  }

  private fun mabLut(
    at: Int,
    pcs: PcsEncoding,
  ): IccProfile {
    val (bOffset, matrixOffset, mOffset, clutOffset, aOffset) = (0 until 5).map { u32(at + 12 + it * 4) }
    ensure(bOffset != 0 && (clutOffset == 0) == (aOffset == 0) && (matrixOffset == 0) == (mOffset == 0))
    val matrix = if (matrixOffset == 0) null else DoubleArray(12) { s32(at + matrixOffset + it * 4) / S15_FIXED_16_ONE }
    return LutProfile(
      aCurves = if (aOffset == 0) null else curvesAt(at + aOffset),
      clut = if (clutOffset == 0) null else mabClut(at + clutOffset),
      mCurves = if (mOffset == 0) null else curvesAt(at + mOffset),
      matrix = matrix,
      bCurves = curvesAt(at + bOffset),
      pcs = pcs,
    )
  }

  private fun mabClut(at: Int): Clut {
    val grid = IntArray(3) { u8(at + it) }
    ensure(grid.all { it >= 2 })
    val width = u8(at + 16)
    ensure(width == 1 || width == 2)
    return Clut(grid, samples(at + 20, grid[0] * grid[1] * grid[2] * 3, width))
  }

  private fun curvesAt(at: Int): List<IccCurve> {
    var cursor = at
    return List(3) {
      val (curve, length) = curveAt(cursor)
      cursor += (length + 3) / 4 * 4
      curve
    }
  }

  private fun curveAt(at: Int): Pair<IccCurve, Int> =
    when (signature(at)) {
      "curv" -> {
        val count = u32(at + 8)
        val curve =
          when (count) {
            0 -> IccCurve.Identity
            1 -> IccCurve.Gamma(u16(at + 12) / 256.0)
            else -> IccCurve.Table(samples(at + 12, count, 2))
          }
        curve to 12 + count * 2
      }
      "para" -> {
        val type = u16(at + 8)
        ensure(type in PARA_PARAM_COUNTS.indices)
        val params = DoubleArray(PARA_PARAM_COUNTS[type]) { s32(at + 12 + it * 4) / S15_FIXED_16_ONE }
        ensure(type == 0 || type >= 3 || params[1] != 0.0)
        IccCurve.Parametric(type, params) to 12 + params.size * 4
      }
      else -> {
        throw MalformedIccException()
      }
    }

  private fun samples(
    at: Int,
    count: Int,
    width: Int,
  ): IccSamples {
    ensure(at >= 0 && count >= 0 && count.toLong() * width <= bytes.size - at)
    return IccSamples(bytes.copyOfRange(at, at + count * width), width)
  }

  // A tag that runs past the profile is skipped rather than failing it, so only a tag the profile needs can fail it.
  private fun readTagTable() {
    val count = u32(HEADER_SIZE)
    ensure(count <= (bytes.size - HEADER_SIZE - 4) / TAG_ENTRY_SIZE)
    for (index in 0 until count) {
      val entry = HEADER_SIZE + 4 + index * TAG_ENTRY_SIZE
      val offset = s32(entry + 4)
      val size = s32(entry + 8)
      if (offset < 0 || size < 0 || offset.toLong() + size > bytes.size) continue
      tags.getOrPut(signature(entry)) { offset until offset + size }
    }
  }

  private inline fun <T : Any> readOrNull(read: () -> T): T? =
    try {
      read()
    } catch (_: MalformedIccException) {
      null
    }

  private fun tag(name: String): IntRange = tags[name] ?: throw MalformedIccException()

  private fun signature(at: Int): String = (0 until 4).map { u8(at + it).toChar() }.joinToString("")

  private fun u8(at: Int): Int {
    ensure(at >= 0 && at < bytes.size)
    return bytes[at].toInt() and 0xFF
  }

  private fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

  private fun s32(at: Int): Int = (u16(at) shl 16) or u16(at + 2)

  private fun u32(at: Int): Int {
    val value = s32(at)
    ensure(value >= 0)
    return value
  }

  private fun ensure(condition: Boolean) {
    if (!condition) throw MalformedIccException()
  }
}

private val PARA_PARAM_COUNTS = intArrayOf(1, 3, 4, 5, 7)

private const val HEADER_SIZE = 128
private const val TAG_ENTRY_SIZE = 12
private const val CLASS_OFFSET = 12
private const val COLOR_SPACE_OFFSET = 16
private const val PCS_OFFSET = 20
private const val MAGIC_OFFSET = 36
