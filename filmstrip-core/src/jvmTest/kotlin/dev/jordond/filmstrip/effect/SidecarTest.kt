package dev.jordond.filmstrip.effect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What a filter graph names a sidecar by, which is also what tells two of them apart.
 */
class SidecarTest {
  @Test
  fun `the same file shares one placeholder`() {
    val bytes = "LUT_3D_SIZE 2".encodeToByteArray()

    assertEquals(Sidecar(bytes, "cube").placeholder, Sidecar(bytes.copyOf(), "cube").placeholder)
  }

  // Equality counts the extension, so the placeholder has to as well. Two entries a graph reads
  // under one name are written to two files, and the first substitution takes both of them.
  @Test
  fun `the same bytes under two extensions do not share one`() {
    val bytes = "TITLE".encodeToByteArray()

    assertNotEquals(Sidecar(bytes, "cube").placeholder, Sidecar(bytes, "3dl").placeholder)
  }

  @Test
  fun `two files do not share one`() {
    assertNotEquals(
      Sidecar("a".encodeToByteArray(), "cube").placeholder,
      Sidecar("b".encodeToByteArray(), "cube").placeholder,
    )
  }
}
