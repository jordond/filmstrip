package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.test.TestFrame
import kotlin.math.roundToInt

/**
 * This frame with each pixel's BT.709 luma in all three channels and its alpha left alone.
 *
 * Two frames read this way compare on luma alone, which is the part of a picture an encoder's chroma reduction leaves
 * where it was.
 */
internal fun TestFrame.bt709Luma(): TestFrame {
  val weights = krKbOf(BT709_MATRIX)
  val grey = pixels.copyOf()
  for (pixel in 0 until size.width * size.height) {
    val base = pixel * CHANNELS
    val luma = weights.lumaOf(codeAt(base), codeAt(base + 1), codeAt(base + 2)).roundToInt().toByte()
    repeat(RGB) { grey[base + it] = luma }
  }
  return TestFrame(grey, size)
}

/**
 * The limited range Cb code the pixel at [x] and [y] stores under the H.273 [matrix] code.
 */
internal fun TestFrame.cbCodeAt(
  x: Int,
  y: Int,
  matrix: Int,
): Int {
  val weights = krKbOf(matrix)
  val base = (y * size.width + x) * CHANNELS
  val red = codeAt(base)
  val green = codeAt(base + 1)
  val blue = codeAt(base + 2)
  return chromaCode((blue - weights.lumaOf(red, green, blue)) / (2 * (1 - weights.kb)))
}

/**
 * The limited range Cr code the pixel at [x] and [y] stores under the H.273 [matrix] code.
 */
internal fun TestFrame.crCodeAt(
  x: Int,
  y: Int,
  matrix: Int,
): Int {
  val weights = krKbOf(matrix)
  val base = (y * size.width + x) * CHANNELS
  val red = codeAt(base)
  val green = codeAt(base + 1)
  val blue = codeAt(base + 2)
  return chromaCode((red - weights.lumaOf(red, green, blue)) / (2 * (1 - weights.kr)))
}

private fun krKbOf(matrix: Int): YcbcrReading.KrKb = ycbcrReading(matrix, UNSPECIFIED, UNSPECIFIED) as YcbcrReading.KrKb

private fun YcbcrReading.KrKb.lumaOf(
  red: Int,
  green: Int,
  blue: Int,
): Double = kr * red + (1 - kr - kb) * green + kb * blue

private fun TestFrame.codeAt(index: Int): Int = pixels[index].toInt() and BYTE_MASK

private fun chromaCode(difference: Double): Int = (CHROMA_MIDDLE + difference * CHROMA_SPAN / FULL_CODE).roundToInt()

/**
 * The H.273 matrix code of BT.709.
 */
internal const val BT709_MATRIX = 1

private const val UNSPECIFIED = 2
private const val CHROMA_MIDDLE = 128
private const val CHROMA_SPAN = 224
private const val FULL_CODE = 255.0
private const val CHANNELS = 4
private const val RGB = 3
private const val BYTE_MASK = 0xFF
