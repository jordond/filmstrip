package dev.jordond.filmstrip

import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.player.PlayerEngineFactory
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * What the umbrella artifact registers on a [Filmstrip], and where a caller's own registrations land.
 */
class AllBackendsTest {
  @Test
  fun `all backends registers the player and this target's export backend`() {
    val components = Filmstrip { allBackends() }.components

    components.backends.map { it.name } shouldBe listOf(DEFAULT_TRANSFORM_BACKEND)
    components.playerEngineFactories.size shouldBe 1
    components.thumbnailSourceFactories.size shouldBe 1
    components.exportEngineFactories.size shouldBe 1
  }

  @Test
  fun `registrations after all backends override the bundled ones`() {
    val resolver = EffectResolver { _, _, _ -> null }
    val engine = PlayerEngineFactory { _, _ -> error("never built here") }

    val components =
      Filmstrip {
        allBackends()
        addEffectResolver(resolver)
        addPlayerEngineFactory(engine)
        addBackendInfo(BackendInfo(name = "custom", artifact = "dev.jordond.filmstrip.test:custom"))
      }.components

    assertSame(resolver, components.effectResolvers.first())
    assertSame(engine, components.playerEngineFactories.first())
    components.playerEngineFactories.size shouldBe 2
    components.backends.map { it.name } shouldBe listOf(DEFAULT_TRANSFORM_BACKEND, "custom")
  }

  @Test
  fun `the transform backend alone registers no player`() {
    val components =
      FilmstripBuilder()
        .autoRegister(false)
        .transformBackend()
        .build()
        .components

    components.backends.map { it.name } shouldBe listOf(DEFAULT_TRANSFORM_BACKEND)
    components.exportEngineFactories.size shouldBe 1
    components.playerEngineFactories.size shouldBe 0
    components.thumbnailSourceFactories.size shouldBe 0
  }
}

/**
 * The name the export backend this target bundles reports itself under.
 */
internal expect val DEFAULT_TRANSFORM_BACKEND: String
