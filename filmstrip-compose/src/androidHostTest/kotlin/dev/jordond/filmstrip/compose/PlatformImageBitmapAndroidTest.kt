package dev.jordond.filmstrip.compose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.PlatformImage
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

@OptIn(InternalFilmstripApi::class)
class PlatformImageBitmapAndroidTest {
  private val bitmap =
    mockk<Bitmap>(relaxed = true) {
      every { width } returns 3
      every { height } returns 2
    }

  @Test
  fun `the bitmap is the image's own and not a copy`() {
    val image = PlatformImage(bitmap)

    val converted = image.toImageBitmap().shouldNotBeNull()

    converted.asAndroidBitmap() shouldBeSameInstanceAs image.asBitmap()
    converted.width shouldBe 3
    converted.height shouldBe 2
    verify(exactly = 0) { bitmap.copy(any(), any()) }
  }

  @Test
  fun `a closed image has nothing to convert`() {
    val image = PlatformImage(bitmap)
    image.close()

    image.toImageBitmap().shouldBeNull()
    verify { bitmap.recycle() }
  }
}
