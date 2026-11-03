package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.heifPrimaryGrid
import dev.jordond.filmstrip.picture.heif.heifPrimaryOverlay
import dev.jordond.filmstrip.picture.heifPrimaryIsTiled
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * HEIF stills whose primary item is an `iovl`, read back through core's own reader.
 */
class HeifOverlayTest {
  private val input = ColorPictures.NCLX_BT709_LIMITED.thumbnail()

  @Test
  fun anOverlaysInputsCarryTheirOffsetsAndSizesInOrder() {
    for (wide in listOf(false, true)) {
      val offsets = listOf(0 to 0, 16 to 8, -3 to 4)
      val fill = listOf(65535, 32896, 0, 40000)
      val bytes = HeifOverlays.overlay("avif", input, offsets, Size(24, 16), fill, wide = wide)
      val overlay = withClue("wide $wide") { heifPrimaryOverlay(bytes).shouldNotBeNull() }
      overlay.inputs.map { it.item } shouldBe listOf(2L, 3L, 4L)
      overlay.inputs.map { it.x to it.y } shouldBe offsets
      overlay.inputs.map { it.size }.distinct() shouldBe listOf(Size(8, 8))
      overlay.size shouldBe Size(24, 16)
      listOf(overlay.fill.red, overlay.fill.green, overlay.fill.blue, overlay.fill.alpha) shouldBe fill
      overlay.fill.opaque.shouldBeFalse()
      heifPrimaryIsTiled(bytes).shouldBeTrue()
      heifPrimaryGrid(bytes).shouldBeNull()
    }
  }

  @Test
  fun theFillShowsWhereverNoInputCoversTheCanvas() {
    val shown = { offsets: List<Pair<Int, Int>>, canvas: Size ->
      heifPrimaryOverlay(HeifOverlays.overlay("avif", input, offsets, canvas)).shouldNotBeNull().fillShown
    }
    withClue("two inputs side by side") { shown(listOf(0 to 0, 8 to 0), Size(16, 8)).shouldBeFalse() }
    withClue("two inputs overlapping across the canvas") { shown(listOf(-2 to 0, 6 to 0), Size(14, 8)).shouldBeFalse() }
    withClue("a gap in the middle column") { shown(listOf(0 to 0, 9 to 0), Size(17, 8)).shouldBeTrue() }
    withClue("a row below the inputs") { shown(listOf(0 to 0, 8 to 0), Size(16, 9)).shouldBeTrue() }
    withClue("a hole in the middle of four") {
      shown(listOf(0 to 0, 10 to 0, 0 to 10, 10 to 10, 5 to 0, 0 to 5, 10 to 5, 5 to 10), Size(18, 18)).shouldBeTrue()
    }
    withClue("the hole filled") {
      shown(listOf(0 to 0, 10 to 0, 0 to 10, 10 to 10, 5 to 0, 0 to 5, 10 to 5, 5 to 10, 5 to 5), Size(18, 18))
        .shouldBeFalse()
    }
  }

  @Test
  fun aPictureWhosePrimaryItemIsNoOverlayHasNone() {
    val avif = ColorPictures.NCLX_BT709_LIMITED
    heifPrimaryOverlay(avif.bytes()).shouldBeNull()
    heifPrimaryOverlay(avif.gridBytes()).shouldBeNull()
    val truncated = HeifOverlays.overlay("avif", input, listOf(0 to 0), Size(8, 8)).copyOf(TRUNCATED)
    withClue("a truncated overlay") { heifPrimaryOverlay(truncated).shouldBeNull() }
    withClue("a PNG") { heifPrimaryOverlay(PNG_SIGNATURE).shouldBeNull() }
  }

  private companion object {
    const val TRUNCATED = 40
    val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
