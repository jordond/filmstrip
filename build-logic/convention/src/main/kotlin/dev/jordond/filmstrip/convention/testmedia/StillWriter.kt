package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.GradleException
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Writes a [FixtureStill] and reads it back to check it is the picture the spec asked for. [write] returns the still's
 * row for the generated fixture list.
 *
 * ffmpeg draws the color into a PNG, and the container's own tool turns that into the fixture. The read back goes
 * through ffprobe for the size and codec, and through ffmpeg for the color at the centre, so a still that came back
 * the wrong size, in the wrong codec or in another color fails the build. A still that fails to write or to verify is
 * deleted, so a half written file is never left where a publish would pick it up.
 */
internal class StillWriter(
  private val exec: ExecOperations,
) {
  /**
   * Names the tool [still]'s container needs when this host has no such program on its `PATH`, or returns null when
   * [write] can go ahead.
   */
  fun missingTool(still: FixtureStill): String? {
    val tool = still.container.tool ?: return null
    val onPath =
      System
        .getenv("PATH")
        .orEmpty()
        .split(File.pathSeparatorChar)
        .filter { it.isNotEmpty() }
        .any { File(it, tool).canExecute() }
    return tool.takeUnless { onPath }
  }

  fun write(
    still: FixtureStill,
    target: File,
  ): String {
    val source = target.resolveSibling("${still.name}-source.png")
    source.delete()
    var written = false
    try {
      run(
        listOf(
          "ffmpeg",
          "-y",
          "-hide_banner",
          "-loglevel",
          "error",
          "-nostdin",
          "-f",
          "lavfi",
          "-i",
          "color=c=${still.hex}:s=${still.width}x${still.height}",
          "-frames:v",
          "1",
          "-pix_fmt",
          "rgb24",
          source.absolutePath,
        ),
      )
      when (still.container) {
        StillContainer.Heic -> {
          run(listOf("sips", "-s", "format", "heic", source.absolutePath, "--out", target.absolutePath))
        }
      }
      return verify(still, target).also { written = true }
    } finally {
      source.delete()
      if (!written) target.delete()
    }
  }

  private fun verify(
    still: FixtureStill,
    target: File,
  ): String {
    val fields =
      run(
        listOf(
          "ffprobe",
          "-v",
          "error",
          "-show_entries",
          "stream=codec_name,width,height",
          "-show_entries",
          "format=size",
          "-of",
          "default=noprint_wrappers=1",
          target.absolutePath,
        ),
      ).toString(Charsets.UTF_8)
        .lineSequence()
        .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
        .associate { it[0].trim() to it[1].trim() }

    fun single(key: String): String = fields[key] ?: error("ffprobe reported no $key for ${still.fileName}")

    val width = single("width").toInt()
    val height = single("height").toInt()
    val codec = single("codec_name")
    val centre = centreOf(target, width, height)
    val asked = listOf(still.color shr RED_SHIFT, still.color shr GREEN_SHIFT, still.color).map { it and BYTE }

    val drift =
      buildList {
        if (width != still.width || height != still.height) {
          add("size ${width}x$height != ${still.width}x${still.height}")
        }
        if (codec != still.container.codec) add("codec $codec != ${still.container.codec}")
        if (centre.zip(asked).any { (read, want) -> abs(read - want) > COLOR_TOLERANCE }) {
          add("centre $centre != $asked")
        }
      }

    if (drift.isNotEmpty()) {
      throw GradleException("${still.fileName} does not match its spec: ${drift.joinToString("; ")}")
    }

    return listOf(still.name, width, height, "-", "-", "-", "-", "-", "-", single("size")).joinToString(" ")
  }

  private fun centreOf(
    target: File,
    width: Int,
    height: Int,
  ): List<Int> {
    val rgb =
      run(
        listOf(
          "ffmpeg",
          "-v",
          "error",
          "-nostdin",
          "-i",
          target.absolutePath,
          "-frames:v",
          "1",
          "-f",
          "rawvideo",
          "-pix_fmt",
          "rgb24",
          "-",
        ),
      )
    val offset = ((height / 2) * width + width / 2) * CHANNELS
    if (rgb.size < offset + CHANNELS) throw GradleException("ffmpeg decoded too few bytes from ${target.name}")
    return (0 until CHANNELS).map { rgb[offset + it].toInt() and BYTE }
  }

  private fun run(command: List<String>): ByteArray {
    val stdout = ByteArrayOutputStream()
    exec.exec {
      commandLine(command)
      standardOutput = stdout
    }
    return stdout.toByteArray()
  }

  private companion object {
    const val CHANNELS = 3
    const val BYTE = 0xFF
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8

    // A flat color through a lossy codec and back lands within a code value or two of where it started.
    const val COLOR_TOLERANCE = 4
  }
}
