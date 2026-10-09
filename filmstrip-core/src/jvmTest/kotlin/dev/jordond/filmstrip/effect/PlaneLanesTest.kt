package dev.jordond.filmstrip.effect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests what the plane lanes factory writes. They cover the node's name and argument keys, the planes written in slot
 * order whatever order they are named in, the target list with its slots, and the inputs the factory refuses.
 */
class PlaneLanesTest {
  @Test
  fun `the node carries the name, the planes and the format`() {
    val node = PlaneLanes.node(listOf("y", "u", "v"), "gbrpf32le")

    assertEquals(
      FilterNode(
        "filmstrip_planelanes",
        listOf(FilterArgument("planes", "y+u+v"), FilterArgument("format", "gbrpf32le")),
      ),
      node,
    )
  }

  @Test
  fun `naming the same planes in another order builds an equal node`() {
    assertEquals(
      PlaneLanes.node(listOf("g", "b", "r"), "gbrpf32le"),
      PlaneLanes.node(listOf("r", "g", "b"), "gbrpf32le"),
    )
    assertEquals(
      PlaneLanes.node(listOf("y", "u", "v", "a"), "gbrapf32le"),
      PlaneLanes.node(listOf("a", "v", "y", "u"), "gbrapf32le"),
    )
  }

  @Test
  fun `the planes are written in slot order`() {
    val node = PlaneLanes.node(listOf("a", "r", "g", "b"), "gbrapf32le")

    assertEquals("g+b+r+a", node.arguments.single { it.key == PlaneLanes.PLANES }.value)
  }

  @Test
  fun `each family's planes are written in the order its slots list them`() {
    listOf(PlaneLanes.RGB_SLOTS, PlaneLanes.YCBCR_SLOTS).forEach { slots ->
      val node = PlaneLanes.node(slots.reversed(), "gbrapf32le")

      assertEquals(slots.joinToString("+"), node.arguments.single { it.key == PlaneLanes.PLANES }.value)
    }
  }

  @Test
  fun `the two families share the alpha slot and nothing else`() {
    assertEquals(PlaneLanes.RGB_SLOTS.size, PlaneLanes.YCBCR_SLOTS.size)
    assertEquals(listOf("a"), PlaneLanes.RGB_SLOTS.intersect(PlaneLanes.YCBCR_SLOTS.toSet()).toList())
    assertEquals(PlaneLanes.RGB_SLOTS.last(), PlaneLanes.YCBCR_SLOTS.last())
  }

  @Test
  fun `the constants name what the node holds`() {
    val node = PlaneLanes.node(listOf("r", "g", "b"), "gbrpf32le")

    assertEquals(PlaneLanes.NAME, node.name)
    assertEquals(listOf(PlaneLanes.PLANES, PlaneLanes.FORMAT), node.arguments.map { it.key })
  }

  @Test
  fun `every listed target builds a node`() {
    PlaneLanes.TARGETS.forEach { target ->
      val node = PlaneLanes.node(listOf("y", "u", "v"), target.format)

      assertEquals(target.format, node.arguments.single { it.key == PlaneLanes.FORMAT }.value)
    }
  }

  @Test
  fun `each target carries the slots it fills`() {
    assertEquals(listOf("g", "b", "r"), PlaneLanes.OPAQUE.slots)
    assertFalse(PlaneLanes.OPAQUE.hasAlpha)
    assertEquals(PlaneLanes.RGB_SLOTS, PlaneLanes.WITH_ALPHA.slots)
    assertTrue(PlaneLanes.WITH_ALPHA.hasAlpha)
    assertEquals(listOf("gbrpf32le", "gbrapf32le"), PlaneLanes.TARGETS.map { it.format })
  }

  @Test
  fun `a target is found by its format and nothing else is`() {
    PlaneLanes.TARGETS.forEach { assertSame(it, PlaneLanes.target(it.format)) }
    assertNull(PlaneLanes.target("yuv444p16le"))
    assertNull(PlaneLanes.target("grayf32le"))
  }

  @Test
  fun `no planes, an unknown plane, a repeated plane or a format off the target list is refused`() {
    assertFailsWith<IllegalArgumentException> { PlaneLanes.node(emptyList(), "gbrpf32le") }
    assertFailsWith<IllegalArgumentException> { PlaneLanes.node(listOf("y", "q"), "gbrpf32le") }
    assertFailsWith<IllegalArgumentException> { PlaneLanes.node(listOf("y", "y"), "gbrpf32le") }
    assertFailsWith<IllegalArgumentException> { PlaneLanes.node(listOf("y", "u", "v"), " ") }
    assertFailsWith<IllegalArgumentException> { PlaneLanes.node(listOf("y", "u", "v"), "yuv444p16le") }
    assertFailsWith<IllegalArgumentException> { PlaneLanes.node(listOf("y", "u", "v"), "grayf32le") }
  }
}
