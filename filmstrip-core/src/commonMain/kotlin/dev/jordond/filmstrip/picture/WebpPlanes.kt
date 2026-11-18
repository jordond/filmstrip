package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The red, green, blue and alpha samples of a lossy WebP from the planes its decoder holds, converted the way libwebp
 * converts them.
 *
 * Chroma is upsampled with libwebp's fancy upsampler, which weights the four nearest chroma samples of every texel, and
 * luma and chroma become RGB with its BT.601 integer arithmetic. The result is what `dwebp` writes for the same planes.
 * Alpha is straight, as `ALPH` stores it, and opaque when [alpha] is null.
 *
 * @param luma The luma plane, [width] by [height] samples.
 * @param blueDifference The blue difference plane, half the width and height rounded up.
 * @param redDifference The red difference plane, laid out like [blueDifference].
 * @param alpha The alpha plane, [width] by [height] samples, or null for none.
 * @return Four bytes a texel, row by row.
 * @throws IllegalArgumentException when a plane is not the size [width] and [height] give it.
 */
@InternalFilmstripApi
public fun webpRgbaOf(
  width: Int,
  height: Int,
  luma: ByteArray,
  blueDifference: ByteArray,
  redDifference: ByteArray,
  alpha: ByteArray?,
): ByteArray {
  require(width > 0 && height > 0) { "A picture is at least one texel, and $width by $height is not." }
  val chromaWidth = (width + 1) / 2
  val chromaHeight = (height + 1) / 2
  require(luma.size == width * height) { "The luma plane holds ${luma.size} samples, not ${width * height}." }
  require(blueDifference.size == chromaWidth * chromaHeight && redDifference.size == blueDifference.size) {
    "A chroma plane holds ${chromaWidth * chromaHeight} samples."
  }
  require(alpha == null || alpha.size == width * height) { "The alpha plane holds ${alpha?.size} samples." }
  val out = ByteArray(width * height * RGBA)
  val blueTop = IntArray(width)
  val blueBottom = IntArray(width)
  val redTop = IntArray(width)
  val redBottom = IntArray(width)

  fun convert(
    row: Int,
    blue: IntArray,
    red: IntArray,
  ) {
    for (x in 0 until width) {
      val at = (row * width + x) * RGBA
      val y = luma[row * width + x].toInt() and BYTE
      out[at] = clip(multiplyHigh(y, LUMA) + multiplyHigh(red[x], RED_FROM_RED) - RED_OFFSET)
      out[at + 1] =
        clip(
          multiplyHigh(y, LUMA) - multiplyHigh(blue[x], GREEN_FROM_BLUE) - multiplyHigh(red[x], GREEN_FROM_RED) +
            GREEN_OFFSET,
        )
      out[at + 2] = clip(multiplyHigh(y, LUMA) + multiplyHigh(blue[x], BLUE_FROM_BLUE) - BLUE_OFFSET)
      out[at + 3] = if (alpha == null) OPAQUE else alpha[row * width + x]
    }
  }

  fun upsample(
    topRow: Int,
    currentRow: Int,
    top: IntArray,
    bottom: IntArray?,
    plane: ByteArray,
  ) {
    upsampleChroma(plane, chromaWidth, width, topRow, currentRow, top, bottom)
  }

  upsample(0, 0, blueTop, null, blueDifference)
  upsample(0, 0, redTop, null, redDifference)
  convert(0, blueTop, redTop)
  var row = 1
  while (row + 1 < height) {
    val above = (row - 1) / 2
    val below = (row + 1) / 2
    upsample(above, below, blueTop, blueBottom, blueDifference)
    upsample(above, below, redTop, redBottom, redDifference)
    convert(row, blueTop, redTop)
    convert(row + 1, blueBottom, redBottom)
    row += 2
  }
  if (row < height) {
    val last = (height - 1) / 2
    upsample(last, last, blueTop, null, blueDifference)
    upsample(last, last, redTop, null, redDifference)
    convert(row, blueTop, redTop)
  }
  return out
}

// Fills [top] with the chroma of the texels of the row that sits nearer [topRow] and [bottom] with the one nearer
// [currentRow], each texel the weighted mean of the four chroma samples around it.
private fun upsampleChroma(
  plane: ByteArray,
  chromaWidth: Int,
  width: Int,
  topRow: Int,
  currentRow: Int,
  top: IntArray,
  bottom: IntArray?,
) {
  val topAt = topRow * chromaWidth
  val currentAt = currentRow * chromaWidth
  var topLeft = plane[topAt].toInt() and BYTE
  var left = plane[currentAt].toInt() and BYTE
  top[0] = (3 * topLeft + left + 2) shr 2
  if (bottom != null) bottom[0] = (3 * left + topLeft + 2) shr 2
  val lastPair = (width - 1) shr 1
  for (x in 1..lastPair) {
    val above = plane[topAt + x].toInt() and BYTE
    val here = plane[currentAt + x].toInt() and BYTE
    val sum = topLeft + above + left + here + 8
    val diagonalOne = (sum + 2 * (above + left)) shr 3
    val diagonalTwo = (sum + 2 * (topLeft + here)) shr 3
    top[2 * x - 1] = (diagonalOne + topLeft) shr 1
    top[2 * x] = (diagonalTwo + above) shr 1
    if (bottom != null) {
      bottom[2 * x - 1] = (diagonalTwo + left) shr 1
      bottom[2 * x] = (diagonalOne + here) shr 1
    }
    topLeft = above
    left = here
  }
  if (width and 1 == 0) {
    top[width - 1] = (3 * topLeft + left + 2) shr 2
    if (bottom != null) bottom[width - 1] = (3 * left + topLeft + 2) shr 2
  }
}

private fun multiplyHigh(
  value: Int,
  coefficient: Int,
): Int = (value * coefficient) shr 8

private fun clip(value: Int): Byte =
  (
    if (value and CLIP_MASK ==
      0
    ) {
      value shr FIX
    } else if (value < 0) {
      0
    } else {
      BYTE
    }
  ).toByte()

private const val RGBA = 4
private const val BYTE = 0xFF
private const val OPAQUE: Byte = -1
private const val FIX = 6
private const val CLIP_MASK = ((256 shl FIX) - 1).inv()
private const val LUMA = 19077
private const val RED_FROM_RED = 26149
private const val GREEN_FROM_BLUE = 6419
private const val GREEN_FROM_RED = 13320
private const val BLUE_FROM_BLUE = 33050
private const val RED_OFFSET = 14234
private const val GREEN_OFFSET = 8708
private const val BLUE_OFFSET = 17685
