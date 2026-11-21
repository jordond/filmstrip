package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.canonicalOrder
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.OsdCorner
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.effects.filters.camcorder.OsdParams
import dev.jordond.filmstrip.effects.filters.camcorder.RecordedAt
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What `digicam { }` adds to an edit.
 */
class DigicamBuilderTest {
  @Test
  fun `an empty block adds the default digicam and no stamp`() {
    val effects = EffectsBuilder().digicam().build()

    effects shouldContainExactly listOf(Digicam())
  }

  @Test
  fun `the block sets every control`() {
    val effects =
      EffectsBuilder()
        .digicam {
          sharpness = 0.25f
          noise = 0.75f
          clip = 0.125f
          seed = 9
        }.build()

    effects shouldContainExactly listOf(Digicam(0.25f, 0.75f, 0.125f, 9))
  }

  @Test
  fun `a stamp is drawn after the digicam so it stays clean`() {
    val effects = EffectsBuilder().digicam { stamp() }.build()

    effects.map { it.id } shouldContainExactly listOf(Digicam.ID, Camcorder.ID)
    canonicalOrder(effects).map { it.id } shouldContainExactly listOf(Digicam.ID, Camcorder.ID)
    effects.last() shouldBe
      Camcorder(listOf(OsdElement.Stamp()), Camcorder.DEFAULT_RECORDED_AT, OsdParams.STAMP_TEXT_SIZE)
  }

  @Test
  fun `a stamp takes its date and corner`() {
    val date = RecordedAt(2003, 8, 21)
    val effects = EffectsBuilder().digicam { stamp(date, OsdCorner.BottomLeft) }.build()

    effects.last() shouldBe Camcorder(listOf(OsdElement.Stamp(OsdCorner.BottomLeft)), date, OsdParams.STAMP_TEXT_SIZE)
  }
}
