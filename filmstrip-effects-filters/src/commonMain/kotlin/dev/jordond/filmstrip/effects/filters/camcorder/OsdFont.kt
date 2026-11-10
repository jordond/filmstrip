package dev.jordond.filmstrip.effects.filters.camcorder

// A square cornered dot font drawn for filmstrip's camcorder display. Every glyph is seven dots tall, letters and
// digits five wide, punctuation and icons as wide as they need. A '#' is a lit dot.
internal object OsdFont {
  const val HEIGHT = 7

  const val RECORD = ''
  const val PLAY = ''
  const val PAUSE = ''
  const val STOP = ''
  const val REWIND = ''
  const val FAST_FORWARD = ''
  const val BATTERY_EMPTY = ''

  // The battery icon showing [bars] of three.
  fun battery(bars: Int): Char = BATTERY_EMPTY + bars.coerceIn(0, BATTERY_BARS)

  const val BATTERY_BARS = 3

  // The characters a label may hold.
  const val TEXT: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:./-'+ "

  fun glyph(char: Char): Glyph = glyphs[char] ?: throw IllegalArgumentException(unknown(char))

  fun requireDrawable(text: String) {
    text.firstOrNull { it !in TEXT }?.let { throw IllegalArgumentException(unknown(it)) }
  }

  private fun unknown(char: Char): String =
    "The camcorder font has no '$char' (U+${char.code.toString(16).uppercase().padStart(4, '0')}). " +
      "It draws A to Z, 0 to 9, space and : . / - ' +."

  private val glyphs: Map<Char, Glyph> =
    mapOf(
      'A' to Glyph("#####", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
      'B' to Glyph("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
      'C' to Glyph("#####", "#....", "#....", "#....", "#....", "#....", "#####"),
      'D' to Glyph("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
      'E' to Glyph("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
      'F' to Glyph("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
      'G' to Glyph("#####", "#....", "#....", "#.###", "#...#", "#...#", "#####"),
      'H' to Glyph("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
      'I' to Glyph("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
      'J' to Glyph("#####", "...#.", "...#.", "...#.", "...#.", "#..#.", "####."),
      'K' to Glyph("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
      'L' to Glyph("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
      'M' to Glyph("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
      'N' to Glyph("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
      'O' to Glyph("#####", "#...#", "#...#", "#...#", "#...#", "#...#", "#####"),
      'P' to Glyph("#####", "#...#", "#...#", "#####", "#....", "#....", "#...."),
      'Q' to Glyph("#####", "#...#", "#...#", "#...#", "#.#.#", "#..#.", "###.#"),
      'R' to Glyph("#####", "#...#", "#...#", "#####", "#.#..", "#..#.", "#...#"),
      'S' to Glyph(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
      'T' to Glyph("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
      'U' to Glyph("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", "#####"),
      'V' to Glyph("#...#", "#...#", "#...#", "#...#", ".#.#.", ".#.#.", "..#.."),
      'W' to Glyph("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
      'X' to Glyph("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
      'Y' to Glyph("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
      'Z' to Glyph("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
      '0' to Glyph("#####", "#...#", "#..##", "#.#.#", "##..#", "#...#", "#####"),
      '1' to Glyph("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", "#####"),
      '2' to Glyph("#####", "....#", "....#", "#####", "#....", "#....", "#####"),
      '3' to Glyph("#####", "....#", "....#", ".####", "....#", "....#", "#####"),
      '4' to Glyph("#...#", "#...#", "#...#", "#####", "....#", "....#", "....#"),
      '5' to Glyph("#####", "#....", "#....", "#####", "....#", "....#", "#####"),
      '6' to Glyph("#####", "#....", "#....", "#####", "#...#", "#...#", "#####"),
      '7' to Glyph("#####", "....#", "....#", "...#.", "..#..", "..#..", "..#.."),
      '8' to Glyph("#####", "#...#", "#...#", "#####", "#...#", "#...#", "#####"),
      '9' to Glyph("#####", "#...#", "#...#", "#####", "....#", "....#", "#####"),
      ':' to Glyph(".", "#", ".", ".", ".", "#", "."),
      '.' to Glyph(".", ".", ".", ".", ".", ".", "#"),
      '/' to Glyph("....#", "....#", "...#.", "..#..", ".#...", "#....", "#...."),
      '-' to Glyph("...", "...", "...", "###", "...", "...", "..."),
      '\'' to Glyph("#", "#", ".", ".", ".", ".", "."),
      '+' to Glyph(".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
      ' ' to Glyph("...", "...", "...", "...", "...", "...", "..."),
      RECORD to Glyph(".....", ".###.", "#####", "#####", "#####", ".###.", "....."),
      PLAY to Glyph("#....", "##...", "###..", "####.", "###..", "##...", "#...."),
      PAUSE to Glyph(".....", "##.##", "##.##", "##.##", "##.##", "##.##", "....."),
      STOP to Glyph(".....", "#####", "#####", "#####", "#####", "#####", "....."),
      REWIND to Glyph("...#...#", "..##..##", ".###.###", "########", ".###.###", "..##..##", "...#...#"),
      FAST_FORWARD to Glyph("#...#...", "##..##..", "###.###.", "########", "###.###.", "##..##..", "#...#..."),
      battery(0) to batteryGlyph(0),
      battery(1) to batteryGlyph(1),
      battery(2) to batteryGlyph(2),
      battery(3) to batteryGlyph(3),
    )

  // A thirteen dot wide cell with a terminal on the right and [bars] two dot bars filling from the left.
  private fun batteryGlyph(bars: Int): Glyph {
    val inside = StringBuilder("#.")
    for (bar in 1..BATTERY_BARS) inside.append(if (bar <= bars) "##." else "...")
    return Glyph(
      "############.",
      "#..........#.",
      "$inside##",
      "$inside##",
      "$inside##",
      "#..........#.",
      "############.",
    )
  }
}

// One glyph's dots, row by row from the top.
internal class Glyph(
  vararg rows: String,
) {
  val width: Int = rows.first().length
  private val lit: BooleanArray = BooleanArray(width * OsdFont.HEIGHT)

  init {
    require(rows.size == OsdFont.HEIGHT && rows.all { it.length == width })
    rows.forEachIndexed { y, row -> row.forEachIndexed { x, c -> lit[y * width + x] = c == '#' } }
  }

  fun isLit(
    x: Int,
    y: Int,
  ): Boolean = lit[y * width + x]
}
