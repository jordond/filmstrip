package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeifStills

/**
 * A 32 by 32 AVIF painted in 64 blocks of four by four texels, each block one luma, blue difference and red
 * difference sample, so one picture holds mid-range and saturated colours under whatever `nclx` it is given.
 *
 * Block `b` sits at column `b % 8` and row `b / 8`. Its eight-bit samples are luma `32 + 56 * (b % 4)`, blue difference
 * `16 + 74 * ((b / 4) % 4)` and red difference `16 + 74 * (b / 16)`, times 4 plus 1 at ten bits and times 16 plus 5 at
 * twelve. A texel one in from a block's top left corner reads only that block's chroma on 4:2:0 samples too.
 *
 * @property name What the picture is, for test messages.
 * @property bitDepth The bits per sample the codestream stores.
 * @property unsubsampled Whether the codestream is 4:4:4 rather than 4:2:0.
 */
@OptIn(InternalFilmstripApi::class)
internal class MatrixAvif private constructor(
  val name: String,
  val bitDepth: Int,
  val unsubsampled: Boolean,
  private val codestream: String,
  private val configuration: String,
) {
  /**
   * The luma, blue difference and red difference samples of [block].
   */
  fun samples(block: Int): IntArray {
    val eightBit = intArrayOf(32 + 56 * (block % 4), 16 + 74 * ((block / 4) % 4), 16 + 74 * (block / 16))
    return when (bitDepth) {
      TEN_BITS -> IntArray(3) { eightBit[it] * 4 + 1 }
      TWELVE_BITS -> IntArray(3) { eightBit[it] * 16 + 5 }
      else -> eightBit
    }
  }

  /**
   * The texel offset, in texels from the top left, read for [block].
   */
  fun texelOf(block: Int): Int = ((block / GRID) * BLOCK + 1) * SIDE + (block % GRID) * BLOCK + 1

  /**
   * The picture as an AVIF file whose only colour box is a `colr nclx` holding [nclx].
   */
  fun bytes(nclx: CicpTag): ByteArray {
    val av1c = hex(configuration)
    val box = byteArrayOf(0, 0, 0, (BOX_HEADER + av1c.size).toByte()) + "av1C".encodeToByteArray() + av1c
    return HeifStills.still("avif", "av01", box, hex(codestream), Size(SIDE, SIDE), nclx, bitDepth = bitDepth)
  }

  override fun toString(): String = name

  companion object {
    const val BLOCKS = 64
    const val SIDE = 32
    private const val GRID = 8
    private const val BLOCK = 4
    private const val BOX_HEADER = 8
    private const val TEN_BITS = 10
    private const val TWELVE_BITS = 12

    // Each codestream comes from libaom 3.15.2's aomenc on macOS 26.6:
    //   aomenc --limit=1 --lossless=1 --obu [--i444] --profile=P --bit-depth=D --input-bit-depth=D -o out.obu in.y4m
    // with profile 0 for 4:2:0 at eight and ten bits, 1 for 4:4:4 and 2 at twelve bits, from a 32 by 32 Y4M frame
    // painted as the class describes at XCOLORRANGE=FULL. Each is its file without the leading temporal delimiter, and
    // each configuration is the av1C payload for that profile, depth and subsampling at level 0.
    val EIGHT_BIT_420: MatrixAvif =
      MatrixAvif(
        "8-bit 4:2:0",
        8,
        unsubsampled = false,
        "0a0618113ffec4203234440000ef40ae2b84ce70eb572dcd9080b604cc8347167cc852d89ddde261e86b968d2c37ddf2128e0909b9" +
          "2c5a12b7feb8909e4c",
        "81000c00",
      )

    val EIGHT_BIT_444: MatrixAvif =
      MatrixAvif(
        "8-bit 4:4:4",
        8,
        unsubsampled = true,
        "0a0538113ffec9323f440000cf73fc44861595d7c2da0788dc84fcd0f805e50d03b63e22d48c79388d1a50efef2bb22a559c686fd6" +
          "1e25d4d3d189ad53ea3a07947e5866d08090a0",
        "81200000",
      )

    val TEN_BIT_420: MatrixAvif =
      MatrixAvif(
        "10-bit 4:2:0",
        TEN_BITS,
        unsubsampled = false,
        "0a0618113ffee420323a440000ef40add6fd312c400b2976bad54c4200b604cc8347167cc852d89ddde261e86d66900d2c37ddf212" +
          "8f471009b92c5a12b8105900909e4c",
        "81004c00",
      )

    val TEN_BIT_444: MatrixAvif =
      MatrixAvif(
        "10-bit 4:4:4",
        TEN_BITS,
        unsubsampled = true,
        "0a0538113ffee93245440000cf73fc2d7214ca1346eb27a0788dc84f213a9805e50d2bc063e22d47233fb0ce9697548fef2bb22a55" +
          "9c68809fe1e25d4d6ca189ad565253a079481b2866d08927ca",
        "81204000",
      )

    val TWELVE_BIT_420: MatrixAvif =
      MatrixAvif(
        "12-bit 4:2:0",
        TWELVE_BITS,
        unsubsampled = false,
        "0a0658113ffef3843240440000ef40adc1c88761d37fd34f1e83cff35b328000b604cc8347167cc852d89ddde261e86dda94000d2c" +
          "37ddf2128f96940009b92c5a12b814c14000909e4c",
        "81406c00",
      )

    private fun hex(value: String): ByteArray =
      ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(HEX).toByte() }

    private const val HEX = 16
  }
}
