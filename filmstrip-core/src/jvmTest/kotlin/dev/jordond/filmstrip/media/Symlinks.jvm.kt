package dev.jordond.filmstrip.media

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Path as NioPath

internal actual fun plantSymlink(
  link: Path,
  target: Path,
) {
  Files.createSymbolicLink(NioPath.of(link.toString()), NioPath.of(target.toString()))
}
