package dev.jordond.filmstrip

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What a [Filmstrip] holds when the bundle's artifacts register themselves.
 */
class AutoRegistrationTest {
  @Test
  fun `an empty block discovers this target's export backend`() {
    val components = Filmstrip().components

    components.backends.map { it.name } shouldBe listOf(DEFAULT_TRANSFORM_BACKEND)
    components.exportEngineFactories.size shouldBe 1
    components.playerEngineFactories.size shouldBe 1
    components.thumbnailSourceFactories.size shouldBe 1
    components.effectResolvers.size shouldBe 1
  }

  @Test
  fun `all backends registers the built-in catalogue once`() {
    Filmstrip { allBackends() }.components.effectResolvers.size shouldBe 1
  }

  @Test
  fun `an explicit transform backend is not registered twice`() {
    val components = Filmstrip { transformBackend() }.components

    components.backends.map { it.name } shouldBe listOf(DEFAULT_TRANSFORM_BACKEND)
    components.exportEngineFactories.size shouldBe 1
  }

  @Test
  fun `turning discovery off leaves no export backend`() {
    val components = Filmstrip { autoRegister(false) }.components

    components.backends shouldBe emptyList()
    components.exportEngineFactories shouldBe emptyList()
    components.playerEngineFactories shouldBe emptyList()
  }
}
