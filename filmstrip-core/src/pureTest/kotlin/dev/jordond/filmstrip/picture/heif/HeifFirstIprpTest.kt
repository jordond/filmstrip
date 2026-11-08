package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.fullBox
import dev.jordond.filmstrip.picture.fixtures.heif
import dev.jordond.filmstrip.picture.fixtures.ipma
import dev.jordond.filmstrip.picture.pictureOrientationOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Adds `ipma` boxes to hand-built HEICs, inside their `iprp` or in a second one after it, and checks that the alpha
 * and color readers take them only from the first, up to the `ipma` cap. The `mdat` sits ahead of `meta`, so growing
 * `meta` moves no item.
 */
class HeifFirstIprpTest {
  private val agreeing = SpsShape(vui = minimalVui(CodestreamSignal(1, 13, 1, true))).nal()

  @Test
  fun `the alpha item's auxC counts only from the first iprp`() {
    val input = TestHeic(listOf(agreeing), alphas = 1, emptyAlphaEntries = true, mdatFirst = true).bytes()
    heifAlpha(input).shouldBeNull()
    val entry = ipma(listOf(ALPHA to listOf(input.propertyIndex("hvcC"), input.propertyIndex("auxC"))))

    heifAlpha(input.inFirstIprp(entry)).shouldNotBeNull().item shouldBe ALPHA.toLong()
    heifAlpha(input.inSecondIprp(entry)).shouldBeNull()
  }

  @Test
  fun `the primary item's nclx counts only from the first iprp`() {
    val thumbnailColor = Cicp(9, 16, 9, false)
    val input = TestHeic(listOf(agreeing), nclx = null, thumbnailNclx = thumbnailColor, mdatFirst = true).bytes()
    nclxOf(input).shouldBeNull()
    val entry = ipma(listOf(1 to listOf(input.propertyIndex("colr"))))

    nclxOf(input.inFirstIprp(entry)) shouldBe thumbnailColor
    nclxOf(input.inSecondIprp(entry)).shouldBeNull()
  }

  @Test
  fun `a first iprp with no ipco leaves no alpha color or turn`() {
    val turned = TestHeic(listOf(agreeing), alphas = 1, transforms = listOf(irot(1)), mdatFirst = true).bytes()
    heifAlpha(turned).shouldNotBeNull()
    nclxOf(turned).shouldNotBeNull()
    pictureOrientationOf(turned) shouldBe heifOrientationOf(1, null)
    val led = turned.withIprp { iprp -> listOf(box("iprp", ipma(emptyList())), iprp) }

    heifAlpha(led).shouldBeNull()
    nclxOf(led).shouldBeNull()
    pictureOrientationOf(led) shouldBe PictureOrientation.Normal
  }

  @Test
  fun `the readers take the item's entry up to the ipma cap and pass it over past the cap`() {
    val turned = listOf(ispe(Size(40, 30)), irot(1))
    val layout = { boxes: Int ->
      val file = heif(turned, *Array(boxes - 1) { ipma(emptyList()) }, ipma(listOf(1 to listOf(1, 2))))
      ByteView(file, file.size)
    }
    heifOrientation(layout(MAX_LISTED_BOXES)) shouldBe heifOrientationOf(1, null)
    heifExtent(layout(MAX_LISTED_BOXES)) shouldBe Size(40, 30)
    heifOrientation(layout(MAX_LISTED_BOXES + 1)) shouldBe PictureOrientation.Normal
    heifExtent(layout(MAX_LISTED_BOXES + 1)).shouldBeNull()

    val input = TestHeic(listOf(agreeing), alphas = 1, emptyAlphaEntries = true, mdatFirst = true).bytes()
    val entry = ipma(listOf(ALPHA to listOf(input.propertyIndex("hvcC"), input.propertyIndex("auxC"))))
    val empty = { boxes: Int -> Array(boxes) { ipma(emptyList()) } }
    heifAlpha(input.inFirstIprp(*empty(MAX_LISTED_BOXES - 2), entry)).shouldNotBeNull().item shouldBe ALPHA.toLong()
    heifAlpha(input.inFirstIprp(*empty(MAX_LISTED_BOXES - 1), entry)).shouldBeNull()
  }

  private fun nclxOf(file: ByteArray): Cicp? = heifCodedItems(ByteView(file, file.size))?.nclx

  // The one-based index of the first property of type in the file's first ipco.
  private fun ByteArray.propertyIndex(type: String): Int {
    val view = ByteView(this, size)
    val ipco =
      view
        .metaParts()
        .shouldNotBeNull()
        .ipco
        .shouldNotBeNull()
    return view.boxes(ipco.start, ipco.end).indexOfFirst { it.type == fourcc(type) } + 1
  }

  private fun ByteArray.inFirstIprp(vararg ipmas: ByteArray): ByteArray =
    withIprp { iprp -> listOf(box("iprp", *childrenOf(iprp, 8).toTypedArray(), *ipmas)) }

  private fun ByteArray.inSecondIprp(ipma: ByteArray): ByteArray = withIprp { iprp -> listOf(iprp, box("iprp", ipma)) }

  // The file with its meta box's iprp replaced by the boxes change makes of it.
  private fun ByteArray.withIprp(change: (ByteArray) -> List<ByteArray>): ByteArray {
    val view = ByteView(this, size)
    return bytes {
      for (box in view.boxes(0, size)) {
        val whole = copyOfRange(box.at, box.end)
        if (box.type != META) {
          raw(whole)
          continue
        }
        val children = childrenOf(whole, 12).flatMap { if (it.type() == "iprp") change(it) else listOf(it) }
        raw(fullBox("meta", 0, 0, *children.toTypedArray()))
      }
    }
  }

  // Every box in parent past its first skip bytes, each whole.
  private fun childrenOf(
    parent: ByteArray,
    skip: Int,
  ): List<ByteArray> {
    val view = ByteView(parent, parent.size)
    return view.boxes(skip, parent.size).map { parent.copyOfRange(it.at, it.end) }.toList()
  }

  private fun ByteArray.type(): String = decodeToString(4, 8)

  private companion object {
    const val ALPHA = TestHeic.FIRST_ALPHA_ITEM
  }
}
