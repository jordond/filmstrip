package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.UnturnedHeif
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HeifTransformsTest {
  // Each listing gives whether a scale follows a turn or mirror, and the stored size a 40 by 30 frame comes to once
  // each clap crops the frame the turns ahead of it leave.
  @Test
  fun eachListingCropsTheFrameTheTransformsAheadOfItLeave() {
    for ((index, listing) in LISTINGS.withIndex()) {
      val transforms = transformsOf(listing.properties, FRAME)
      val name = "listing $index"

      assertEquals(listing.scalesTurned, transforms.scalesTurned, "$name scales turned")
      assertEquals(listing.size, transforms.size, "$name size")
    }
  }

  // The drop reads the same listing, so it refuses a scale after a turn on exactly the listings that set
  // scalesTurned, and a clap that reads no crop. Every other listing is handed over with no clap, turn or mirror left,
  // so the decoder lands the whole frame and the crop still reads from the input, a clap after a turn included.
  @Test
  fun theDropRefusesAScaleAfterATurnExactlyWhenScalesTurnedIsSetAndTakesEveryClap() {
    for ((index, listing) in LISTINGS.withIndex()) {
      val properties = listOf(ispe(FRAME)) + listing.properties
      val file = heif(properties, ipma(listOf(1 to properties.indices.map { it + 1 })))
      val transforms = transformsOf(listing.properties, FRAME)
      val result = heifUnturned(file)
      val reason = (result as? UnturnedHeif.Refused)?.reason
      val name = "listing $index"

      assertEquals(transforms.scalesTurned, reason == SCALED_AFTER, name)
      if (transforms.scalesTurned) continue
      if (transforms.crop == null) {
        assertEquals(CROP_UNREAD, reason, name)
        continue
      }
      val handed = assertIs<UnturnedHeif.Handed>(result, name).bytes
      assertNull(pictureCropOf(handed), "$name handed")
      assertEquals(PictureOrientation.Normal, pictureOrientationOf(handed), "$name handed")
      val clapped = listing.properties.any { it.copyOfRange(4, 8).decodeToString() == "clap" }
      assertEquals(transforms.crop.takeIf { clapped }, pictureCropOf(file), "$name crop")
    }
  }

  // A clap behind an irot of 0 crops the stored frame, and the drop takes every irot and the clap off, leaving the
  // ispe.
  @Test
  fun aClapBehindAnIrotOf0IsDroppedWithEveryTurn() {
    val properties = listOf(ispe(FRAME), IROT_0, CLAP, IROT_1)
    val file = heif(properties, ipma(listOf(1 to properties.indices.map { it + 1 })))
    val expected = heif(properties, ipma(listOf(1 to listOf(1))))

    assertEquals(expected.toList(), (heifUnturned(file) as? UnturnedHeif.Dropped)?.bytes?.toList())
  }

  @Test
  fun withoutAFrameNoSizeIsReadAndNoClapIsRead() {
    val cutShort = box("clap", ByteArray(4))

    assertNull(transformsOf(listOf(IROT_1, CLAP), frame = null).size)
    assertEquals(heifOrientationOf(1, null), transformsOf(listOf(cutShort, IROT_1), frame = null).orientation)
  }

  private fun transformsOf(
    properties: List<ByteArray>,
    frame: Size?,
  ): HeifTransforms {
    val file = properties.fold(ByteArray(0)) { bytes, property -> bytes + property }
    val view = ByteView(file, file.size)
    return view.heifTransformsOf(view.boxes(0, file.size), frame)
  }

  private class Listing(
    val properties: List<ByteArray>,
    val scalesTurned: Boolean,
    val size: Size?,
  )

  private companion object {
    val FRAME = Size(40, 30)
    val IROT_0 = irot(0)
    val IROT_1 = irot(1)
    val IROT_2 = irot(2)
    val IROT_3 = irot(3)
    val IMIR_0 = imir(0)
    val CLAP = clap(Size(20, 30))
    val ISCL = box("iscl", ByteArray(8))

    val LISTINGS =
      listOf(
        Listing(emptyList(), scalesTurned = false, size = FRAME),
        Listing(listOf(IROT_1, CLAP), scalesTurned = false, size = Size(30, 20)),
        Listing(listOf(CLAP, IROT_1), scalesTurned = false, size = Size(20, 30)),
        Listing(listOf(IMIR_0, CLAP, IROT_1), scalesTurned = false, size = Size(20, 30)),
        Listing(listOf(CLAP, IROT_1, clap(Size(10, 20))), scalesTurned = false, size = Size(20, 10)),
        Listing(listOf(IMIR_0, ISCL), scalesTurned = true, size = FRAME),
        Listing(listOf(IROT_2, CLAP), scalesTurned = false, size = Size(20, 30)),
        Listing(listOf(IROT_1, IROT_1, CLAP), scalesTurned = false, size = Size(20, 30)),
        Listing(listOf(IROT_1, CLAP, IROT_1), scalesTurned = false, size = Size(30, 20)),
        Listing(listOf(clap(Size(10, 10)), clap(Size(10, 10))), scalesTurned = false, size = Size(10, 10)),
        Listing(listOf(clap(Size(10, 10)), clap(Size(20, 20))), scalesTurned = false, size = null),
        Listing(listOf(IROT_1, clap(Size(35, 10))), scalesTurned = false, size = null),
        Listing(listOf(clap(Size(35, 10)), IROT_1), scalesTurned = false, size = Size(35, 10)),
        Listing(listOf(clap(Size(0, 10)), clap(Size(10, 10))), scalesTurned = false, size = null),
        Listing(listOf(IROT_0, CLAP), scalesTurned = false, size = Size(20, 30)),
        Listing(listOf(IROT_0, CLAP, IROT_1), scalesTurned = false, size = Size(20, 30)),
        Listing(listOf(IROT_1, IROT_3, CLAP), scalesTurned = false, size = Size(20, 30)),
        Listing(listOf(ISCL, IROT_1), scalesTurned = false, size = FRAME),
        Listing(listOf(IROT_1, CLAP, ISCL), scalesTurned = true, size = Size(30, 20)),
        Listing(listOf(IROT_1, clap(20, 1, 10, 1, horizontalOffset = 3)), scalesTurned = false, size = Size(10, 20)),
      )
  }
}
