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
): File {
  val file = File.createTempFile("filmstrip-prores4444", ".mov").apply { deleteOnExit() }
  val colour = "0x%02X%02X%02X@%.6f".format(red, green, blue, alpha / BYTE_MAX.toDouble())
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
      "color=c=$colour:s=${width}x$height:r=30:d=$seconds,format=yuva444p12le",
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
