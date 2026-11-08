package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.createBitmap
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.croppedSamples
import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifWithoutOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.picture.pictureCropOf
import dev.jordond.filmstrip.picture.pictureOrientationOf
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicSubsampledPictures
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import kotlin.test.Test

/**
 * Overlay pictures tagged with an orientation, decoded by Android and prepared for a frame the way core reads the tag.
 *
 * Android's decoder turns nothing, a HEIC included once its `irot` and `imir` are dropped, so [rasterFor] lays the
 * stored samples out through [orientedSamples]. Every expected texel comes from [orientedSamples] over the same picture
 * decoded untagged, compared whole, so the middle of every quadrant is read and all eight orientations read
 * differently.
 */
class OrientedPictureRasterMediaTest {
  @Test
  fun aHeicTaggedSixDecodesAsStoredOnceItsTurnIsDropped() {
    assumeTrue("Android decodes HEIC from API 28", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    val tagged = heifTagged(HEIC, PictureOrientation.Rotate90)
    val asStored = decoded("the HEIC", HEIC)
    val read = decoded("the HEIC tagged 6", tagged)

    read.orientation shouldBe PictureOrientation.Rotate90
    withClue("the HEIC tagged 6 decoded turned") { read.bitmap.size() shouldBe asStored.bitmap.size() }
    withClue("the HEIC tagged 6 decoded turned") { read.bitmap.argb() shouldBe asStored.bitmap.argb() }
  }

  @Test
  fun aJpegAndAPngTaggedSixDecodeAsStored() {
    for ((name, untagged, tagging) in formats()) {
      val asStored = decoded(name, untagged)
      val read = decoded("$name tagged 6", tagging(PictureOrientation.Rotate90))

      withClue("$name tagged 6 decoded turned") { read.bitmap.size() shouldBe asStored.bitmap.size() }
      withClue("$name tagged 6 decoded turned") { read.bitmap.argb() shouldBe asStored.bitmap.argb() }
    }
  }

  @Test
  fun everyOrientationRastersTheWayCoreLaysThePictureOut() {
    val heic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    val misses =
      formats(heic).flatMap { (name, untagged, tagging) ->
        val untaggedRead = decoded(name, untagged)
        val stored = untaggedRead.bitmap
        val crop = untaggedRead.crop ?: PictureCrop(0, 0, stored.size())
        val samples = croppedSamples(crop, stored.argb(), stored.size())
        PictureOrientation.entries.flatMap { orientation ->
          val read = decoded("$name $orientation", tagging(orientation))
          val shown = orientation.orientedSize(crop.size)
          val oriented = orientedSamples(orientation, samples, crop.size)
          listOf(null, HdrTransfer.Pq).mapNotNull { transfer ->
            val raster = read.bitmap.rasterFor(transfer, read.color, read.orientation, read.crop)
            val color = read.color
            val expected =
              transfer?.let { media3HdrTexels(oriented, it, color).argb } ?: pictureSrgbCodes(color, oriented)
            when {
              read.orientation != orientation -> "$name $orientation read as ${read.orientation}"
              raster.bitmap.size() != shown -> "$name $orientation rastered at ${raster.bitmap.size()}, not $shown"
              !raster.bitmap.argb().contentEquals(expected) -> "$name $orientation on ${transfer ?: "SDR"} misses"
              else -> null
            }
          }
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  // A centred and an offset clap, and one listed after a quarter turn. Android's decoder is handed each with its clap
  // dropped and decodes the whole frame, and the raster is that frame cut at the crop core reads and then turned,
  // texel by texel against the same HEIC without a clap.
  @Test
  fun aClappedHeicRastersTheWholeFrameCutAtCoresCropAndThenTurned() {
    assumeTrue("Android decodes HEIC from API 28", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    val still = HeicSubsampledPictures.EIGHT_BIT_420.bytes(CicpTag(1, 13, 1, true))
    val frame = decoded("the whole frame", still)
    val size = frame.bitmap.size()
    val samples = frame.bitmap.argb()
    for ((name, properties) in CLAPPED) {
      val clapped = OrientedPictures.heifWithProperties(still, properties)
      val crop = withClue(name) { pictureCropOf(clapped).shouldNotBeNull() }
      val orientation = pictureOrientationOf(clapped)
      val handed = withClue(name) { heifWithoutOrientation(clapped).shouldBeInstanceOf<UnturnedHeif.Dropped>() }.bytes
      val read = decoded(name, clapped)

      val raster = read.bitmap.rasterFor(null, read.color, read.orientation, read.crop)

      val bare = withClue(name) { BitmapFactory.decodeByteArray(handed, 0, handed.size).shouldNotBeNull() }
      withClue("$name dropped bytes decoded cropped") { bare.size() shouldBe size }
      withClue("$name decoded cropped") { read.bitmap.size() shouldBe size }
      withClue(name) { read.crop shouldBe crop }
      withClue(name) { raster.bitmap.size() shouldBe orientation.orientedSize(crop.size) }
      val shown = orientedSamples(orientation, croppedSamples(crop, samples, size), crop.size)
      val expected = pictureSrgbCodes(read.color, shown)
      withClue(name) { raster.bitmap.argb() shouldBe expected }
    }
  }

  private data class Format(
    val name: String,
    val untagged: ByteArray,
    val tagging: (PictureOrientation) -> ByteArray,
  )

  private fun formats(heic: Boolean = false): List<Format> =
    listOf(
      Format("PNG", PNG) { OrientedPictures.taggedPng(PNG, it.exif) },
      Format("JPEG", JPEG) { OrientedPictures.taggedJpeg(JPEG, it.exif) },
    ) + listOfNotNull(Format("HEIC", HEIC) { heifTagged(HEIC, it) }.takeIf { heic })

  private fun decoded(
    name: String,
    picture: ByteArray,
  ): DecodedPicture =
    withClue("$name did not decode") {
      ImageSource.Bytes(picture).decode(context = null).shouldNotBeNull()
    }

  private fun Bitmap.argb(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

  private companion object {
    const val BLOCK = 8

    // A centred and an offset clean aperture, and an offset one listed after a quarter turn, which crops the turned
    // frame and which core carries back to the stored picture.
    val CLAPPED =
      mapOf(
        "centred" to listOf(OrientedPictures.cleanAperture(40, 24)),
        "offset" to listOf(OrientedPictures.cleanAperture(40, 24, -9, 7, offsetDenominator = 2)),
        "after a turn" to listOf(OrientedPictures.quarterTurn(1), OrientedPictures.cleanAperture(40, 24, 5, -3)),
      )
    const val FULL_QUALITY = 100

    val PNG = OrientedPictures.png()

    // The stored picture at BLOCK texels a texel, compressed by Android at full quality.
    val JPEG: ByteArray by lazy {
      val big = Size(OrientedPictures.SIZE.width * BLOCK, OrientedPictures.SIZE.height * BLOCK)
      val texels = OrientedPictures.samples()
      val picture =
        IntArray(big.width * big.height) {
          texels[(it / big.width / BLOCK) * OrientedPictures.SIZE.width + (it % big.width) / BLOCK]
        }
      val bitmap = createBitmap(big.width, big.height)
      bitmap.setPixels(picture, 0, big.width, 0, 0, big.width, big.height)
      val out = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.JPEG, FULL_QUALITY, out)
      bitmap.recycle()
      out.toByteArray()
    }

    // A PNG 48 by 32 painted in four quadrants with a marker texel top-left, written by heif-enc 1.23.6 at quality 90.
    val HEIC =
      (
        "0000001c6674797068656963000000006d696631686569636d6961660000017d6d657461000000000000002168646c720000" +
          "000000000000706963740000000000000000000000000000000022696c6f63000000004440000100010000000001a1000100" +
          "000000000001130000002369696e6600000000000100000015696e6665020000000001000068766331000000000e7069746d" +
          "000000000001000000fd69707270000000dd6970636f00000076687663430103700000009000000000001ef000fcfdf8f800" +
          "000f03600001001840010c01ffff03700000030090000003000003001eba0240610001002a42010103700000030090000003" +
          "000003001ea020810596eae4a6b9b8086830200000030320000003002162000100064401c173c08900000013636f6c726e63" +
          "6c780001000d000680000000146973706500000000000000400000004000000028636c617000000030000000010000002000" +
          "000001fffffff000000002ffffffe000000002000000107069786900000000030808080000001869706d6100000000000000" +
          "0100010581020305840000011b6d6461740000010f2801af0638e3e050f8b017ff27fbffb7f23e1ba0be9fa860758376efd6" +
          "bfed8bfffe53c000d1b6aea440a26c59ffe13feffc967b364e0ac511967bf5528447cc2b1a49691ba2afb3f01db7eb935fff" +
          "e4631f4f5d71e0025d080f9db3de7f2bd8e926415dc3f241f9c7d5b07fffa659ffff30557138fffeb90bffdbfc06dfe2feff" +
          "ff341f3ae3559dd4f3e9f79fb60e61302a79f332524a7bd44a2ae240f530e3b6b1c6e343c384d61706fc82654efb3f843569" +
          "6cdd860b63ec644417467412092beadfc3dfbfef7599d5cfafc28c983575fb6e3262c48809bd3f4274393a1a750aa7a9eec2" +
          "b95f9b7141ef0a9d7af25ae7e9f5f1cb2d3fda7394dc3fb59f96a4ee28932d367f1d4559ca47e7af87d3"
      ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // The irot and imir core folds into orientation, found by asking core's reader, since the fold is core's.
    fun heifTagged(
      heif: ByteArray,
      orientation: PictureOrientation,
    ): ByteArray {
      if (orientation == PictureOrientation.Normal) return OrientedPictures.taggedHeif(heif, 0, null)
      for (turns in listOf(null, 0, 1, 2, 3)) {
        for (axis in listOf(null, 0, 1)) {
          if (turns == null && axis == null) continue
          val tagged = OrientedPictures.taggedHeif(heif, turns, axis)
          if (pictureOrientationOf(tagged) == orientation) return tagged
        }
      }
      error("no irot and imir fold into $orientation")
    }
  }
}
