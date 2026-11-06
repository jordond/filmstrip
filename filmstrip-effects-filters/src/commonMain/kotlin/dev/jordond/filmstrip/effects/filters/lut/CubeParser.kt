package dev.jordond.filmstrip.effects.filters.lut

/**
 * The size and entries a [CubeParser] reads, with a domain of `0` to `1` and every entry within it.
 */
internal class ParsedCube(
  val size: Int,
  val entries: FloatArray,
)

/**
 * Reads the text of a `.cube` file line by line, holding no more than the table its rows have earned.
 *
 * [LUT_3D_SIZE] is a length field in caller text, so it only sizes the table once the text is long enough to hold that
 * many rows. The whole read is bound by [maxChars].
 */
internal class CubeParser(
  private val text: String,
  private val maxChars: Int,
) {
  private var lineNumber = 0
  private var size = 0
  private var rows = 0
  private var entries: FloatArray? = null
  private val domainMin = doubleArrayOf(0.0, 0.0, 0.0)
  private val domainMax = doubleArrayOf(1.0, 1.0, 1.0)

  fun parse(): ParsedCube {
    require(text.length <= maxChars) { "A .cube file can hold $maxChars characters, and this one has ${text.length}." }
    var start = if (text.startsWith(BYTE_ORDER_MARK)) 1 else 0
    while (start < text.length) {
      val end = lineEnd(start)
      lineNumber++
      readLine(start, end)
      start = if (text.startsWith(CRLF, end)) end + CRLF.length else end + 1
    }
    require(size != 0) { "A .cube file needs a $LUT_3D_SIZE line, and this one has none." }
    val read = requireNotNull(entries) { "A .cube file of $LUT_3D_SIZE $size needs ${rowsFor(size)} rows, and has 0." }
    require(rows == rowsFor(size)) { "A .cube file of $LUT_3D_SIZE $size needs ${rowsFor(size)} rows, and has $rows." }
    return inUnitDomain(read)
  }

  private fun lineEnd(start: Int): Int {
    var at = start
    while (at < text.length && text[at] != '\n' && text[at] != '\r') at++
    return at
  }

  private fun readLine(
    start: Int,
    end: Int,
  ) {
    var from = start
    while (from < end && text[from].isCubeSpace()) from++
    var until = end
    while (until > from && text[until - 1].isCubeSpace()) until--
    if (from == until || text[from] == '#') return
    if (text[from].isLetter()) readKeyword(from, until) else readRow(from, until)
  }

  private fun readKeyword(
    from: Int,
    until: Int,
  ) {
    var nameEnd = from
    while (nameEnd < until && !text[nameEnd].isCubeSpace()) nameEnd++
    val name = text.substring(from, nameEnd)
    if (name == TITLE) return
    require(rows == 0) { "${here()} has $name after the table's rows began." }
    when (name) {
      LUT_3D_SIZE -> {
        readSize(nameEnd, until)
      }
      "LUT_1D_SIZE", "LUT_1D_INPUT_RANGE" -> {
        throw IllegalArgumentException("${here()} has $name, and a 1D table is not supported.")
      }
      "DOMAIN_MIN" -> {
        numbersOn(nameEnd, until, CHANNELS).copyInto(domainMin)
      }
      "DOMAIN_MAX" -> {
        numbersOn(nameEnd, until, CHANNELS).copyInto(domainMax)
      }
      "LUT_3D_INPUT_RANGE" -> {
        val range = numbersOn(nameEnd, until, 2)
        domainMin.fill(range[0])
        domainMax.fill(range[1])
      }
      else -> {
        throw IllegalArgumentException("${here()} has an unknown keyword ${name.take(MAX_NAME_SHOWN)}.")
      }
    }
  }

  private fun readSize(
    from: Int,
    until: Int,
  ) {
    require(size == 0) { "${here()} has a second $LUT_3D_SIZE." }
    val named = text.substring(from, until).trim().toIntOrNull()
    require(named != null && named in CubeLut.MIN_SIZE..CubeLut.MAX_SIZE) {
      "${here()} has a $LUT_3D_SIZE outside ${CubeLut.MIN_SIZE} to ${CubeLut.MAX_SIZE}."
    }
    val minimumChars = rowsFor(named) * MIN_ROW_CHARS - 1
    require(text.length >= minimumChars) {
      "${here()} has a $LUT_3D_SIZE of $named, which takes at least $minimumChars characters, and the file has " +
        "${text.length}."
    }
    size = named
  }

  private fun readRow(
    from: Int,
    until: Int,
  ) {
    require(size != 0) { "${here()} has a row ahead of $LUT_3D_SIZE." }
    require(rows < rowsFor(size)) { "${here()} has a row past the ${rowsFor(size)} a $LUT_3D_SIZE of $size holds." }
    val table = entries ?: FloatArray(rowsFor(size) * CHANNELS).also { entries = it }
    val row = numbersOn(from, until, CHANNELS)
    val at = rows * CHANNELS
    // An SDR code stops at 0 and 1, so an entry past either is held there.
    table[at] = row[0].coerceIn(0.0, 1.0).toFloat()
    table[at + 1] = row[1].coerceIn(0.0, 1.0).toFloat()
    table[at + 2] = row[2].coerceIn(0.0, 1.0).toFloat()
    rows++
  }

  private fun numbersOn(
    start: Int,
    until: Int,
    count: Int,
  ): DoubleArray {
    val numbers = DoubleArray(count)
    var from = start
    var read = 0
    while (from < until) {
      while (from < until && text[from].isCubeSpace()) from++
      if (from == until) break
      var end = from
      while (end < until && !text[end].isCubeSpace()) end++
      require(read < count) { "${here()} has more than $count numbers." }
      val number = text.substring(from, end).toDoubleOrNull()
      require(number != null && number.isFinite()) { "${here()} has something other than a finite number." }
      numbers[read++] = number
      from = end
    }
    require(read == count) { "${here()} has $read numbers, not $count." }
    return numbers
  }

  private fun inUnitDomain(table: FloatArray): ParsedCube {
    val isDefault = (0 until CHANNELS).all { domainMin[it] == 0.0 && domainMax[it] == 1.0 }
    if (isDefault) return ParsedCube(size, table)
    (0 until CHANNELS).forEach { channel ->
      require(domainMin[channel] < domainMax[channel]) {
        "A .cube domain has to rise on every channel, and channel $channel runs ${domainMin[channel]} to " +
          "${domainMax[channel]}."
      }
    }
    val target = maxOf(size, MIN_DOMAIN_GRID)
    val last = (target - 1).toDouble()
    val resampled = FloatArray(rowsFor(target) * CHANNELS)
    val read = DoubleArray(CHANNELS)
    forEachEntry(target) { index, red, green, blue ->
      trilinear(size, table, fromDomain(red / last, 0), fromDomain(green / last, 1), fromDomain(blue / last, 2), read)
      val at = index * CHANNELS
      resampled[at] = read[0].toFloat()
      resampled[at + 1] = read[1].toFloat()
      resampled[at + 2] = read[2].toFloat()
    }
    return ParsedCube(target, resampled)
  }

  private fun fromDomain(
    code: Double,
    channel: Int,
  ): Double = (code - domainMin[channel]) / (domainMax[channel] - domainMin[channel])

  private fun here(): String = "Line $lineNumber of the .cube file"

  private fun Char.isCubeSpace(): Boolean = this == ' ' || this == '\t'
}

private fun rowsFor(size: Int): Int = size * size * size

private const val LUT_3D_SIZE = "LUT_3D_SIZE"
private const val TITLE = "TITLE"

// "0 0 0" and its line break, the shortest a row can be.
private const val MIN_ROW_CHARS = 6

// A domain's edge can land between grid points, so a domain is read onto a grid fine enough to keep a coarse file's
// edge sharp, and 65 points is what the atlas holds on every GL version.
private const val MIN_DOMAIN_GRID = 65
private const val MAX_NAME_SHOWN = 32
private const val BYTE_ORDER_MARK = "\uFEFF"
private const val CRLF = "\r\n"
