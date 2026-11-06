package dev.jordond.filmstrip.media

import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.posix.symlink

@OptIn(ExperimentalForeignApi::class)
internal actual fun plantSymlink(
  link: Path,
  target: Path,
) {
  symlink(target.toString(), link.toString()) shouldBe 0
}
