package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.assertRasterReads
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pictureCropOf
import dev.jordond.filmstrip.internal.pictureOrientationOf
import dev.jordond.filmstrip.internal.pictureWithoutOrientationTag
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.croppedSamples
import dev.jordond.filmstrip.media.heifWithoutOrientation
import dev.jordond.filmstrip.media.orientedSamples
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicSubsampledPictures
import dev.jordond.filmstrip.test.IccPictures
import dev.jordond.filmstrip.test.OrientedPictures
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Overlay pictures tagged with an orientation, drawn on Apple the way core reads the tag.
 *
 * Neither of Apple's decode routes turns a picture by its tag, so [OverlayPixels] lays the stored codes out through
 * the orientation core reads. Every expected texel comes from [orientedSamples] over the same picture read untagged,
 * compared whole, so the middle of every quadrant is read and all eight orientations read differently.
 */
@OptIn(InternalFilmstripApi::class)
class OverlayOrientationTest {
  @Test
  fun `apple decodes a picture tagged 6 as stored on both routes`() {
    assertRasterReads()
    val turn = PictureOrientation.Rotate90
    val cases =
      listOf(
        "PNG" to (OrientedPictures.png() to OrientedPictures.taggedPng(OrientedPictures.png(), turn.exif)),
        "JPEG" to (jpeg to OrientedPictures.taggedJpeg(jpeg, turn.exif)),
        "JPEG XL" to (JXL_UNTAGGED to JXL_TAGGED_6),
        "HEIC" to (heic to heifTagged(heic, turn)),
      )
    for ((name, pair) in cases) {
      val (untagged, tagged) = pair
      assertEquals(turn, pictureOrientationOf(tagged), "core reads $name's tag")
      // The HEIC lists a clap, which the drop takes off the untagged one too, so both decode the whole frame.
      val handed = if (name == "HEIC") assertIs<UnturnedHeif.Handed>(heifWithoutOrientation(tagged)).bytes else tagged
      val bare = if (name == "HEIC") assertIs<UnturnedHeif.Handed>(heifWithoutOrientation(untagged)).bytes else untagged
      val asStored = writtenPixels(bare).orFail(name)
      val decoded = writtenPixels(handed).orFail("$name tagged 6")
      assertEquals(asStored.size, decoded.size, "$name tagged 6 decoded turned")
      assertContentEquals(asStored.stored, decoded.stored, "$name tagged 6 decoded turned")
    }
  }

  @Test
  fun `every orientation draws the way core lays the picture out`() {
    assertRasterReads()
    for ((name, untagged, tagging) in formats()) {
      val stored = ImageSource.ofBytes(untagged).read().orFail(name)
      val samples = assertNotNull(stored.stored, "$name read no samples")
      for (orientation in PictureOrientation.entries) {
        val tagged = tagging(orientation)
        assertEquals(orientation, pictureOrientationOf(tagged), "core reads $name $orientation")
        val shown = ImageSource.ofBytes(tagged).read().orFail("$name $orientation")
        assertEquals(orientation.orientedSize(stored.size), shown.size, "$name $orientation")
        assertContentEquals(orientedSamples(orientation, samples, stored.size), shown.stored, "$name $orientation")
      }
    }
  }

  @Test
  fun `ImageIO is handed every tagged picture with its tag written as 1`() {
    for ((name, _, tagging) in formats().filter { it.name != "HEIC" }) {
      for (orientation in PictureOrientation.entries) {
        val tagged = tagging(orientation)

        val handed = assertIs<ImageIoLanding.Decoded>(explicitLanding(tagged)).bytes

        assertContentEquals(pictureWithoutOrientationTag(tagged), handed, "$name $orientation")
        assertEquals(PictureOrientation.Normal, pictureOrientationOf(handed), "$name $orientation")
      }
    }
  }

  // A centred and an offset clap, and one listed after a quarter turn, each drawn as the same HEIC without a clap cut
  // at the crop core reads and then turned. ImageIO is handed the file with its clap dropped, so it decodes the whole
  // frame and lands an offset clap where libheif does, not a column off.
  @Test
  fun `a clapped HEIC draws the whole frame cut at core's crop and then turned`() {
    assertRasterReads()
    val still = HeicSubsampledPictures.EIGHT_BIT_420.bytes(CicpTag(1, 13, 1, true))
    val frame = ImageSource.ofBytes(still).read().orFail("the whole frame")
    val samples = assertNotNull(frame.stored)
    for ((name, properties) in CLAPPED) {
      val clapped = OrientedPictures.heifWithProperties(still, properties)
      val crop = assertNotNull(pictureCropOf(clapped), name)
      val orientation = pictureOrientationOf(clapped)
      val expected = orientedSamples(orientation, croppedSamples(crop, samples, frame.size), crop.size)

      val drawn = ImageSource.ofBytes(clapped).read().orFail(name)

      assertEquals(orientation.orientedSize(crop.size), drawn.size, "$name size")
      assertContentEquals(expected, drawn.stored, "$name texels")
    }
  }

  // ImageIO decodes the bytes the drop hands it at the whole stored frame, whatever clap the file lists.
  @Test
  fun `ImageIO decodes the dropped bytes of a clapped HEIC uncropped`() {
    assertRasterReads()
    val still = HeicSubsampledPictures.EIGHT_BIT_420.bytes(CicpTag(1, 13, 1, true))
    val frame = ImageSource.ofBytes(still).read().orFail("the whole frame")
    for ((name, properties) in CLAPPED) {
      val clapped = OrientedPictures.heifWithProperties(still, properties)
      val handed = assertIs<UnturnedHeif.Dropped>(heifWithoutOrientation(clapped), name).bytes

      val codes = handed.nsData().overlayCodes()

      val held = assertIs<PictureRead.Read<HeldCodes>>(codes, name).value
      assertEquals(frame.size, held.image.pixelSize(), name)
    }
  }

  @Test
  fun `a JPEG XL tagged in its codestream is turned by core`() {
    assertRasterReads()
    val stored = ImageSource.ofBytes(JXL_UNTAGGED).read().orFail("the JPEG XL")
    val shown = ImageSource.ofBytes(JXL_TAGGED_6).read().orFail("the tagged JPEG XL")
    val expected = orientedSamples(PictureOrientation.Rotate90, assertNotNull(stored.stored), stored.size)
    assertEquals(PictureOrientation.Rotate90.orientedSize(stored.size), shown.size)
    assertContentEquals(expected, shown.stored)
  }

  // A picture described as Display P3 draws its SDR codes and its HDR light from bands of the turned codes.
  @Test
  fun `SDR and HDR frames draw a described picture turned`() {
    assertRasterReads()
    val reader = assertNotNull(codeReader, "no context reads codes back")
    val held =
      ImageSource
        .ofBytes(jpeg)
        .read()
        .orFail("the JPEG")
        .held
    val colour = pictureColour(IccPictures.DISPLAY_P3.pngOf(Size(1, 1)))
    val asStored = OverlayPixels(held, readColour = { colour })
    for (orientation in PictureOrientation.entries) {
      val turned = OverlayPixels(held, readColour = { colour }, orientation = orientation)
      for (transfer in listOf(null, HdrTransfer.Pq)) {
        val space = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020
        val drawn = asStored.enteringFrame(transfer, space).storedSamples(asStored.size, reader)
        val expected = orientedSamples(orientation, drawn, asStored.size)
        val read = turned.enteringFrame(transfer, space).storedSamples(turned.size, reader)
        assertContentEquals(expected, read, "$orientation on ${transfer ?: "SDR"}")
      }
    }
  }

  private data class Format(
    val name: String,
    val untagged: ByteArray,
    val tagging: (PictureOrientation) -> ByteArray,
  )

  private fun formats(): List<Format> =
    listOf(
      Format("PNG", OrientedPictures.png()) { OrientedPictures.taggedPng(OrientedPictures.png(), it.exif) },
      Format("JPEG", jpeg) { OrientedPictures.taggedJpeg(jpeg, it.exif) },
      Format("HEIC", heic) { heifTagged(heic, it) },
    )

  private companion object {
    const val BLOCK = 8
    const val CODE_MASK = 0xFF
    const val FULL = 255.0
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8

    val BIG = Size(OrientedPictures.SIZE.width * BLOCK, OrientedPictures.SIZE.height * BLOCK)

    val jpeg: ByteArray by lazy { written("public.jpeg") }

    // A bare codestream 48 by 32 painted in four quadrants with a marker texel top-left, the same codestream
    // boxed with orientation 6 in its image header, written by cjxl 0.12.0 at distance 0 from a PNG and from
    // the PNG with an eXIf tagging 6, and the PNG written by heif-enc 1.23.6 at quality 90.
    val JXL_UNTAGGED =
      (
        "ff0a07412408000100cc004b386998ca83f72b2808801a3f3118bc40100092641d009144fefd8724fefdfe0caafdfd9344fe" +
          "80b9627bec43f79633495c02"
      ).hexBytes()
    val JXL_TAGGED_6 =
      (
        "0000000c4a584c200d0a870a00000014667479706a786c20000000006a786c20000000126a786c7000000000ff0a072d1013" +
          "0000003362726f62457869661b290000047463fb05316b69a67ed88003e738a00087e1066c18b4824af8ef602db5170d3e15" +
          "00000000456a786c708000000108000100cc004b386998ca83f72b2808801a3f3118bc40100092641d009144fefd8724fefd" +
          "fe0caafdfd9344fe80b9627bec43f79633495c02"
      ).hexBytes()
    val heic =
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
      ).hexBytes()

    fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // A centred and an offset clean aperture, and an offset one listed after a quarter turn, which crops the turned
    // frame and which core carries back to the stored picture.
    val CLAPPED =
      mapOf(
        "centred" to listOf(OrientedPictures.cleanAperture(40, 24)),
        "offset" to listOf(OrientedPictures.cleanAperture(40, 24, -9, 7, offsetDenominator = 2)),
        "after a turn" to listOf(OrientedPictures.quarterTurn(1), OrientedPictures.cleanAperture(40, 24, 5, -3)),
      )

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

    // The stored picture at BLOCK texels a texel, written by ImageIO as type at full quality.
    @OptIn(ExperimentalForeignApi::class)
    fun written(type: String): ByteArray {
      val space = CGColorSpaceCreateDeviceRGB()
      val info = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value
      val context =
        assertNotNull(CGBitmapContextCreate(null, BIG.width.toULong(), BIG.height.toULong(), 8u, 0u, space, info))
      CGColorSpaceRelease(space)
      val texels = OrientedPictures.samples()
      for (row in 0 until OrientedPictures.SIZE.height) {
        for (column in 0 until OrientedPictures.SIZE.width) {
          val texel = texels[row * OrientedPictures.SIZE.width + column]
          val red = (texel ushr RED_SHIFT and CODE_MASK) / FULL
          val green = (texel ushr GREEN_SHIFT and CODE_MASK) / FULL
          CGContextSetRGBFillColor(context, red, green, (texel and CODE_MASK) / FULL, 1.0)
          val bottom = (OrientedPictures.SIZE.height - 1 - row) * BLOCK
          CGContextFillRect(context, CGRectMake((column * BLOCK).toDouble(), bottom.toDouble(), 8.0, 8.0))
        }
      }
      val image = assertNotNull(CGBitmapContextCreateImage(context))
      CGContextRelease(context)
      val data = assertNotNull(CFDataCreateMutable(null, 0))
      val name = CFStringCreateWithCString(null, type, kCFStringEncodingUTF8)
      val destination = CGImageDestinationCreateWithData(data, name, 1u, null)
      name?.let(::CFRelease)
      val finished =
        destination?.let {
          CGImageDestinationAddImage(it, image, null)
          CGImageDestinationFinalize(it).also { _ -> CFRelease(it) }
        } ?: false
      CGImageRelease(image)
      val bytes = if (finished) CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).toInt()) else null
      CFRelease(data)
      return assertNotNull(bytes, "ImageIO wrote no $type")
    }
  }
}
