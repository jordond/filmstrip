package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effects.LINEAR_COLOR
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.croppedSamples
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.picture.pictureCropOf
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * How a decoded overlay picture is cut to the `clap` core reads and laid out by the orientation core reads, on the
 * ffmpeg backend.
 */
class StoredOrientationTest {
  @Test
  fun `a picture is laid out by the orientation its bytes are tagged with`() {
    for (orientation in PictureOrientation.entries) {
      val png = OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif)

      orientationLeftOf(png) shouldBe orientation
    }
  }

  // libjxl turns a JPEG XL by its codestream's orientation whatever ffmpeg asks, so nothing is left to turn.
  @Test
  fun `a JPEG XL and bytes this process cannot read are left unturned`() {
    orientationLeftOf(null) shouldBe PictureOrientation.Normal
    orientationLeftOf(byteArrayOf(0xFF.toByte(), 0x0A) + ByteArray(16)) shouldBe PictureOrientation.Normal
  }

  @Test
  fun `only an AVIF or HEIC with a clap is cut`() {
    val clapped = heifWith(OrientedPictures.cleanAperture(CROP.width, CROP.height))

    cropLeftOf(clapped) shouldBe pictureCropOf(clapped)
    cropLeftOf(clapped).shouldNotBeNull()
    cropLeftOf(heifWith()).shouldBeNull()
    cropLeftOf(OrientedPictures.png()).shouldBeNull()
    cropLeftOf(null).shouldBeNull()
  }

  @Test
  fun `a crop keeps the texels core cuts and the picture's color and limits`() {
    val crop = PictureCrop(1, 1, CROP)
    val packed = picture().cropped(crop)
    val wide = wide().cropped(crop)

    packed.size shouldBe CROP
    packed.samples.shouldBeInstanceOf<StoredSamples.Argb>().argb shouldBe
      croppedSamples(crop, OrientedPictures.samples(), STORED)
    packed.color shouldBeSameInstanceAs LINEAR_COLOR
    packed.limits shouldBe LIMITS
    wide.size shouldBe CROP
    wide.samples.shouldBeInstanceOf<StoredSamples.Rgba16>().rgba shouldBe croppedSamples(crop, wideSamples(), STORED)
  }

  @Test
  fun `no crop or one that keeps the whole picture leaves it as it is`() {
    val picture = picture()

    picture.cropped(null) shouldBeSameInstanceAs picture
    picture.cropped(PictureCrop(0, 0, STORED)) shouldBeSameInstanceAs picture
    picture.oriented(PictureOrientation.Normal) shouldBeSameInstanceAs picture
  }

  @Test
  fun `a turn lays the samples out the way core orients them at the size it shows them`() {
    for (orientation in PictureOrientation.entries.filter { it != PictureOrientation.Normal }) {
      val packed = picture().oriented(orientation)
      val wide = wide().oriented(orientation)

      withClue("$orientation") { packed.size shouldBe orientation.orientedSize(STORED) }
      withClue("$orientation") {
        packed.samples.shouldBeInstanceOf<StoredSamples.Argb>().argb shouldBe
          orientedSamples(orientation, OrientedPictures.samples(), STORED)
      }
      withClue("$orientation") {
        wide.samples.shouldBeInstanceOf<StoredSamples.Rgba16>().rgba shouldBe
          orientedSamples(orientation, wideSamples(), STORED)
      }
      withClue("$orientation") { packed.color shouldBeSameInstanceAs LINEAR_COLOR }
    }
  }

  // The clap after the turn names a rectangle of the turned picture, which core reads back onto the stored one, so the
  // decode is cut first and turned after.
  @Test
  fun `a turned and clapped picture is cut on the stored picture before it is turned`() {
    val bytes = heifWith(OrientedPictures.quarterTurn(1), OrientedPictures.cleanAperture(CROP.height, CROP.width))
    val crop = pictureCropOf(bytes).shouldNotBeNull()
    val orientation = orientationLeftOf(bytes)
    orientation shouldNotBe PictureOrientation.Normal

    val laid = picture().laidOut(bytes).shouldBeInstanceOf<StoredRead.Read>().picture
    val cut = croppedSamples(crop, OrientedPictures.samples(), STORED)
    laid.size shouldBe orientation.orientedSize(crop.size)
    laid.samples.shouldBeInstanceOf<StoredSamples.Argb>().argb shouldBe orientedSamples(orientation, cut, crop.size)
  }

  @Test
  fun `a decode that holds no rectangle the clap keeps is undrawn with core's sentence`() {
    val bytes = heifWith(OrientedPictures.cleanAperture(CROP.width, CROP.height))
    val crop = pictureCropOf(bytes).shouldNotBeNull()
    val small = Size(2, 2)
    val decoded = StoredPicture(StoredSamples.Argb(IntArray(small.width * small.height)), small, null)

    decoded.laidOut(bytes).shouldBeInstanceOf<StoredRead.Undrawn>().reason shouldBe crop.refusalFor(small)
  }

  private fun picture(): StoredPicture =
    StoredPicture(StoredSamples.Argb(OrientedPictures.samples()), STORED, LINEAR_COLOR, LIMITS)

  private fun wide(): StoredPicture = StoredPicture(StoredSamples.Rgba16(wideSamples()), STORED, null)

  // Four distinct sixteen-bit samples a texel, so a texel moved apart from its own samples shows.
  private fun wideSamples(): ShortArray = ShortArray(STORED.width * STORED.height * 4) { (it * 257 + 3).toShort() }

  // An AVIF of the oriented picture's stored size, with properties on its primary item, and no codestream a decoder
  // reads, since only its boxes are read here.
  private fun heifWith(vararg properties: ByteArray): ByteArray {
    val still = HeifStills.still("avif", "av01", AV1C, ByteArray(CODESTREAM_BYTES), STORED)
    return if (properties.isEmpty()) still else OrientedPictures.heifWithProperties(still, properties.toList())
  }

  private companion object {
    val STORED = OrientedPictures.SIZE
    val CROP = Size(4, 2)
    val LIMITS = listOf(Degradation(DegradationReason.PrecisionReduced, "The decoder read the picture at eight bits."))
    const val CODESTREAM_BYTES = 8
    val AV1C = byteArrayOf(0, 0, 0, 12) + "av1C".encodeToByteArray() + byteArrayOf(0x81.toByte(), 0, 0, 0)
  }
}
