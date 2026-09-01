package dev.jordond.filmstrip.media

import android.graphics.Bitmap
import android.os.Build
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Which compress format each still format asks for, and where WebP moves with the OS version.
 *
 * `WEBP_LOSSY` only exists from API 30 and the module's floor is API 24, so the version the
 * encoder is running on is a parameter rather than something read inside the mapping.
 */
class StillCompressFormatTest {
  @Test
  fun `png and jpeg do not move with the os version`() {
    for (sdkInt in listOf(MIN_SDK, Build.VERSION_CODES.Q, Build.VERSION_CODES.R, LATEST_SDK)) {
      assertEquals(Bitmap.CompressFormat.PNG, compressFormatOf(StillFormat.Png, sdkInt))
      assertEquals(Bitmap.CompressFormat.JPEG, compressFormatOf(StillFormat.Jpeg, sdkInt))
    }
  }

  @Test
  fun `webp takes the lossy format from the version that has one`() {
    assertEquals(Bitmap.CompressFormat.WEBP_LOSSY, compressFormatOf(StillFormat.Webp, Build.VERSION_CODES.R))
    assertEquals(Bitmap.CompressFormat.WEBP_LOSSY, compressFormatOf(StillFormat.Webp, LATEST_SDK))
  }

  @Test
  fun `webp falls back below the version that split it`() {
    @Suppress("DEPRECATION")
    val fallback = Bitmap.CompressFormat.WEBP

    // The version below R is the one that matters. Testing only the floor would pass under a
    // mapping that switched at the wrong release.
    assertEquals(fallback, compressFormatOf(StillFormat.Webp, MIN_SDK))
    assertEquals(fallback, compressFormatOf(StillFormat.Webp, Build.VERSION_CODES.Q))
    assertNotEquals(fallback, compressFormatOf(StillFormat.Webp, Build.VERSION_CODES.R))
  }

  private companion object {
    const val MIN_SDK = 24
    const val LATEST_SDK = 36
  }
}
