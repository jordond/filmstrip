package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.player.contract.contractTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A step that throws while a thumbnail is drawn, the way an overlay picture too large to draw at its color does.
 *
 * The generator still hands back a frame, drawn without the step's effects, so the tile is refused with the step's
 * reason rather than delivered.
 */
class AppleThumbnailStepFailureMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a step that throws while drawing a tile refuses it with its reason`() =
    contractTest {
      val filmstrip =
        Filmstrip {
          playerBackend()
          addEffectResolver(ThrowingResolver)
        }

      val composition = appleFixtureComposition(listOf(Throwing))
      val received = filmstrip.frames(composition, listOf(500.milliseconds), 120).toList()

      try {
        val failure = received.single().shouldBeInstanceOf<FrameResult.Failure>()
        val error = failure.error.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>()
        error.specId shouldBe Throwing.id
        error.message shouldBe REASON
      } finally {
        received.forEach { (it as? FrameResult.Success)?.image?.close() }
      }
    }

  private object Throwing : EffectSpec {
    override val id: String = "dev.jordond.filmstrip.test.throwing"
  }

  private object ThrowingResolver : EffectResolver {
    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? =
      if (spec !is Throwing) {
        null
      } else {
        EffectResolution.Resolved(PlatformEffect(CoreImageEffect { _, _ -> throw IllegalStateException(REASON) }))
      }
  }

  private companion object {
    const val REASON = "The picture is too large to draw at its color."
  }
}
