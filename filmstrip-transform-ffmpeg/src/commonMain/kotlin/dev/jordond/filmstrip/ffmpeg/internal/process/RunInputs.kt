package dev.jordond.filmstrip.ffmpeg.internal.process

import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.media.describe
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * One input as the command line names it, and the source a failure to read it is reported against.
 *
 * @property path What ffmpeg is handed for the input.
 * @property source The caller's own name for the input, or null when it is not a local file, like a generated input or
 * a remote URL, which nothing here can look for on disk.
 */
internal class ResolvedInput(
  val path: String,
  val source: String?,
)

/**
 * Turns [input] into what the command line names, writing an in-memory picture into this directory first.
 *
 * @throws IOException When a picture cannot be written down.
 */
internal fun Scratch.resolve(input: InputSource): ResolvedInput =
  when (input) {
    is InputSource.OfPath -> {
      ResolvedInput(input.path, input.description)
    }
    is InputSource.OfImage -> {
      val path = materialise(input.image)
      ResolvedInput(path, input.image.describe().takeUnless { path.contains("://") })
    }
    is InputSource.Generated -> {
      ResolvedInput(input.description, source = null)
    }
  }

/**
 * The source of the first local input that is no longer on disk, or null when every one of them is still there.
 */
internal fun List<ResolvedInput>.missingSource(): String? =
  firstOrNull { it.source != null && !SystemFileSystem.exists(Path(it.path)) }?.source
