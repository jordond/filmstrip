package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.camcorder.RecordedAt
import dev.jordond.filmstrip.effects.filters.pixel.PixelPalette
import dev.jordond.filmstrip.effects.filters.tape.TapeStock
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.fail

/**
 * Reset checked against the class itself. Every settable property is found by reflection and moved off its default,
 * so a knob added later is covered without anyone remembering to list it here.
 */
class EditStateResetTest {
  @Test
  fun `reset returns every field to a fresh state`() {
    val edit = EditState()
    val parts: (EditState) -> List<Any> =
      { listOf(it, it.pixelLook, it.camcorderDisplay, it.textMotion, it.watermarkMotion) }
    val moved = parts(edit).flatMap(::move)
    moved.shouldNotBeEmpty()

    edit.reset()

    val fresh = EditState()
    parts(edit).zip(parts(fresh)).forEach { (actual, expected) -> compare(actual, expected) }
  }

  private fun move(target: Any): List<String> =
    properties(target).map { (getter, setter) ->
      val before = getter.invoke(target)
      val after = other(before, setter.parameterTypes.single(), getter.name)
      setter.invoke(target, after)
      withClue("${getter.name} did not move") { getter.invoke(target) shouldNotBe before }
      getter.name
    }

  private fun compare(
    actual: Any,
    fresh: Any,
  ) {
    properties(actual).forEach { (getter, _) ->
      withClue("${actual::class.simpleName}.${getter.name}") { getter.invoke(actual) shouldBe getter.invoke(fresh) }
    }
  }

  private fun properties(target: Any): List<Pair<Method, Method>> {
    val methods = target.javaClass.methods
    return methods
      .filter { it.name.startsWith("set") && it.parameterCount == 1 }
      .mapNotNull { setter ->
        val name = setter.name.removePrefix("set")
        val getter = methods.firstOrNull { it.parameterCount == 0 && (it.name == "get$name" || it.name == "is$name") }
        getter?.let { it to setter }
      }.sortedBy { it.first.name }
  }

  private fun other(
    value: Any?,
    type: Class<*>,
    name: String,
  ): Any =
    when {
      value is Boolean -> !value
      value is Float -> value + 0.25f
      type == Integer::class.java -> (value as Int?)?.plus(1) ?: 7
      value is Int -> value + 1
      value is String -> value + "changed"
      type.isEnum -> type.enumConstants.first { it != value }
      type == ColorMatrix::class.java -> ColorMatrix(rb = 0.5f)
      type == AspectRatio::class.java -> if (value == AspectRatio.Square) AspectRatio.Portrait else AspectRatio.Square
      type == Size::class.java -> Size(640, 360)
      type == NormalizedRect::class.java -> NormalizedRect(0.2f, 0.3f, 0.7f, 0.8f)
      type == Anchor::class.java -> Anchor(0.3f, 0.6f)
      type == TapeStock::class.java -> BetaSpeed.entries.last()
      type == ImageSource::class.java -> ImageSource.Path("/tmp/logo.png")
      type == PixelPalette::class.java -> PixelPalette.GameBoy
      type == RecordedAt::class.java -> RecordedAt(1989, 12, 25)
      else -> fail("No other value for $name of type ${type.simpleName}, add one here")
    }
}
