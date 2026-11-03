package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.fixtures.JxlBits
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JxlHeaderTest {
  @Test
  fun smallSidesAreCountedInEighths() {
    val header =
      read(
        JxlBits()
          .write(1, 1)
          .write(4, 5)
          .write(0, 3)
          .write(9, 5)
          .write(1, 1),
      )

    header.height shouldBe 40
    header.width shouldBe 80
  }

  // Ratio 3 is 4 to 3 and ratio 5 is 16 to 9, two from the middle of the table where a reader using the wrong row or
  // dividing the wrong way round lands somewhere else.
  @Test
  fun aWidthGivenAsARatioIsTheHeightTimesThatRatio() {
    val fourThirds =
      read(
        JxlBits()
          .write(1, 1)
          .write(5, 5)
          .write(3, 3)
          .write(1, 1),
      )
    val sixteenNinths =
      read(
        JxlBits()
          .write(1, 1)
          .write(8, 5)
          .write(5, 3)
          .write(1, 1),
      )

    fourThirds.height shouldBe 48
    fourThirds.width shouldBe 64
    sixteenNinths.height shouldBe 72
    sixteenNinths.width shouldBe 128
  }

  @Test
  fun fullSidesReadTheBitWidthTheirSelectorNames() {
    val bits =
      JxlBits()
        .write(0, 1)
        .write(3, 2)
        .write(999_999, 30)
        .write(0, 3)
        .write(0, 2)
        .write(99, 9)
        .write(1, 1)

    val header = read(bits)

    header.height shouldBe 1_000_000
    header.width shouldBe 100
  }

  @Test
  fun theMiddleSelectorsReadThirteenAndEighteenBits() {
    val bits =
      JxlBits()
        .write(0, 1)
        .write(1, 2)
        .write(4_999, 13)
        .write(0, 3)
        .write(2, 2)
        .write(199_999, 18)
        .write(1, 1)

    val header = read(bits)

    header.height shouldBe 5_000
    header.width shouldBe 200_000
  }

  @Test
  fun metadataMarkedAllDefaultReadsAsNormal() {
    val header = read(smallSquare().write(1, 1))

    header.orientation shouldBe PictureOrientation.Normal
  }

  @Test
  fun metadataWithoutExtraFieldsReadsAsNormal() {
    val header = read(smallSquare().write(0, 1).write(0, 1))

    header.orientation shouldBe PictureOrientation.Normal
  }

  // The field is stored one below the EXIF value, so a reader that forgot the offset reads 5 as a quarter turn.
  @Test
  fun anOrientationInTheExtraFieldsIsOneAboveTheStoredValue() {
    val transposed = read(smallSquare().write(0, 1).write(1, 1).write(4, 3))
    val turned = read(smallSquare().write(0, 1).write(1, 1).write(5, 3))

    transposed.orientation.exif shouldBe 5
    turned.orientation.exif shouldBe 6
  }

  @Test
  fun aCodestreamWithoutTheSignatureIsMalformed() {
    val codestream = byteArrayOf(0xFF.toByte(), 0x0B) + smallSquare().write(1, 1).toByteArray()

    assertFailsWith<MalformedPicture> { jxlHeaderOf(ByteView(codestream, codestream.size)) }
  }

  @Test
  fun aCodestreamEndingInsideTheFieldsReadIsMalformed() {
    val whole =
      JXL_CODESTREAM +
        JxlBits()
          .write(0, 1)
          .write(3, 2)
          .write(999_999, 30)
          .toByteArray()
    val cut = whole.copyOf(JXL_CODESTREAM.size + 2)

    assertFailsWith<MalformedPicture> { jxlHeaderOf(ByteView(cut, cut.size)) }
    assertFailsWith<MalformedPicture> { jxlHeaderOf(ByteView(JXL_CODESTREAM, JXL_CODESTREAM.size)) }
  }

  // A view ends where it is told to, not where its array does, so bytes past the end are not read.
  @Test
  fun aViewCutShortOfItsArrayIsMalformedWhereTheViewEnds() {
    val codestream = JXL_CODESTREAM + smallSquare().write(1, 1).toByteArray()

    assertFailsWith<MalformedPicture> { jxlHeaderOf(ByteView(codestream, JXL_CODESTREAM.size)) }
  }

  private fun smallSquare(): JxlBits = JxlBits().write(1, 1).write(3, 5).write(1, 3)

  private fun read(bits: JxlBits): JxlHeader {
    val codestream = JXL_CODESTREAM + bits.toByteArray()
    return jxlHeaderOf(ByteView(codestream, codestream.size))
  }
}
