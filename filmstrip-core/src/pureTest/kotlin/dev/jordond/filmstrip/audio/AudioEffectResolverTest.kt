package dev.jordond.filmstrip.audio

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlayerConfig
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalFilmstripApi::class)
class AudioEffectResolverTest {
  private val attributes = AudioAttributes(TimeRange(0.seconds, 2.seconds))
  private val spec = Named("test.hum")

  @Test
  fun `the first resolver to claim a spec wins`() {
    val asked = mutableListOf<String>()
    val resolvers =
      listOf(
        Recording("declines", asked) { null },
        Recording("claims", asked) { refusal("claims") },
        Recording("never asked", asked) { refusal("never asked") },
      )

    resolvers.resolveAudioEffect(spec, attributes) shouldBe refusal("claims")
    asked shouldContainExactly listOf("declines", "claims")
  }

  @Test
  fun `a spec no resolver claims resolves to nothing`() {
    listOf(AudioEffectResolver { _, _ -> null }).resolveAudioEffect(spec, attributes).shouldBeNull()
    emptyList<AudioEffectResolver>().resolveAudioEffect(spec, attributes).shouldBeNull()
  }

  @Test
  fun `a resolver is handed the spec and the attributes it was asked with`() {
    var seen: Pair<AudioEffectSpec, AudioAttributes>? = null
    val resolver =
      AudioEffectResolver { asked, with ->
        seen = asked to with
        null
      }

    listOf(resolver).resolveAudioEffect(spec, attributes)

    seen shouldBe (spec to attributes)
  }

  @Test
  fun `a later registration is asked first and reaches the player factory`() {
    val first = AudioEffectResolver { _, _ -> refusal("first") }
    val second = AudioEffectResolver { _, _ -> refusal("second") }
    var seen: FilmstripComponents? = null
    val filmstrip =
      Filmstrip {
        addAudioEffectResolver(first)
        addAudioEffectResolver(second)
        addPlayerEngineFactory { _, components ->
          seen = components
          null
        }
      }

    filmstrip.preview(compositionOf { clip(MediaSource.of("/fixtures/clip.mp4")) }, PlayerConfig())

    val resolvers = seen.shouldNotBeNull().audioEffectResolvers
    resolvers.resolveAudioEffect(spec, attributes) shouldBe refusal("second")
    resolvers.size shouldBe 2
  }

  private fun refusal(by: String) = AudioEffectResolution.Unsupported(spec.id, by)

  private class Recording(
    private val name: String,
    private val asked: MutableList<String>,
    private val answer: () -> AudioEffectResolution?,
  ) : AudioEffectResolver {
    override fun resolve(
      spec: AudioEffectSpec,
      attributes: AudioAttributes,
    ): AudioEffectResolution? {
      asked += name
      return answer()
    }
  }

  private data class Named(
    override val id: String,
  ) : AudioEffectSpec
}
