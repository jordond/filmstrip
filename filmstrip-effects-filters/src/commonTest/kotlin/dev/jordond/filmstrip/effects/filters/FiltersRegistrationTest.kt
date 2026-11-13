package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.effect.EffectResolver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Where the filter catalogue lands when the artifact registers itself.
 */
class FiltersRegistrationTest {
  @Test
  fun `the catalogue registers itself`() {
    val components = Filmstrip().components

    components.effectResolvers.filterIsInstance<FilterEffectResolver>() shouldHaveSize 1
    components.audioEffectResolvers.filterIsInstance<FilterAudioEffectResolver>() shouldHaveSize 1
  }

  @Test
  fun `an explicit call registers the catalogue once`() {
    val components = Filmstrip { filters() }.components

    components.effectResolvers.filterIsInstance<FilterEffectResolver>() shouldHaveSize 1
    components.audioEffectResolvers.filterIsInstance<FilterAudioEffectResolver>() shouldHaveSize 1
  }

  @Test
  fun `turning discovery off leaves the catalogue out`() {
    val components = Filmstrip { autoRegister(false) }.components

    components.effectResolvers.filterIsInstance<FilterEffectResolver>().shouldBeEmpty()
    components.audioEffectResolvers.filterIsInstance<FilterAudioEffectResolver>().shouldBeEmpty()
  }

  @Test
  fun `a resolver of the block's own goes ahead of the catalogue`() {
    val own = EffectResolver { _, _, _ -> null }

    val resolvers = Filmstrip { addEffectResolver(own) }.components.effectResolvers

    assertSame(own, resolvers.first())
    resolvers.last().shouldBeInstanceOf<FilterEffectResolver>()
  }
}
