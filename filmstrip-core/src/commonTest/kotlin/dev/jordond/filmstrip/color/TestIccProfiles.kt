package dev.jordond.filmstrip.color

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Builds the ICC profile bytes the picture light tests read.
 *
 * Holds only bytes and the figures they were built from. Every expected color is worked out in the test that reads
 * the profile.
 */
internal object TestIccProfiles {
  /**
   * The colorants of the sRGB profile most pictures embed, in s15Fixed16 steps, red, green and blue in turn.
   */
  val SRGB_COLORANTS = intArrayOf(28578, 14581, 912, 25241, 46981, 6362, 9376, 3972, 46799)

  /**
   * The sRGB curve as a type 3 `para` curve, g, a, b, c and d.
   */
  val SRGB_PARAMS = doubleArrayOf(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045)

  /**
   * Display P3's red, green and blue chromaticities, x then y.
   */
  val P3_PRIMARIES = doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060)

  /**
   * BT.709's red, green and blue chromaticities, x then y, the primaries sRGB shares.
   */
  val BT709_PRIMARIES = doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06)

  /**
   * BT.2020's red, green and blue chromaticities, x then y.
   */
  val BT2020_PRIMARIES = doubleArrayOf(0.708, 0.292, 0.170, 0.797, 0.131, 0.046)

  /**
   * A mixing matrix whose rows sum to under one, so its output stays inside the XYZ encoding's range.
   */
  val MAB_MIX = doubleArrayOf(0.20, 0.15, 0.08, 0.10, 0.30, 0.05, 0.02, 0.04, 0.30)

  val srgb: ByteArray = matrixTrc(SRGB_COLORANTS, para(3, SRGB_PARAMS))

  val srgbTable: ByteArray =
    matrixTrc(SRGB_COLORANTS, curv(IntArray(1024) { sixteenBit(srgbToLinear(it / 1023f).toDouble()) }))

  val gamma22: ByteArray = matrixTrc(SRGB_COLORANTS, gammaCurv(2.2))

  val nudgedSrgb: ByteArray = matrixTrc(SRGB_COLORANTS.copyOf().also { it[0] += 10 }, para(3, SRGB_PARAMS))

  val linearSrgb: ByteArray = matrixTrc(SRGB_COLORANTS, curv(IntArray(0)))

  val displayP3: ByteArray = matrixTrc(d50Colorants(P3_PRIMARIES), para(3, SRGB_PARAMS))

  /**
   * sRGB with colorants adapted to D50 here from BT.709's chromaticities, rather than the figures most embedded sRGB
   * profiles carry, which sit several s15Fixed16 steps away.
   */
  val bradfordSrgb: ByteArray = matrixTrc(d50Colorants(BT709_PRIMARIES), para(3, SRGB_PARAMS))

  /**
   * sRGB with its curve as a sixteen entry `curv` table, each entry on the sRGB curve, so it matches sRGB only at its
   * own entries.
   */
  val sparseSrgbTable: ByteArray =
    matrixTrc(SRGB_COLORANTS, curv(IntArray(16) { sixteenBit(srgbToLinear(it / 15f).toDouble()) }))

  val grey22: ByteArray = profile("GRAY", "XYZ ", listOf("kTRC" to gammaCurv(2.2)))

  /**
   * An `mAB` profile with a gamma 1.8 A curve, a three point lookup table sampling [MAB_MIX], a gamma 1.2 M curve, a
   * matrix that scales each channel and adds an offset, and identity B curves.
   *
   * Every stage between grid points is linear in the table's own input, so a trilinear and a tetrahedral reader agree.
   */
  val mab: ByteArray =
    profile(
      "RGB ",
      "XYZ ",
      listOf(
        "A2B0" to
          mAB(
            aCurves = List(3) { para(0, doubleArrayOf(1.8)) },
            grid = intArrayOf(3, 3, 3),
            sample = { r, g, b -> mix(MAB_MIX, doubleArrayOf(r, g, b)) },
            mCurves = List(3) { para(0, doubleArrayOf(1.2)) },
            matrix = doubleArrayOf(0.9, 0.0, 0.0, 0.0, 0.95, 0.0, 0.0, 0.0, 0.85, 0.01, 0.02, 0.03),
            bCurves = List(3) { curv(IntArray(0)) },
          ),
      ),
    )

  fun matrixTrc(
    colorants: IntArray,
    curve: ByteArray,
  ): ByteArray =
    profile(
      "RGB ",
      "XYZ ",
      listOf(
        "rXYZ" to xyz(colorants, 0),
        "gXYZ" to xyz(colorants, 3),
        "bXYZ" to xyz(colorants, 6),
        "rTRC" to curve,
        "gTRC" to curve,
        "bTRC" to curve,
      ),
    )

  /**
   * An `mAB` tag with three inputs and outputs. [sample] gives the table's value at each grid point, red, green and
   * blue in the range zero to one, and the result is stored in sixteen bits.
   */
  fun mAB(
    aCurves: List<ByteArray>?,
    grid: IntArray?,
    sample: ((Double, Double, Double) -> DoubleArray)?,
    mCurves: List<ByteArray>?,
    matrix: DoubleArray?,
    bCurves: List<ByteArray>,
  ): ByteArray {
    val body = IccWriter()
    body.signature("mAB ")
    body.u32(0)
    body.u8(3)
    body.u8(3)
    body.u16(0)
    val offsets = body.size
    repeat(5) { body.u32(0) }

    fun place(slot: Int) = body.patchU32(offsets + slot * 4, body.size)
    place(0)
    bCurves.forEach { body.aligned(it) }
    if (matrix != null) {
      place(1)
      matrix.forEach { body.s15Fixed16(it) }
      place(2)
      mCurves!!.forEach { body.aligned(it) }
    }
    if (grid != null && sample != null) {
      place(3)
      repeat(16) { body.u8(if (it < 3) grid[it] else 0) }
      body.u8(2)
      repeat(3) { body.u8(0) }
      for (r in 0 until grid[0]) {
        for (g in 0 until grid[1]) {
          for (b in 0 until grid[2]) {
            sample(r / (grid[0] - 1.0), g / (grid[1] - 1.0), b / (grid[2] - 1.0)).forEach { body.u16(sixteenBit(it)) }
          }
        }
      }
      body.pad()
      place(4)
      aCurves!!.forEach { body.aligned(it) }
    }
    return body.bytes()
  }

  /**
   * A `lut8` or `lut16` tag with identity input and output tables and a two point table. [corner] gives the raw
   * stored value at each corner, red, green and blue each zero or one.
   */
  fun legacyLut(
    wide: Boolean,
    corner: (Int, Int, Int) -> IntArray,
  ): ByteArray {
    val body = IccWriter()
    body.signature(if (wide) "mft2" else "mft1")
    body.u32(0)
    body.u8(3)
    body.u8(3)
    body.u8(2)
    body.u8(0)
    listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0).forEach { body.s15Fixed16(it) }
    val entries = if (wide) 2 else 256
    if (wide) {
      body.u16(entries)
      body.u16(entries)
    }
    val write: (Int) -> Unit = if (wide) body::u16 else body::u8
    val top = if (wide) 65535 else 255
    repeat(3) { repeat(entries) { write(it * top / (entries - 1)) } }
    for (r in 0..1) {
      for (g in 0..1) {
        for (b in 0..1) corner(r, g, b).forEach(write)
      }
    }
    repeat(3) { repeat(entries) { write(it * top / (entries - 1)) } }
    return body.bytes()
  }

  fun curv(entries: IntArray): ByteArray =
    IccWriter()
      .apply {
        signature("curv")
        u32(0)
        u32(entries.size)
        entries.forEach { u16(it) }
      }.bytes()

  fun gammaCurv(gamma: Double): ByteArray =
    IccWriter()
      .apply {
        signature("curv")
        u32(0)
        u32(1)
        u16((gamma * 256).roundToInt())
      }.bytes()

  fun para(
    type: Int,
    params: DoubleArray,
  ): ByteArray =
    IccWriter()
      .apply {
        signature("para")
        u32(0)
        u16(type)
        u16(0)
        params.forEach { s15Fixed16(it) }
      }.bytes()

  /**
   * Rounds [value] to the s15Fixed16 step a profile stores it at.
   */
  fun quantized(value: Double): Double = (value * 65536).roundToInt() / 65536.0

  /**
   * Builds a profile of [colorSpace] whose connection space is [pcs], with a D50 white point tag added to [tags].
   */
  fun profile(
    colorSpace: String,
    pcs: String,
    tags: List<Pair<String, ByteArray>>,
  ): ByteArray {
    val all = tags + ("wtpt" to xyz(intArrayOf(63190, 65536, 54061), 0))
    val table = 4 + all.size * 12
    var offset = 128 + table
    val placed =
      all.map { (name, data) ->
        val at = offset
        offset += (data.size + 3) / 4 * 4
        Triple(name, at, data)
      }
    val out = IccWriter()
    out.u32(offset)
    out.u32(0)
    out.u32(0x04300000)
    out.signature("mntr")
    out.signature(colorSpace)
    out.signature(pcs)
    repeat(12) { out.u8(0) }
    out.signature("acsp")
    repeat(24) { out.u8(0) }
    out.u32(0)
    listOf(63190, 65536, 54061).forEach { out.u32(it) }
    repeat(48) { out.u8(0) }
    out.u32(all.size)
    placed.forEach { (name, at, data) ->
      out.signature(name)
      out.u32(at)
      out.u32(data.size)
    }
    placed.forEach { (_, _, data) -> out.aligned(data) }
    return out.bytes()
  }

  /**
   * Converts [primaries] with a D65 white to the D50 colorants a profile stores, adapting with Bradford.
   */
  fun d50Colorants(primaries: DoubleArray): IntArray {
    val toXyz = TestColorMath.rgbToXyz(primaries)
    val adapt = TestColorMath.bradford(TestColorMath.D65, TestColorMath.D50)
    val d50 = TestColorMath.multiply(adapt, toXyz)
    return IntArray(9) { (d50[(it % 3) * 3 + it / 3] * 65536).roundToInt() }
  }

  fun sixteenBit(value: Double): Int = (value * 65535).roundToInt().coerceIn(0, 65535)

  private fun mix(
    matrix: DoubleArray,
    rgb: DoubleArray,
  ): DoubleArray = TestColorMath.apply(matrix, rgb)

  private fun xyz(
    colorants: IntArray,
    from: Int,
  ): ByteArray =
    IccWriter()
      .apply {
        signature("XYZ ")
        u32(0)
        (from until from + 3).forEach { u32(colorants[it]) }
      }.bytes()
}

/**
 * Color matrices worked out from chromaticities, kept apart from the ones the code under test derives.
 */
internal object TestColorMath {
  val D65 = doubleArrayOf(0.3127 / 0.3290, 1.0, (1 - 0.3127 - 0.3290) / 0.3290)
  val D50 = doubleArrayOf(63190 / 65536.0, 1.0, 54061 / 65536.0)

  fun rgbToXyz(primaries: DoubleArray): DoubleArray {
    val columns = DoubleArray(9)
    for (p in 0 until 3) {
      val x = primaries[p * 2]
      val y = primaries[p * 2 + 1]
      columns[p] = x / y
      columns[3 + p] = 1.0
      columns[6 + p] = (1 - x - y) / y
    }
    val scale = apply(invert(columns), D65)
    return DoubleArray(9) { columns[it] * scale[it % 3] }
  }

  fun bradford(
    from: DoubleArray,
    to: DoubleArray,
  ): DoubleArray {
    val cone = doubleArrayOf(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)
    val fromCone = apply(cone, from)
    val toCone = apply(cone, to)
    val diagonal = DoubleArray(9) { if (it % 4 == 0) toCone[it / 3] / fromCone[it / 3] else 0.0 }
    return multiply(invert(cone), multiply(diagonal, cone))
  }

  /**
   * Converts CIELAB to XYZ against the D50 white, by the CIE formula.
   */
  fun labToXyz(
    lightness: Double,
    a: Double,
    b: Double,
  ): DoubleArray {
    val fy = (lightness + 16) / 116
    val f = doubleArrayOf(fy + a / 500, fy, fy - b / 200)
    val delta = 6.0 / 29
    return DoubleArray(3) { D50[it] * if (f[it] > delta) f[it].pow(3) else 3 * delta * delta * (f[it] - 4.0 / 29) }
  }

  fun apply(
    matrix: DoubleArray,
    vector: DoubleArray,
  ): DoubleArray =
    DoubleArray(3) { r -> matrix[r * 3] * vector[0] + matrix[r * 3 + 1] * vector[1] + matrix[r * 3 + 2] * vector[2] }

  fun multiply(
    a: DoubleArray,
    b: DoubleArray,
  ): DoubleArray =
    DoubleArray(9) { i ->
      val r = i / 3
      val c = i % 3
      a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

  fun invert(m: DoubleArray): DoubleArray {
    val det =
      m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[1] * (m[3] * m[8] - m[5] * m[6]) +
        m[2] * (m[3] * m[7] - m[4] * m[6])
    return doubleArrayOf(
      (m[4] * m[8] - m[5] * m[7]) / det,
      (m[2] * m[7] - m[1] * m[8]) / det,
      (m[1] * m[5] - m[2] * m[4]) / det,
      (m[5] * m[6] - m[3] * m[8]) / det,
      (m[0] * m[8] - m[2] * m[6]) / det,
      (m[2] * m[3] - m[0] * m[5]) / det,
      (m[3] * m[7] - m[4] * m[6]) / det,
      (m[1] * m[6] - m[0] * m[7]) / det,
      (m[0] * m[4] - m[1] * m[3]) / det,
    )
  }
}

private class IccWriter {
  private val out = ArrayList<Byte>()

  val size: Int get() = out.size

  fun u8(value: Int) {
    out += value.toByte()
  }

  fun u16(value: Int) {
    u8(value shr 8)
    u8(value)
  }

  fun u32(value: Int) {
    u16(value ushr 16)
    u16(value and 0xFFFF)
  }

  fun s15Fixed16(value: Double) = u32((value * 65536).roundToInt())

  fun signature(name: String) = name.forEach { u8(it.code) }

  fun aligned(data: ByteArray) {
    data.forEach { out += it }
    pad()
  }

  fun pad() {
    while (out.size % 4 != 0) u8(0)
  }

  fun patchU32(
    at: Int,
    value: Int,
  ) {
    for (index in 0 until 4) out[at + index] = (value shr (24 - index * 8)).toByte()
  }

  fun bytes(): ByteArray = out.toByteArray()
}
