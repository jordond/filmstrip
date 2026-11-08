package dev.jordond.filmstrip.webcodecs

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A color, as the eight-bit channels a test asserts on.
 */
internal class Rgb(
  val red: Int,
  val green: Int,
  val blue: Int,
) {
  /**
   * True when every channel is within [tolerance] of [other]. Color survives a 4:2:0 round trip approximately, never
   * exactly.
   */
  fun isNear(
    other: Rgb,
    tolerance: Int = DEFAULT_TOLERANCE,
  ): Boolean =
    abs(red - other.red) <= tolerance &&
      abs(green - other.green) <= tolerance &&
      abs(blue - other.blue) <= tolerance

  override fun toString(): String = "rgb($red, $green, $blue)"

  companion object {
    const val DEFAULT_TOLERANCE = 24

    val Black = Rgb(0, 0, 0)
    val Red = Rgb(220, 30, 30)
    val Blue = Rgb(30, 30, 220)
    val Green = Rgb(30, 200, 30)
    val Grey = Rgb(200, 200, 200)
  }
}

/**
 * The mean color of the three by three block around a point, so one pixel's rounding does not decide a reading.
 */
internal fun OutputFrame.around(
  x: Double,
  y: Double,
): Rgb {
  val column = (x * width).toInt()
  val row = (y * height).toInt()
  val samples =
    (-1..1).flatMap { dy ->
      (-1..1).map { dx -> at((column + dx + 0.5) / width, (row + dy + 0.5) / height) }
    }
  return Rgb(
    samples.map { it.red }.average().roundToInt(),
    samples.map { it.green }.average().roundToInt(),
    samples.map { it.blue }.average().roundToInt(),
  )
}

/**
 * Fails when any channel of [read] sits further than [tolerance] from [expected], naming [where] the reading was
 * taken.
 */
internal fun assertReads(
  expected: Rgb,
  read: Rgb,
  where: String,
  tolerance: Float,
) {
  val off = maxOf(abs(read.red - expected.red), abs(read.green - expected.green), abs(read.blue - expected.blue))
  withClue("$where read $read, where $expected was expected within $tolerance") { (off <= tolerance) shouldBe true }
}
