package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.canonicalOrder
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What a [Camcorder] declares to the planner, and the builders that describe it.
 */
class CamcorderTest {
  @Test
  fun `declares its place beneath overlays and that it needs SDR`() {
    val camcorder = Camcorder(listOf(OsdElement.Rec()))

    camcorder.stage shouldBe EffectStage.Composite
    camcorder.beneathOverlays shouldBe true
    camcorder.requiresSdr shouldBe true
  }

  @Test
  fun `is inert with no elements and not with one`() {
    Camcorder(emptyList()).isInert shouldBe true
    Camcorder(listOf(OsdElement.Rec())).isInert shouldBe false
  }

  @Test
  fun `compares by value`() {
    Camcorder(listOf(OsdElement.Tape(TapeSpeed.LP))) shouldBe Camcorder(listOf(OsdElement.Tape(TapeSpeed.LP)))
  }

  @Test
  fun `refuses a text size out of range`() {
    shouldThrow<IllegalArgumentException> { Camcorder(listOf(OsdElement.Rec()), textSize = 0.5f) }
  }

  @Test
  fun `the builder declares each element in order`() {
    val start = RecordedAt(1997, 6, 14, 15, 32)
    val effects =
      EffectsBuilder()
        .camcorder {
          rec()
          date(start)
          clock()
          battery(0.25f)
          counter(10.seconds, withFrames = true)
        }.build()

    val camcorder = effects.single().shouldBeInstanceOf<Camcorder>()
    camcorder.recordedAt shouldBe start
    camcorder.elements shouldContainExactly
      listOf(
        OsdElement.Rec(),
        OsdElement.Date(),
        OsdElement.Clock(),
        OsdElement.Battery(0.25f),
        OsdElement.Counter(10.seconds, withFrames = true),
      )
  }

  @Test
  fun `a camcorder inside vhs is recorded ahead of the tape`() {
    val effects = EffectsBuilder().vhs { camcorder { rec() } }.build()

    effects.map { it.id } shouldContainExactly listOf(Camcorder.ID, Vhs.ID)
    canonicalOrder(effects).map { it.id } shouldContainExactly listOf(Camcorder.ID, Vhs.ID)
  }

  @Test
  fun `a second camcorder inside one vhs fails by name`() {
    val error =
      shouldThrow<IllegalArgumentException> {
        EffectsBuilder().vhs {
          camcorder { rec() }
          camcorder { tape(TapeSpeed.SP) }
        }
      }

    error.message shouldBe "A vhs records one camcorder, and this vhs already has one."
  }

  @Test
  fun `a camcorder after vhs stays over the tape`() {
    val effects =
      EffectsBuilder()
        .vhs()
        .camcorder { rec() }
        .build()

    canonicalOrder(effects).map { it.id } shouldContainExactly listOf(Vhs.ID, Camcorder.ID)
  }
}
