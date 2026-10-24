package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.CodestreamSignal
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Rewrites the video signal type of hand-built HEVC sequence parameter sets and checks each result against the unit
 * built directly with the new signal, so every other bit is shown to be kept.
 */
class HevcSpsRewriteTest {
  private val bt709Full = CodestreamSignal(1, 13, 1, true)
  private val bt601Limited = CodestreamSignal(1, 13, 6, false)
  private val zeros = CodestreamSignal(0, 0, 0, true)

  @Test
  fun `a parameter set without video usability information gains a minimal one`() {
    spsStating(SpsShape().nal(), bt709Full) shouldBe SpsShape(vui = minimalVui(bt709Full)).nal()
    spsStating(SpsShape().nal(), bt601Limited) shouldBe SpsShape(vui = minimalVui(bt601Limited)).nal()
  }

  @Test
  fun `information without a video signal type gains one after its aspect ratio and overscan`() {
    val before = SpsShape(vui = fullVui(signal = null))
    spsStating(before.nal(), bt709Full) shouldBe SpsShape(vui = fullVui(signal = bt709Full, videoFormat = 5)).nal()
  }

  @Test
  fun `a full vui keeps its aspect ratio and timing and bitstream restriction around a replaced signal type`() {
    val before = SpsShape(vui = fullVui(signal = CodestreamSignal(9, 16, 9, false)))
    spsStating(before.nal(), bt601Limited) shouldBe SpsShape(vui = fullVui(signal = bt601Limited)).nal()
  }

  @Test
  fun `a signal type without a colour description gains one and keeps its video format`() {
    val rangeOnly = SpsShape(vui = fullVui(signal = CodestreamSignal(null, null, null, false), videoFormat = 1))
    val expected = SpsShape(vui = fullVui(signal = bt709Full, videoFormat = 1)).nal()
    spsStating(rangeOnly.nal(), bt709Full) shouldBe expected
  }

  @Test
  fun `a parameter set that already states the whole signal comes back as itself`() {
    val agreeing = SpsShape(vui = fullVui(signal = bt709Full)).nal()
    spsStating(agreeing, bt709Full) shouldBeSameInstanceAs agreeing
  }

  @Test
  fun `agreeing on matrix and range is not enough when the primaries differ`() {
    val before = SpsShape(vui = fullVui(signal = CodestreamSignal(12, 13, 1, true))).nal()
    spsStating(before, bt709Full) shouldBe SpsShape(vui = fullVui(signal = bt709Full)).nal()
  }

  @Test
  fun `only the transfer disagreeing is enough to rewrite`() {
    val before = SpsShape(vui = fullVui(signal = CodestreamSignal(1, 1, 1, true))).nal()
    spsStating(before, bt709Full) shouldBe SpsShape(vui = fullVui(signal = bt709Full)).nal()
  }

  @Test
  fun `only the range disagreeing is enough to rewrite`() {
    val before = SpsShape(vui = fullVui(signal = CodestreamSignal(1, 13, 1, false))).nal()
    spsStating(before, bt709Full) shouldBe SpsShape(vui = fullVui(signal = bt709Full)).nal()
  }

  @Test
  fun `a parameter set extension after the vui is kept as it was`() {
    for (signal in listOf(null, CodestreamSignal(9, 16, 9, false))) {
      val before = SpsShape(vui = fullVui(signal, videoFormat = 5, extension = true)).nal()
      val expected = SpsShape(vui = fullVui(bt709Full, videoFormat = 5, extension = true)).nal()
      spsStating(before, bt709Full) shouldBe expected
    }
  }

  @Test
  fun `a colour description of zeros adds emulation prevention bytes`() {
    val expected = SpsShape(chromaFormat = 3, vui = minimalVui(zeros))
    val rewritten = spsStating(SpsShape(chromaFormat = 3).nal(), zeros)
    rewritten shouldBe escape(expected.bits().rbsp())
    insertedBytes(rewritten!!) shouldBeGreaterThan insertedBytes(SpsShape(chromaFormat = 3).nal())
  }

  @Test
  fun `replacing a colour description of zeros drops the emulation prevention bytes it needed`() {
    val before = SpsShape(vui = minimalVui(zeros)).nal()
    val rewritten = spsStating(before, bt709Full)
    rewritten shouldBe SpsShape(vui = minimalVui(bt709Full)).nal()
    insertedBytes(rewritten!!) shouldBeLessThan insertedBytes(before)
  }

  @Test
  fun `the identity matrix is stated only on a unit whose own layout is 4 4 4`() {
    for (chromaFormat in 0..2) {
      for (vui in listOf(null, minimalVui(bt709Full), minimalVui(zeros))) {
        spsStating(SpsShape(chromaFormat = chromaFormat, vui = vui).nal(), zeros).shouldBeNull()
      }
      spsStating(SpsShape(chromaFormat = chromaFormat).nal(), bt709Full) shouldBe
        SpsShape(chromaFormat = chromaFormat, vui = minimalVui(bt709Full)).nal()
    }
    spsSignal(spsStating(SpsShape(chromaFormat = 3).nal(), zeros)!!) shouldBe zeros
  }

  @Test
  fun `the rewritten unit reads back as the new signal`() {
    for (shape in listOf(SpsShape(), SpsShape(vui = fullVui(signal = null)), SpsShape(pcm = PCM, spsId = 3))) {
      spsSignal(spsStating(shape.nal(), bt601Limited)!!) shouldBe bt601Limited
    }
  }

  @Test
  fun `a unit that does not parse or is cut short gives null`() {
    spsStating(SpsShape(nalType = 34).nal(), bt709Full).shouldBeNull()
    spsStating(SpsShape(width = 0).nal(), bt709Full).shouldBeNull()
    val whole = SpsShape().nal()
    for (size in 0 until whole.size) spsStating(whole.copyOf(size), bt709Full).shouldBeNull()
  }

  @Test
  fun `a unit with no stop bit after its signal type gives null`() {
    val bits = SpsShape(vui = fullVui(signal = CodestreamSignal(9, 16, 9, false), tail = false)).bits()
    spsStating(escape(bits.rbsp(stop = false)), bt709Full).shouldBeNull()
  }

  private fun insertedBytes(nal: ByteArray): Int = nal.size - unescape(nal).size
}

/**
 * The video usability information the rewrite writes into a unit that has none, every flag clear but the video signal
 * type, which states [signal] with video format 5.
 */
internal fun minimalVui(signal: CodestreamSignal): Bits.() -> Unit =
  {
    flag(false).flag(false)
    signalType(signal, videoFormat = 5)
    repeat(7) { flag(false) }
  }

/**
 * Video usability information with every optional part present: an explicit sample aspect ratio, overscan, [signal]
 * when it is set, chroma sample locations, a default display window, timing with a picture order count rate and a
 * bitstream restriction, followed by the parameter set's extension flag unless [tail] is false.
 *
 * With [extension] the flag is set and a range extension follows it, every other extension flag clear.
 */
internal fun fullVui(
  signal: CodestreamSignal?,
  videoFormat: Int = 2,
  tail: Boolean = true,
  extension: Boolean = false,
): Bits.() -> Unit =
  {
    flag(true).u(8, 255).u(16, 4).u(16, 3)
    flag(true).flag(true)
    if (signal == null) flag(false) else signalType(signal, videoFormat)
    if (tail) {
      flag(true).ue(1).ue(2)
      flag(false).flag(false).flag(false)
      flag(true).ue(1).ue(2)
      ue(3).ue(4)
      flag(true).u(32, 1001L).u(32, 60000L)
      flag(true).ue(0).flag(false)
      flag(true).flag(true).flag(false)
      flag(true).ue(0).ue(2)
      ue(1).ue(15).ue(15)
      flag(extension)
      if (extension) flag(true).u(3, 0).u(4, 0).u(9, 0b101010101)
    }
  }

private fun Bits.signalType(
  signal: CodestreamSignal,
  videoFormat: Int,
) {
  flag(true).u(3, videoFormat).flag(signal.fullRange == true).flag(signal.matrix != null)
  if (signal.matrix != null) u(8, signal.primaries ?: 2).u(8, signal.transfer ?: 2).u(8, signal.matrix)
}
