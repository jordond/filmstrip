package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.picture.PictureContainer
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class FrameDelaysTest {
  @Test
  fun `a gif shows a stored zero and ten as 100 ms and twenty as 20 ms`() {
    listOf(0, 10, 20).map { shownFrameDelay(PictureContainer.Gif, it.milliseconds) } shouldBe
      listOf(100.milliseconds, 100.milliseconds, 20.milliseconds)
  }

  @Test
  fun `a gif keeps a delay past ten milliseconds as it is stored`() {
    listOf(11, 30, 250).map { shownFrameDelay(PictureContainer.Gif, it.milliseconds) } shouldBe
      listOf(11.milliseconds, 30.milliseconds, 250.milliseconds)
  }

  @Test
  fun `an apng and a webp replace only a stored zero`() {
    for (container in listOf(PictureContainer.Png, PictureContainer.Webp)) {
      listOf(0, 8, 10, 20).map { shownFrameDelay(container, it.milliseconds) } shouldBe
        listOf(100.milliseconds, 8.milliseconds, 10.milliseconds, 20.milliseconds)
    }
  }

  @Test
  fun `a gif with no loop block plays once and a stored zero plays forever`() {
    framePlays(PictureContainer.Gif, null) shouldBe FramePlays.Times(1)
    framePlays(PictureContainer.Gif, 0) shouldBe FramePlays.Forever
  }

  @Test
  fun `a gif that stores a loop count plays it and once more`() {
    framePlays(PictureContainer.Gif, 1) shouldBe FramePlays.Times(2)
    framePlays(PictureContainer.Gif, 3) shouldBe FramePlays.Times(4)
  }

  @Test
  fun `an apng and a webp play the count they store and zero plays forever`() {
    for (container in listOf(PictureContainer.Png, PictureContainer.Webp)) {
      framePlays(container, 0) shouldBe FramePlays.Forever
      framePlays(container, 1) shouldBe FramePlays.Times(1)
      framePlays(container, 3) shouldBe FramePlays.Times(3)
    }
  }

  @Test
  fun `a container that does not animate is refused`() {
    assertFailsWith<IllegalArgumentException> { shownFrameDelay(PictureContainer.Jpeg, 10.milliseconds) }
    assertFailsWith<IllegalArgumentException> { framePlays(PictureContainer.Heif, 0) }
  }

  @Test
  fun `a negative delay and a negative count are refused`() {
    assertFailsWith<IllegalArgumentException> { shownFrameDelay(PictureContainer.Gif, (-1).milliseconds) }
    assertFailsWith<IllegalArgumentException> { framePlays(PictureContainer.Gif, -1) }
  }

  @Test
  fun `an apng or a webp with no stored count is refused`() {
    assertFailsWith<IllegalArgumentException> { framePlays(PictureContainer.Png, null) }
    assertFailsWith<IllegalArgumentException> { framePlays(PictureContainer.Webp, null) }
  }
}
