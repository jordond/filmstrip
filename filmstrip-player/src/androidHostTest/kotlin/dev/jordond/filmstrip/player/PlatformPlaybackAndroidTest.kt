package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration

/**
 * What the Android backend hands back when nothing installed a Context.
 */
class PlatformPlaybackAndroidTest {
  private val components = ComponentRegistry.Builder().build()

  @BeforeTest
  fun forgetTheContext() {
    FilmstripContext.clear()
  }

  @Test
  fun `an engine built without a context fails every load with the missing install`() {
    val engine = createPlayerEngine(PlayerConfig(), components).shouldNotBeNull()
    var result: SetCompositionResult? = null

    engine.setComposition(SetCompositionRequest(EditComposition(tracks = emptyList()))) { result = it }

    val error = result.shouldBeInstanceOf<SetCompositionResult.Failure>().error
    error.shouldBeInstanceOf<PlaybackError.Underlying>().message shouldBe FilmstripContext.MISSING_CONTEXT
    engine.dispose()
  }

  @Test
  fun `a thumbnail source built without a context fails every request with the missing install`() {
    val request = ThumbnailRequest(EditComposition(tracks = emptyList()), Duration.ZERO, 90, 1L, precise = false)
    var result: ThumbnailResult? = null

    createThumbnailSource(request, components).shouldNotBeNull().requestThumbnail(request) { result = it }

    val error = result.shouldBeInstanceOf<ThumbnailResult.Failure>().error
    error.shouldBeInstanceOf<FilmstripError.Underlying>().message shouldBe FilmstripContext.MISSING_CONTEXT
  }
}
