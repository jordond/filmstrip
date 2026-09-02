package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.ffmpeg.internal.Scratch
import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * What the export directory writes, and what it writes only once.
 */
class ScratchTest {
  private val scratch = Scratch.create()

  @AfterTest
  fun cleanUp() {
    scratch.delete()
  }

  // A graph that reads the same table from two nodes carries the same sidecar twice, and the
  // placeholder it reads them by is one string, so a second write would be a second file nothing
  // ever names.
  @Test
  fun `writes one file for a sidecar that appears twice`() {
    val sidecar = Sidecar(CUBE.encodeToByteArray(), "cube")

    scratch.write(sidecar) shouldBe scratch.write(Sidecar(CUBE.encodeToByteArray(), "cube"))
  }

  @Test
  fun `writes two files for two sidecars`() {
    val first = scratch.write(Sidecar(CUBE.encodeToByteArray(), "cube"))
    val second = scratch.write(Sidecar("LUT_3D_SIZE 4".encodeToByteArray(), "cube"))

    assertNotEquals(first, second)
  }

  private companion object {
    const val CUBE = "LUT_3D_SIZE 2"
  }
}
