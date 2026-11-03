package dev.jordond.filmstrip.effect

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests what the plane lanes factory writes. They cover the node's name and argument keys, the planes written in slot
 * order whatever order they are named in, the target list with its slots, and the inputs the factory refuses.
 */
class PlaneLanesTest {
  @Test
  fun `the node carries the name, the planes and the format`() {
    val node = PlaneLanes.node(listOf("y", "u", "v"), "gbrpf32le")

    node shouldBe
      FilterNode(
        "filmstrip_planelanes",
        listOf(FilterArgument("planes", "y+u+v"), FilterArgument("format", "gbrpf32le")),
      )
  }

  @Test
  fun `naming the same planes in another order builds an equal node`() {
    PlaneLanes.node(listOf("r", "g", "b"), "gbrpf32le") shouldBe PlaneLanes.node(listOf("g", "b", "r"), "gbrpf32le")
    PlaneLanes.node(listOf("a", "v", "y", "u"), "gbrapf32le") shouldBe
      PlaneLanes.node(listOf("y", "u", "v", "a"), "gbrapf32le")
  }

  @Test
  fun `the planes are written in slot order`() {
    val node = PlaneLanes.node(listOf("a", "r", "g", "b"), "gbrapf32le")

    node.arguments.single { it.key == PlaneLanes.PLANES }.value shouldBe "g+b+r+a"
  }

  @Test
  fun `each family's planes are written in the order its slots list them`() {
    listOf(PlaneLanes.RGB_SLOTS, PlaneLanes.YCBCR_SLOTS).forEach { slots ->
      val node = PlaneLanes.node(slots.reversed(), "gbrapf32le")

      node.arguments.single { it.key == PlaneLanes.PLANES }.value shouldBe slots.joinToString("+")
    }
  }

  @Test
  fun `the two families share the alpha slot and nothing else`() {
    PlaneLanes.YCBCR_SLOTS.size shouldBe PlaneLanes.RGB_SLOTS.size
    PlaneLanes.RGB_SLOTS.intersect(PlaneLanes.YCBCR_SLOTS.toSet()).toList() shouldBe listOf("a")
    PlaneLanes.YCBCR_SLOTS.last() shouldBe PlaneLanes.RGB_SLOTS.last()
  }

  @Test
  fun `the constants name what the node holds`() {
    val node = PlaneLanes.node(listOf("r", "g", "b"), "gbrpf32le")

    node.name shouldBe PlaneLanes.NAME
    node.arguments.map { it.key } shouldBe listOf(PlaneLanes.PLANES, PlaneLanes.FORMAT)
  }

  @Test
  fun `every listed target builds a node`() {
    PlaneLanes.TARGETS.forEach { target ->
      val node = PlaneLanes.node(listOf("y", "u", "v"), target.format)

      node.arguments.single { it.key == PlaneLanes.FORMAT }.value shouldBe target.format
    }
  }

  @Test
  fun `each target carries the slots it fills`() {
    PlaneLanes.OPAQUE.slots shouldBe listOf("g", "b", "r")
    PlaneLanes.OPAQUE.hasAlpha.shouldBeFalse()
    PlaneLanes.WITH_ALPHA.slots shouldBe PlaneLanes.RGB_SLOTS
    PlaneLanes.WITH_ALPHA.hasAlpha.shouldBeTrue()
    PlaneLanes.TARGETS.map { it.format } shouldBe listOf("gbrpf32le", "gbrapf32le")
  }

  @Test
  fun `a target is found by its format and nothing else is`() {
    PlaneLanes.TARGETS.forEach { PlaneLanes.target(it.format) shouldBeSameInstanceAs it }
    PlaneLanes.target("yuv444p16le").shouldBeNull()
    PlaneLanes.target("grayf32le").shouldBeNull()
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
