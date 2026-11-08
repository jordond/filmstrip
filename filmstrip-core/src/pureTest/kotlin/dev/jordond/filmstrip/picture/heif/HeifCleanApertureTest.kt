package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.fixtures.heifItems
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.picture.pictureCropOf
import dev.jordond.filmstrip.picture.pictureExtentOf
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class HeifCleanApertureTest {
  // Each clap on the 40 by 30 frame, with the texels libheif keeps: its left edge rounded towards zero, its top edge
  // to the nearest with a half up, and an edge past the frame pulled back onto it.
  @Test
  fun eachClapKeepsTheTexelsLibheifCropsTo() {
    for ((index, case) in CASES.withIndex()) {
      val transforms = transformsOf(listOf(case.clap))

      withClue("case $index crop") { transforms.crop shouldBe case.crop }
      withClue("case $index size") { transforms.size shouldBe case.crop?.size?.takeUnless { case.clamped } }
    }
  }

  // A clap after a turn or mirror crops the picture as shown. Reading the shown crop back to the stored picture has to
  // give the same texels as turning the stored crop, for every one of the eight orientations.
  @Test
  fun aClapAfterATurnKeepsTheStoredTexelsTheShownCropShows() {
    val stored = IntArray(FRAME.width * FRAME.height) { it }
    val offset = clap(19, 1, 9, 1, horizontalOffset = 5, verticalOffset = -7, verticalDenominator = 2)
    for (turns in 0..3) {
      for (axis in listOf(null, 0, 1)) {
        val properties = listOfNotNull(irot(turns), axis?.let(::imir), offset)
        val transforms = transformsOf(properties)
        val orientation = heifOrientationOf(turns, axis)
        val shownFrame = orientation.orientedSize(FRAME)
        val shown = orientedSamples(orientation, stored, FRAME)
        val view = byteViewOf(offset)
        val kept = view.clapCrop(view.boxes(0, offset.size).single(), shownFrame).shouldNotBeNull()
        val shownSize = Size(kept.right - kept.left + 1, kept.bottom - kept.top + 1)
        val shownCrop = shown.cropped(shownFrame, kept.left, kept.top, shownSize.width, shownSize.height)
        val crop = withClue("$turns $axis") { transforms.crop.shouldNotBeNull() }
        val storedCrop = stored.cropped(FRAME, crop.left, crop.top, crop.size.width, crop.size.height)

        withClue("$turns $axis") { orientedSamples(orientation, storedCrop, crop.size) shouldBe shownCrop }
      }
    }
  }

  // Two claps each crop what the one ahead of them left, so the second is centred on the first's crop.
  @Test
  fun aSecondClapCropsWhatTheFirstLeft() {
    val transforms = transformsOf(listOf(clap(Size(20, 10)), clap(Size(10, 4))))

    transforms.crop shouldBe PictureCrop(15, 13, Size(10, 4))
  }

  // The still measure and the crop a backend cuts come from the same read, so they agree on a centred and an offset
  // clap, and only the crop is read for one reaching past the frame.
  @Test
  fun aFileMeasuresAtTheSizeOfTheCropABackendCuts() {
    for ((index, case) in CASES.withIndex()) {
      val file = heifItems(listOf(ispe(FRAME), case.clap))
      val crop = pictureCropOf(file)

      withClue("case $index crop") { crop shouldBe case.crop }
      withClue("case $index measure") { pictureExtentOf(file) shouldBe crop?.size?.takeUnless { case.clamped } }
    }
  }

  @Test
  fun aClapLibheifRefusesKeepsNothing() {
    for (clap in listOf(clap(Size(0, 10)), clap(20, 0, 10, 1), clap(20, 1, 10, 1, horizontalDenominator = 0))) {
      transformsOf(listOf(clap)).crop.shouldBeNull()
    }
  }

  private fun transformsOf(properties: List<ByteArray>): HeifTransforms {
    val file = properties.fold(ByteArray(0)) { bytes, property -> bytes + property }
    val view = byteViewOf(file)
    return view.heifTransformsOf(view.boxes(0, file.size), FRAME)
  }

  private fun byteViewOf(bytes: ByteArray): ByteView = ByteView(bytes, bytes.size)

  // The texels of a picture of frame's size from left and top, width by height.
  private fun IntArray.cropped(
    frame: Size,
    left: Int,
    top: Int,
    width: Int,
    height: Int,
  ): IntArray = IntArray(width * height) { this[(top + it / width) * frame.width + left + it % width] }

  private class Case(
    val clap: ByteArray,
    val crop: PictureCrop?,
    val clamped: Boolean = false,
  )

  private companion object {
    val FRAME = Size(40, 30)

    val CASES =
      listOf(
        Case(clap(Size(20, 10)), PictureCrop(10, 10, Size(20, 10))),
        Case(clap(Size(19, 9)), PictureCrop(10, 11, Size(19, 9))),
        Case(
          clap(20, 1, 10, 1, horizontalOffset = 5, verticalOffset = -7, verticalDenominator = 2),
          PictureCrop(15, 7, Size(20, 10)),
        ),
        Case(clap(20, 1, 10, 1, horizontalOffset = -21, horizontalDenominator = 2), PictureCrop(0, 10, Size(20, 10))),
        Case(
          clap(20, 1, 10, 1, verticalOffset = -25, verticalDenominator = 2),
          PictureCrop(10, 0, Size(20, 8)),
          clamped = true,
        ),
        Case(clap(Size(50, 10)), PictureCrop(0, 10, Size(40, 10)), clamped = true),
        Case(clap(2001, 100, 10, 1), PictureCrop(9, 10, Size(20, 10))),
        Case(clap(Size(40, 30)), PictureCrop(0, 0, FRAME)),
      )
  }
}
