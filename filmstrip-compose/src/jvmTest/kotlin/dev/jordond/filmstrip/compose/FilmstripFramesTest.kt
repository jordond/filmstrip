package dev.jordond.filmstrip.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.Filmstrip
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class FilmstripFramesTest {
  @Test
  fun `a window that overruns the cap evicts the frame furthest from the strip`() =
    runTest {
      val source = FakeThumbnailSource()
      val strip = strip(source, maxBytes = WINDOW * FakeThumbnailSource.FRAME_BYTES - 1)

      // Mid-strip, so the window has items either side of it and the eviction has a choice to make.
      strip.frames.onVisibleRange(MIDDLE, MIDDLE)
      strip.runtime.settle()

      source.requested shouldBe (MIDDLE - OVERSCAN..MIDDLE + OVERSCAN).map { POSITIONS[it] }
      strip.frames.heldBytes shouldBe (WINDOW - 1) * FakeThumbnailSource.FRAME_BYTES
      strip.loaded() shouldBe WINDOW - 1

      // Both edges of the window sit the same distance from what is on screen, and the one further
      // along the strip goes first.
      strip.frames.holds(MIDDLE + OVERSCAN) shouldBe false
      strip.frames.holds(MIDDLE - OVERSCAN) shouldBe true

      // Read through the public accessor too, so a decode that holds but fails to draw still fails
      // the test.
      val drawable = strip.frames[MIDDLE - OVERSCAN].shouldNotBeNull()
      drawable.width shouldBe FakeThumbnailSource.FRAME_WIDTH
      drawable.height shouldBe FakeThumbnailSource.FRAME_HEIGHT
      strip.frames[MIDDLE + OVERSCAN] shouldBe null

      source.images.last().isClosed shouldBe true
      source.images.dropLast(1).count { it.isClosed } shouldBe 0

      strip.runtime.dispose()
    }

  @Test
  fun `a frame left behind by the window is closed`() =
    runTest {
      val source = FakeThumbnailSource()
      val strip = strip(source, overscan = 0)

      strip.frames.onVisibleRange(0, 0)
      strip.runtime.settle()
      strip.loaded() shouldBe 1

      strip.frames.onVisibleRange(MIDDLE, MIDDLE)
      strip.runtime.settle()

      source.images.first().isClosed shouldBe true
      strip.frames.holds(0) shouldBe false
      strip.frames.heldBytes shouldBe FakeThumbnailSource.FRAME_BYTES

      strip.runtime.dispose()
    }

  @Test
  fun `a request the strip scrolls past is cancelled`() =
    runTest {
      val source = FakeThumbnailSource()
      source.autoDeliver = false
      val strip = strip(source, overscan = 0)

      strip.frames.onVisibleRange(0, 0)
      strip.runtime.settle()
      source.requested shouldBe listOf(POSITIONS[0])
      source.cancelled shouldBe emptyList()

      strip.frames.onVisibleRange(MIDDLE, MIDDLE)
      strip.runtime.settle()

      source.cancelled shouldBe listOf(POSITIONS[0])
      source.requested shouldBe listOf(POSITIONS[0], POSITIONS[MIDDLE])

      strip.runtime.dispose()
    }

  @Test
  fun `an edit that changes a rendered frame drops what was held`() =
    runTest {
      val source = FakeThumbnailSource()
      val filmstrip = filmstripWith(source)
      var composition by mutableStateOf(testComposition("first.mp4"))

      val runtime = ComposeRuntime(this)
      lateinit var frames: FilmstripFrames
      runtime.setContent {
        frames = rememberFilmstripFrames(filmstrip, composition, POSITIONS, HEIGHT_PX, overscan = 0)
      }

      frames.onVisibleRange(0, 0)
      runtime.settle()
      val first = source.images.single()
      first.isClosed shouldBe false

      composition = testComposition("second.mp4")
      runtime.settle()

      first.isClosed shouldBe true
      source.requested shouldBe listOf(POSITIONS[0], POSITIONS[0])
      frames.heldBytes shouldBe FakeThumbnailSource.FRAME_BYTES

      runtime.dispose()
    }

  @Test
  fun `a strip that only moves its positions keeps the frames both sets ask for`() =
    runTest {
      val source = FakeThumbnailSource()
      val filmstrip = filmstripWith(source)
      var positions by mutableStateOf(POSITIONS)

      val runtime = ComposeRuntime(this)
      lateinit var frames: FilmstripFrames
      runtime.setContent {
        frames =
          rememberFilmstripFrames(filmstrip, testComposition("strip.mp4"), positions, HEIGHT_PX, overscan = 0)
      }

      frames.onVisibleRange(0, 0)
      runtime.settle()
      val held = source.images.single()

      // One step in: every position survives, at twice the index it had.
      positions = POSITIONS.flatMap { listOf(it, it + HALF_STEP) }
      runtime.settle()

      held.isClosed shouldBe false
      frames.holds(0) shouldBe true
      frames.heldBytes shouldBe FakeThumbnailSource.FRAME_BYTES
      source.requested shouldBe listOf(POSITIONS[0])

      runtime.dispose()
    }

  @Test
  fun `a position no new tile sits at is closed`() =
    runTest {
      val source = FakeThumbnailSource()
      val filmstrip = filmstripWith(source)
      var positions by mutableStateOf(POSITIONS)

      val runtime = ComposeRuntime(this)
      lateinit var frames: FilmstripFrames
      runtime.setContent {
        frames =
          rememberFilmstripFrames(filmstrip, testComposition("strip.mp4"), positions, HEIGHT_PX, overscan = 0)
      }

      frames.onVisibleRange(0, 0)
      runtime.settle()
      val held = source.images.single()

      positions = POSITIONS.map { it + HALF_STEP }
      runtime.settle()

      held.isClosed shouldBe true
      source.requested shouldBe listOf(POSITIONS[0], POSITIONS[0] + HALF_STEP)
      frames.heldBytes shouldBe FakeThumbnailSource.FRAME_BYTES

      runtime.dispose()
    }

  private fun TestScope.strip(
    source: FakeThumbnailSource,
    maxBytes: Long = FilmstripFramesDefaults.MaxBytes,
    overscan: Int = OVERSCAN,
  ): StripUnderTest {
    val filmstrip: Filmstrip = filmstripWith(source)
    val runtime = ComposeRuntime(this)
    lateinit var frames: FilmstripFrames

    runtime.setContent {
      frames =
        rememberFilmstripFrames(
          filmstrip = filmstrip,
          composition = testComposition("strip.mp4"),
          positions = POSITIONS,
          heightPx = HEIGHT_PX,
          maxBytes = maxBytes,
          overscan = overscan,
        )
    }

    return StripUnderTest(runtime, frames)
  }

  /**
   * One strip, and the runtime driving it.
   */
  private class StripUnderTest(
    val runtime: ComposeRuntime,
    val frames: FilmstripFrames,
  ) {
    fun loaded(): Int = (0 until frames.count).count { frames.holds(it) }
  }

  private companion object {
    const val HEIGHT_PX = FakeThumbnailSource.FRAME_HEIGHT
    const val OVERSCAN = 2
    const val WINDOW = OVERSCAN * 2 + 1

    /**
     * Far enough into the strip that the window sits inside it at both edges.
     */
    const val MIDDLE = 8

    val POSITIONS: List<Duration> = List(20) { (it * 250).milliseconds }

    /**
     * Half the gap between two positions, which is where a zoom step puts the ones it adds.
     */
    val HALF_STEP: Duration = 125.milliseconds
  }
}
