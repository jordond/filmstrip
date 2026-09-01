package dev.jordond.filmstrip.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.jordond.filmstrip.edit.AudioSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class FilmstripFrameTest {
  @Test
  fun `the frame asked for is the exact one at the position`() =
    runTest {
      val source = FakeThumbnailSource()
      val filmstrip = filmstripWith(source)

      val runtime = ComposeRuntime(this)
      var frame: ImageBitmap? = null
      runtime.setContent {
        frame = rememberFilmstripFrame(filmstrip, testComposition("poster.mp4"), AT, HEIGHT_PX)
      }

      val request = source.requests.single()
      request.position shouldBe AT
      request.heightPx shouldBe HEIGHT_PX
      request.precise shouldBe true

      val drawable = frame.shouldNotBeNull()
      drawable.width shouldBe FakeThumbnailSource.FRAME_WIDTH
      drawable.height shouldBe FakeThumbnailSource.FRAME_HEIGHT

      runtime.dispose()
    }

  @Test
  fun `a new position replaces the frame and closes the one it had`() =
    runTest {
      val source = FakeThumbnailSource()
      val filmstrip = filmstripWith(source)
      var at by mutableStateOf(AT)

      val runtime = ComposeRuntime(this)
      var frame: ImageBitmap? = null
      runtime.setContent {
        frame = rememberFilmstripFrame(filmstrip, testComposition("poster.mp4"), at, HEIGHT_PX)
      }
      val first = source.images.single()
      val firstDrawable = frame.shouldNotBeNull()

      at = LATER
      runtime.settle()

      source.requested shouldBe listOf(AT, LATER)
      first.isClosed shouldBe true
      source.images.last().isClosed shouldBe false
      (frame === firstDrawable) shouldBe false

      runtime.dispose()
    }

  @Test
  fun `the last frame stays in place while the next one renders`() =
    runTest {
      val source = FakeThumbnailSource()
      val filmstrip = filmstripWith(source)
      var at by mutableStateOf(AT)

      val runtime = ComposeRuntime(this)
      var frame: ImageBitmap? = null
      runtime.setContent {
        frame = rememberFilmstripFrame(filmstrip, testComposition("poster.mp4"), at, HEIGHT_PX)
      }
      val first = source.images.single()

      source.autoDeliver = false
      at = LATER
      runtime.settle()

      frame.shouldNotBeNull()
      first.isClosed shouldBe false

      source.deliver()
      runtime.settle()
      first.isClosed shouldBe true

      runtime.dispose()
    }

  @Test
  fun `a fetch in flight is cancelled when the position moves`() =
    runTest {
      val source = FakeThumbnailSource()
      source.autoDeliver = false
      val filmstrip = filmstripWith(source)
      var at by mutableStateOf(AT)

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        rememberFilmstripFrame(filmstrip, testComposition("poster.mp4"), at, HEIGHT_PX)
      }
      source.cancelled shouldBe emptyList()

      at = LATER
      runtime.settle()

      source.cancelled shouldBe listOf(AT)
      source.requested shouldBe listOf(AT, LATER)

      runtime.dispose()
    }

  @Test
  fun `an edit that changes nothing a frame is rendered from keeps it`() =
    runTest {
      val source = FakeThumbnailSource()
      val filmstrip = filmstripWith(source)
      var composition by mutableStateOf(testComposition("poster.mp4"))

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        rememberFilmstripFrame(filmstrip, composition, AT, HEIGHT_PX)
      }
      val held = source.images.single()

      composition = composition.withAudio(AudioSpec.Mute)
      runtime.settle()

      source.requested shouldBe listOf(AT)
      held.isClosed shouldBe false

      composition = testComposition("other.mp4")
      runtime.settle()

      source.requested shouldBe listOf(AT, AT)
      held.isClosed shouldBe true

      runtime.dispose()
    }

  @Test
  fun `a render that fails clears the frame`() =
    runTest {
      val source = FakeThumbnailSource()
      val filmstrip = filmstripWith(source)
      var at by mutableStateOf(AT)

      val runtime = ComposeRuntime(this)
      var frame: ImageBitmap? = null
      runtime.setContent {
        frame = rememberFilmstripFrame(filmstrip, testComposition("poster.mp4"), at, HEIGHT_PX)
      }
      val held = source.images.single()
      frame.shouldNotBeNull()

      source.failing = true
      at = LATER
      runtime.settle()

      frame shouldBe null
      held.isClosed shouldBe true

      runtime.dispose()
    }

  @Test
  fun `leaving composition closes the frame`() =
    runTest {
      val source = FakeThumbnailSource()
      val filmstrip = filmstripWith(source)
      var shown by mutableStateOf(true)

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        if (shown) rememberFilmstripFrame(filmstrip, testComposition("poster.mp4"), AT, HEIGHT_PX)
      }
      val held = source.images.single()
      held.isClosed shouldBe false

      shown = false
      runtime.settle()

      held.isClosed shouldBe true

      runtime.dispose()
    }

  private companion object {
    const val HEIGHT_PX = FakeThumbnailSource.FRAME_HEIGHT
    val AT: Duration = 1.seconds
    val LATER: Duration = 3.seconds
  }
}
