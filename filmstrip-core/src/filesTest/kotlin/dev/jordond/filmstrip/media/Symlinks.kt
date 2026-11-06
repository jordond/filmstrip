package dev.jordond.filmstrip.media

import kotlinx.io.files.Path

/**
 * Makes a real symbolic link at [link] pointing at [target], which need not exist.
 */
internal expect fun plantSymlink(
  link: Path,
  target: Path,
)
