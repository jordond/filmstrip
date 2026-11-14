package dev.jordond.filmstrip.codec

import dev.jordond.filmstrip.export.ExportSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class BitrateTest {
  @Test
  fun `a whole rate prints in the largest unit it reaches`() {
    Bitrate.mbps(8).toString() shouldBe "8 Mbps"
    Bitrate.kbps(800).toString() shouldBe "800 kbps"
    Bitrate(500).toString() shouldBe "500 bps"
  }

  @Test
  fun `each unit starts at its own threshold`() {
    Bitrate(0).toString() shouldBe "0 bps"
    Bitrate(999).toString() shouldBe "999 bps"
    Bitrate(1_000).toString() shouldBe "1 kbps"
    Bitrate(1_000_000).toString() shouldBe "1 Mbps"
  }

  @Test
  fun `a part rate keeps two decimals at most with no trailing zeros`() {
    Bitrate.mbps(2.5).toString() shouldBe "2.5 Mbps"
    Bitrate(1_050_000).toString() shouldBe "1.05 Mbps"
    Bitrate(1_230_000).toString() shouldBe "1.23 Mbps"
    Bitrate(1_500).toString() shouldBe "1.5 kbps"
    Bitrate(1_234_567).toString() shouldBe "1.23 Mbps"
  }

  @Test
  fun `rounding goes half up and carries into the whole number`() {
    Bitrate(2_499_999).toString() shouldBe "2.5 Mbps"
    Bitrate(1_995_000).toString() shouldBe "2 Mbps"
    Bitrate(1_994_999).toString() shouldBe "1.99 Mbps"
    Bitrate(1_005).toString() shouldBe "1.01 kbps"
  }

  @Test
  fun `a rate rounding up to the next unit reads in that unit`() {
    Bitrate(999_994).toString() shouldBe "999.99 kbps"
    Bitrate(999_995).toString() shouldBe "1 Mbps"
    Bitrate(999_999).toString() shouldBe "1 Mbps"
  }

  @Test
  fun `the far ends of a long print without overflowing`() {
    Bitrate(Long.MAX_VALUE).toString() shouldBe "9223372036854.78 Mbps"
    Bitrate(-2_500_000).toString() shouldBe "-2.5 Mbps"
    Bitrate(Long.MIN_VALUE).toString() shouldBe "-9223372036854775808 bps"
  }

  @Test
  fun `two rates of the same bits per second are equal and hash alike`() {
    Bitrate.mbps(4) shouldBe Bitrate(4_000_000)
    Bitrate.mbps(4).hashCode() shouldBe Bitrate(4_000_000).hashCode()
    Bitrate.mbps(4) shouldNotBe Bitrate(4_000_001)
  }

  @Test
  fun `a rate held by an export spec takes part in its equality`() {
    ExportSpec(bitrate = Bitrate.kbps(2_500)) shouldBe ExportSpec(bitrate = Bitrate(2_500_000))
    ExportSpec(bitrate = Bitrate.kbps(2_500)) shouldNotBe ExportSpec(bitrate = Bitrate.kbps(2_501))
    ExportSpec(bitrate = Bitrate.kbps(2_500)) shouldNotBe ExportSpec(bitrate = null)
  }
}
