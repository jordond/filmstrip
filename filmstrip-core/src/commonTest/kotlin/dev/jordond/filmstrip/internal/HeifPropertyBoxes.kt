package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.geometry.Size

// The size and transformative properties an AVIF or HEIC item lists, built for the size and orientation tests.

internal fun ispe(size: Size): ByteArray = ispe(size.width.toLong(), size.height.toLong())

internal fun ispe(
  width: Long,
  height: Long,
): ByteArray = fullBox("ispe", 0, 0, bytes { u32(width).u32(height) })

internal fun clap(size: Size): ByteArray = clap(size.width.toLong(), 1, size.height.toLong(), 1)

internal fun irot(turns: Int): ByteArray = box("irot", byteArrayOf(turns.toByte()))

internal fun imir(axis: Int): ByteArray = box("imir", byteArrayOf(axis.toByte()))

/**
 * A clean aperture of the given width and height fractions, offset from the centre by the horizontal and vertical
 * fractions, each field written as the 32 bits the box holds.
 */
internal fun clap(
  width: Long,
  widthDenominator: Long,
  height: Long,
  heightDenominator: Long,
  horizontalOffset: Long = 0,
  horizontalDenominator: Long = 1,
  verticalOffset: Long = 0,
  verticalDenominator: Long = 1,
): ByteArray =
  box(
    "clap",
    bytes {
      u32(width).u32(widthDenominator).u32(height).u32(heightDenominator)
      u32(horizontalOffset).u32(horizontalDenominator).u32(verticalOffset).u32(verticalDenominator)
    },
  )
