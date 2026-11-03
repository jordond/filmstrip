package dev.jordond.filmstrip.media

import android.media.ExifInterface
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The orientation tag a phone camera writes, read against Android's own names for it.
 *
 * A photo taken in portrait is almost always stored landscape with a tag saying which way up it
 * goes, so a mapping that got this wrong would ship sideways photos with nothing to say so. Pinning
 * it against [ExifInterface]'s constants is what keeps the shared mapping and the values Android
 * reads from a file describing the same eight orientations.
 */
class ImageRotationAndroidTest {
  @Test
  fun `the four turning orientations map to the turns android names them after`() {
    imageRotationOf(ExifInterface.ORIENTATION_NORMAL) shouldBe 0
    imageRotationOf(ExifInterface.ORIENTATION_ROTATE_90) shouldBe 90
    imageRotationOf(ExifInterface.ORIENTATION_ROTATE_180) shouldBe 180
    imageRotationOf(ExifInterface.ORIENTATION_ROTATE_270) shouldBe 270
  }

  // The mirrored four are the middle of the range, and they are the ones a mapping that multiplied
  // the tag out would get wrong. Each carries the turn of the orientation it mirrors.
  @Test
  fun `the four mirrored orientations map to the turn they mirror`() {
    imageRotationOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL) shouldBe 0
    imageRotationOf(ExifInterface.ORIENTATION_FLIP_VERTICAL) shouldBe 180
    imageRotationOf(ExifInterface.ORIENTATION_TRANSPOSE) shouldBe 90
    imageRotationOf(ExifInterface.ORIENTATION_TRANSVERSE) shouldBe 270
  }

  @Test
  fun `an undefined tag reads as no rotation`() {
    imageRotationOf(ExifInterface.ORIENTATION_UNDEFINED) shouldBe 0
  }

  // The default the measurement falls back to when a still carries no EXIF block at all, which a
  // PNG and a WebP never do.
  @Test
  fun `the fallback the measurement uses is the one android calls normal`() {
    EXIF_ORIENTATION_NORMAL shouldBe ExifInterface.ORIENTATION_NORMAL
  }
}
