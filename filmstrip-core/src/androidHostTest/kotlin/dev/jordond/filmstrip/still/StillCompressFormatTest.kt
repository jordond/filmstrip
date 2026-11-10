package dev.jordond.filmstrip.still

import android.graphics.Bitmap
import android.os.Build
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * Which compress format each still format asks for, and where WebP moves with the OS version.
 *
 * `WEBP_LOSSY` only exists from API 30 and the module's floor is API 26, so the version the
 * encoder is running on is a parameter rather than something read inside the mapping.
 */
class StillCompressFormatTest {
  @Test
  fun `png and jpeg do not move with the os version`() {
    for (sdkInt in listOf(MIN_SDK, Build.VERSION_CODES.Q, Build.VERSION_CODES.R, LATEST_SDK)) {
      compressFormatOf(StillFormat.Png, sdkInt) shouldBe Bitmap.CompressFormat.PNG
      compressFormatOf(StillFormat.Jpeg, sdkInt) shouldBe Bitmap.CompressFormat.JPEG
    }
  }

  @Test
  fun `webp takes the lossy format from the version that has one`() {
    compressFormatOf(StillFormat.Webp, Build.VERSION_CODES.R) shouldBe Bitmap.CompressFormat.WEBP_LOSSY
    compressFormatOf(StillFormat.Webp, LATEST_SDK) shouldBe Bitmap.CompressFormat.WEBP_LOSSY
  }

  @Test
  fun `webp falls back below the version that split it`() {
    @Suppress("DEPRECATION")
    val fallback = Bitmap.CompressFormat.WEBP

    // The version below R is the one that matters. Testing only the floor would pass under a
    // mapping that switched at the wrong release.
    compressFormatOf(StillFormat.Webp, MIN_SDK) shouldBe fallback
    compressFormatOf(StillFormat.Webp, Build.VERSION_CODES.Q) shouldBe fallback
    compressFormatOf(StillFormat.Webp, Build.VERSION_CODES.R) shouldNotBe fallback
  }

  private companion object {
    const val MIN_SDK = 24
    const val LATEST_SDK = 36
  }
}
