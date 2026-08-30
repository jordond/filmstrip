package dev.jordond.filmstrip

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlayerConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * `preview` and `frame` hand their factories the same registry the export path gets, so a
 * resolver the host registered reaches a preview and a thumbnail strip too.
 */
class ComponentsReachFactoriesTest {
  @Test
  fun previewHandsItsComponentsToThePlayerEngineFactory() {
    val resolver = HostResolver()
    var seen: ComponentRegistry? = null
    val filmstrip =
      Filmstrip {
        addEffectResolver(resolver)
        addPlayerEngineFactory { _, components ->
          seen = components
          null
        }
      }

    filmstrip.preview(filmstrip.oneClip(), PlayerConfig())

    assertContains(assertNotNull(seen).effectResolvers, resolver)
  }

  @Test
  fun aFrameHandsItsComponentsToTheThumbnailSourceFactory() =
    runTest {
      val resolver = HostResolver()
      var seen: ComponentRegistry? = null
      val filmstrip =
        Filmstrip {
          addEffectResolver(resolver)
          addThumbnailSourceFactory { _, components ->
            seen = components
            null
          }
        }

      filmstrip.frame(filmstrip.oneClip(), 0.milliseconds)

      assertContains(assertNotNull(seen).effectResolvers, resolver)
    }

  private fun Filmstrip.oneClip(): EditComposition = composition { clip(MediaSource.of("/fixtures/clip.mp4")) }

  // Declines every spec. A class rather than a lambda, because the test recognises the instance it
  // registered and non-capturing lambdas of the same body share one.
  private class HostResolver : EffectResolver {
    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? = null
  }
}
