package dev.jordond.filmstrip.test

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

class MidGreenStillTest {
  @Test
  fun everySampleDecodesToTheAuthoredMidGreen() {
    val (y, cb, cr) = MidGreenStill.YCBCR
    val tag = MidGreenStill.NCLX
    val decoded = ycbcrToRgb(y, cb, cr, tag.matrix, tag.primaries, tag.transfer, tag.fullRange).toList()
    val authored = AuthoredColors.channelsOf(AuthoredColors.MID_GREEN)

    withClue("$decoded against $authored within ${AuthoredColors.TOLERANCE}") {
      decoded.zip(authored).all { (read, want) -> abs(read - want) <= AuthoredColors.TOLERANCE } shouldBe true
    }
  }

  @Test
  fun theFileCarriesItsNclxAndOneHevcItem() {
    val heic = MidGreenStill.heic()
    val top = heifBoxes(heic)
    val properties =
      heic.childrenOf(heic.childrenOf(heic.childrenOf(top.named("meta"), fullBox = true).named("iprp")).named("ipco"))
    val colr = properties.named("colr")

    top.map { it.type } shouldContainExactly listOf("ftyp", "meta", "mdat")
    heic.textAt(top.named("ftyp").start) shouldBe "heic"
    heic.textAt(colr.start) shouldBe "nclx"
    listOf(heic.uInt16At(colr.start + 4), heic.uInt16At(colr.start + 6), heic.uInt16At(colr.start + 8)) shouldBe
      listOf(MidGreenStill.NCLX.primaries, MidGreenStill.NCLX.transfer, MidGreenStill.NCLX.matrix)
    (heic.uInt8At(colr.start + 10) shr 7) shouldBe 1
    properties.map { it.type } shouldContainExactly listOf("ispe", "pixi", "hvcC", "colr")
  }
}
