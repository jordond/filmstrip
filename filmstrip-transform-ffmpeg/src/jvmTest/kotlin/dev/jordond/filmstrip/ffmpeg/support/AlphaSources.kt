package dev.jordond.filmstrip.ffmpeg.support

import io.kotest.matchers.shouldBe
import java.io.File

/**
 * A ProRes 4444 clip of [width] by [height] and [seconds] long, flat [red], [green] and [blue] with every pixel at
 * [alpha] out of 255, written as `yuva444p12le` the way a ProRes 4444 file carries alpha.
 *
 * The source is generated here from a lavfi colour and never stored, so the pixel the tests read is exactly what the
 * arguments say.
 */
internal fun proRes4444Clip(
  width: Int,
  height: Int,
  red: Int,
  green: Int,
  blue: Int,
  alpha: Int,
  seconds: Int = 1,
): File = proRes4444Of("color=c=${lavfiColour(red, green, blue, alpha)}:s=${width}x$height:r=30:d=$seconds")

/**
 * Like [proRes4444Clip], with the left half in one colour and the right half in another, both at [alpha].
 *
 * A blurred fill mixes the halves, so a pixel over it reads a cover that differs from the clip's own colour.
 */
internal fun proRes4444TwoTone(
  width: Int,
  height: Int,
  left: Triple<Int, Int, Int>,
  right: Triple<Int, Int, Int>,
  alpha: Int,
): File {
  val half = width / 2
  val a = "color=c=${lavfiColour(left.first, left.second, left.third, alpha)}:s=${half}x$height:r=30:d=1[l]"
  val b = "color=c=${lavfiColour(right.first, right.second, right.third, alpha)}:s=${half}x$height:r=30:d=1[r]"
  return proRes4444Of("$a;$b;[l][r]hstack")
}

/**
 * Like [proRes4444Clip], with the left half opaque in [colour] and the right half clear and black.
 *
 * A blur that does not weigh its pixels by alpha mixes the clear half's black into the cover, so a pixel over the clear
 * half reads darker than the colour beside it.
 */
internal fun proRes4444HalfClear(
  width: Int,
  height: Int,
  colour: Triple<Int, Int, Int>,
): File {
  val half = width / 2
  val a = "color=c=${lavfiColour(colour.first, colour.second, colour.third, BYTE_MAX)}:s=${half}x$height:r=30:d=1[l]"
  val b = "color=c=${lavfiColour(0, 0, 0, 0)}:s=${half}x$height:r=30:d=1[r]"
  return proRes4444Of("$a;$b;[l][r]hstack")
}

private fun lavfiColour(
  red: Int,
  green: Int,
  blue: Int,
  alpha: Int,
): String = "0x%02X%02X%02X@%.6f".format(red, green, blue, alpha / BYTE_MAX.toDouble())

private fun proRes4444Of(graph: String): File {
  val file = File.createTempFile("filmstrip-prores4444", ".mov").apply { deleteOnExit() }
  val encode =
    ProcessBuilder(
      hostFfmpeg,
      "-y",
      "-hide_banner",
      "-loglevel",
      "error",
      "-f",
      "lavfi",
      "-i",
      "$graph,format=yuva444p12le",
      "-c:v",
      "prores_ks",
      "-profile:v",
      "4444",
      "-pix_fmt",
      "yuva444p12le",
      file.path,
    ).inheritIO().start()
  encode.waitFor() shouldBe 0
  return file
}

private const val BYTE_MAX = 255
