package dev.jordond.filmstrip.effect

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * What a filter graph names a sidecar by, which is also what tells two of them apart.
 */
class SidecarTest {
  @Test
  fun `the same file shares one placeholder`() {
    val bytes = "LUT_3D_SIZE 2".encodeToByteArray()

    Sidecar(bytes.copyOf(), "cube").placeholder shouldBe Sidecar(bytes, "cube").placeholder
  }

  // Equality counts the extension, so the placeholder has to as well. Two entries a graph reads
  // under one name are written to two files, and the first substitution takes both of them.
  @Test
  fun `the same bytes under two extensions do not share one`() {
    val bytes = "TITLE".encodeToByteArray()

    Sidecar(bytes, "3dl").placeholder shouldNotBe Sidecar(bytes, "cube").placeholder
  }

  @Test
  fun `two files do not share one`() {
    Sidecar("b".encodeToByteArray(), "cube").placeholder shouldNotBe
      Sidecar("a".encodeToByteArray(), "cube").placeholder
  }
}
