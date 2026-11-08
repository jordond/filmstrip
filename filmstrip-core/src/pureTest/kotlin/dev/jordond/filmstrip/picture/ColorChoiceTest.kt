package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.Icc
import dev.jordond.filmstrip.color.PngGamma
import dev.jordond.filmstrip.color.TestIccProfiles
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Which of the color descriptions a container carries a picture lands in, and which of them land it as untagged.
 */
class ColorChoiceTest {
  private val p3 = Icc(TestIccProfiles.displayP3)
  private val grey = Icc(TestIccProfiles.grey22)
  private val bt2020Pq = Cicp(9, 16, 0, true)

  @Test
  fun `the first description that fits the stored channels wins`() {
    chooseColor(listOf(p3, bt2020Pq), 3) shouldBeSameInstanceAs p3
    chooseColor(listOf(bt2020Pq, p3), 3) shouldBe bt2020Pq
  }

  @Test
  fun `a profile of the other color space is passed over for the next description`() {
    chooseColor(listOf(grey, bt2020Pq), 3) shouldBe bt2020Pq
    chooseColor(listOf(p3, grey), 1) shouldBeSameInstanceAs grey
  }

  @Test
  fun `a channel count nobody recorded fits any profile that parses`() {
    chooseColor(listOf(grey, p3), null) shouldBeSameInstanceAs grey
  }

  @Test
  fun `a profile that does not parse is passed over`() {
    chooseColor(listOf(Icc(ByteArray(200) { it.toByte() }), bt2020Pq), 3) shouldBe bt2020Pq
  }

  @Test
  fun `unspecified code points say nothing and defer to the next description`() {
    chooseColor(listOf(Cicp(2, 13, 0, true), p3), 3) shouldBeSameInstanceAs p3
    chooseColor(listOf(Cicp(9, 2, 0, true), p3), 3) shouldBeSameInstanceAs p3
  }

  // Each of these is understood, so it decides the picture's color rather than handing it to the profile behind it.
  @Test
  fun `sRGB and unsupported code points land the picture untagged without falling through`() {
    chooseColor(listOf(Cicp(1, 13, 0, true), p3), 3).shouldBeNull()
    chooseColor(listOf(Cicp(3, 13, 0, true), p3), 3).shouldBeNull()
    chooseColor(listOf(Cicp(9, 12, 0, true), p3), 3).shouldBeNull()
  }

  @Test
  fun `png gamma at the sRGB equivalence lands untagged and any other gamma is kept`() {
    chooseColor(listOf(PngGamma(45455, null)), 3).shouldBeNull()
    chooseColor(listOf(PngGamma(50000, null)), 3) shouldBe PngGamma(50000, null)
  }

  @Test
  fun `nothing to choose from lands untagged`() {
    chooseColor(emptyList(), 3).shouldBeNull()
  }

  @Test
  fun `a profile is described only when it holds bytes`() {
    iccDescription(null).shouldBeNull()
    iccDescription(ByteArray(0)).shouldBeNull()
    iccDescription(TestIccProfiles.srgb).shouldNotBeNull().profile shouldBe TestIccProfiles.srgb
  }
}
