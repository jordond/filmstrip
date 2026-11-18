package dev.jordond.filmstrip.picture.png

// Undoes each row's filter in place, or returns false for a filter type PNG does not define.
internal fun unfilter(
  data: ByteArray,
  start: Int,
  rowBytes: Int,
  rows: Int,
  stride: Int,
): Boolean {
  val lead = minOf(stride, rowBytes)
  for (row in 0 until rows) {
    val line = start + row * (rowBytes + 1) + 1
    val above = if (row == 0) -1 else line - rowBytes - 1
    when (data[line - 1].toInt()) {
      NONE -> {}
      SUB -> {
        unfilterSub(data, line, rowBytes, stride)
      }
      UP -> {
        if (above >= 0) unfilterUp(data, line, above, rowBytes)
      }
      AVERAGE -> {
        unfilterAverage(data, line, above, rowBytes, lead, stride)
      }
      PAETH -> {
        if (above >= 0) {
          unfilterPaeth(data, line, above, rowBytes, lead, stride)
        } else {
          unfilterSub(data, line, rowBytes, stride)
        }
      }
      else -> {
        return false
      }
    }
  }
  return true
}

private fun unfilterUp(
  data: ByteArray,
  line: Int,
  above: Int,
  rowBytes: Int,
) {
  val gap = above - line
  for (i in line until line + rowBytes) data[i] = (data[i] + data[i + gap]).toByte()
}

private fun unfilterSub(
  data: ByteArray,
  line: Int,
  rowBytes: Int,
  stride: Int,
) {
  for (i in line + stride until line + rowBytes) data[i] = (data[i] + data[i - stride]).toByte()
}

private fun unfilterAverage(
  data: ByteArray,
  line: Int,
  above: Int,
  rowBytes: Int,
  lead: Int,
  stride: Int,
) {
  if (above < 0) {
    for (i in line + stride until line + rowBytes) {
      data[i] = (data[i] + ((data[i - stride].toInt() and 0xFF) ushr 1)).toByte()
    }
    return
  }
  val gap = above - line
  for (i in line until line + lead) data[i] = (data[i] + ((data[i + gap].toInt() and 0xFF) ushr 1)).toByte()
  for (i in line + stride until line + rowBytes) {
    val predicted = ((data[i - stride].toInt() and 0xFF) + (data[i + gap].toInt() and 0xFF)) ushr 1
    data[i] = (data[i] + predicted).toByte()
  }
}

// PNG's Paeth predictor, which breaks ties toward a, then b, then c.
private fun unfilterPaeth(
  data: ByteArray,
  line: Int,
  above: Int,
  rowBytes: Int,
  lead: Int,
  stride: Int,
) {
  val gap = above - line
  for (i in line until line + lead) data[i] = (data[i] + data[i + gap]).toByte()
  for (i in line + stride until line + rowBytes) {
    val a = data[i - stride].toInt() and 0xFF
    val b = data[i + gap].toInt() and 0xFF
    val c = data[i + gap - stride].toInt() and 0xFF
    val aboveGap = b - c
    val leftGap = a - c
    val fromA = if (aboveGap < 0) -aboveGap else aboveGap
    val fromB = if (leftGap < 0) -leftGap else leftGap
    val sum = aboveGap + leftGap
    val fromC = if (sum < 0) -sum else sum
    val predicted =
      if (fromA <= fromB && fromA <= fromC) {
        a
      } else if (fromB <= fromC) {
        b
      } else {
        c
      }
    data[i] = (data[i] + predicted).toByte()
  }
}

private const val NONE = 0
private const val SUB = 1
private const val UP = 2
private const val AVERAGE = 3
private const val PAETH = 4
