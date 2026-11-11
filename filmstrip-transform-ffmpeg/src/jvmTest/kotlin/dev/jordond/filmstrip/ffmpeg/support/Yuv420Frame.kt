package dev.jordond.filmstrip.ffmpeg.support

/**
 * One frame's planes, 8 bit limited range 4:2:0.
 */
internal class Yuv420Frame(
  val width: Int,
  val height: Int,
  private val bytes: ByteArray,
) {
  /**
   * The luma code at [x] and [y].
   */
  fun luma(
    x: Int,
    y: Int,
  ): Int = bytes[y * width + x].toInt() and BYTE

  /**
   * The Cb code of chroma sample [column] on chroma row [row].
   */
  fun cb(
    column: Int,
    row: Int,
  ): Int = bytes[width * height + row * (width / 2) + column].toInt() and BYTE

  /**
   * The Cr code of chroma sample [column] on chroma row [row].
   */
  fun cr(
    column: Int,
    row: Int,
  ): Int = bytes[width * height + (width / 2) * (height / 2) + row * (width / 2) + column].toInt() and BYTE
}
