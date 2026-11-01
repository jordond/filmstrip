package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.CodestreamSignal
import dev.jordond.filmstrip.media.YcbcrCoding
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test

/**
 * Reads the video signal out of hand-built HEVC sequence parameter sets, stepping over every field ahead of the video
 * usability information, and carries it from an `hvcC` into the YCbCr coding of a HEIC.
 */
class HevcSpsTest {
  private val nothing = CodestreamSignal(null, null, null, null)
  private val bt2020 = CodestreamSignal(9, 16, 9, false)
  private val bt2020Vui = vui(fullRange = false, colour = Triple(9, 16, 9))

  private fun signal(shape: SpsShape): CodestreamSignal? = spsSignal(shape.nal())

  @Test
  fun `a parameter set without video usability information signals nothing`() {
    signal(SpsShape()) shouldBe nothing
  }

  @Test
  fun `information without a video signal type signals nothing`() {
    signal(SpsShape(vui = vui())) shouldBe nothing
    signal(SpsShape(vui = vui(aspect = 255, overscan = true))) shouldBe nothing
  }

  @Test
  fun `a video signal type without a colour description signals only its range`() {
    signal(SpsShape(vui = vui(fullRange = true))) shouldBe CodestreamSignal(null, null, null, true)
    signal(SpsShape(vui = vui(aspect = 1, fullRange = false))) shouldBe CodestreamSignal(null, null, null, false)
  }

  @Test
  fun `a colour description signals its code points after a sample aspect ratio and overscan`() {
    val described = vui(aspect = 255, overscan = false, fullRange = false, colour = Triple(9, 16, 9))
    signal(SpsShape(vui = described)) shouldBe bt2020
    signal(SpsShape(vui = vui(fullRange = true, colour = Triple(1, 13, 1)))) shouldBe CodestreamSignal(1, 13, 1, true)
    signal(SpsShape(vui = vui(fullRange = true, colour = Triple(12, 1, 6)))) shouldBe CodestreamSignal(12, 1, 6, true)
  }

  @Test
  fun `sub-layers with and without their own profile and level are stepped over`() {
    val layers = listOf(true to false, false to true, true to true)
    signal(SpsShape(subLayers = layers, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(subLayers = layers, orderingForAll = true, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(subLayers = List(6) { false to false }, orderingForAll = true, vui = bt2020Vui)) shouldBe bt2020
  }

  @Test
  fun `a conformance window and separate colour planes and pcm are stepped over`() {
    signal(SpsShape(conformanceWindow = listOf(0, 3, 1, 30), vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(chromaFormat = 3, pcm = PCM, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(chromaFormat = 0, pocLsbMinus4 = 12, vui = bt2020Vui)) shouldBe bt2020
  }

  @Test
  fun `scaling lists are stepped over whether each one is copied or explicit`() {
    signal(SpsShape(scalingLists = scalingLists(), vui = bt2020Vui)) shouldBe bt2020
  }

  @Test
  fun `scaling lists left at their defaults carry no data`() {
    signal(SpsShape(scalingLists = scalingLists(present = false), vui = bt2020Vui)) shouldBe bt2020
  }

  @Test
  fun `scaling list values are held to their ranges`() {
    for (value in listOf(-7, 100, 247)) signal(scaled(dc = value)) shouldBe bt2020
    for (value in listOf(-8, 248)) signal(scaled(dc = value)).shouldBeNull()
    for (value in listOf(-128, 5, 127)) signal(scaled(coefficient = value)) shouldBe bt2020
    for (value in listOf(-129, 128)) signal(scaled(coefficient = value)).shouldBeNull()
    signal(scaled(reference = 0)) shouldBe bt2020
    signal(scaled(reference = 2)).shouldBeNull()
  }

  @Test
  fun `the parameter set id and bit depth are held to their ranges`() {
    signal(SpsShape(spsId = 15, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(spsId = 16, vui = bt2020Vui)).shouldBeNull()
    for (depth in listOf(2, 4, 8)) signal(SpsShape(bitDepthMinus8 = depth, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(bitDepthMinus8 = 9, vui = bt2020Vui)).shouldBeNull()
  }

  @Test
  fun `the picture size and conformance window keep a whole picture`() {
    signal(SpsShape(width = 72, height = 136, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(width = 60, vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(height = 0, vui = bt2020Vui)).shouldBeNull()
    signal(windowed(1, 16, 15, 0, 0)) shouldBe bt2020
    signal(windowed(1, 16, 16, 0, 0)).shouldBeNull()
    signal(windowed(3, 32, 31, 0, 0)) shouldBe bt2020
    signal(windowed(3, 32, 32, 0, 0)).shouldBeNull()
    signal(windowed(2, 0, 0, 40, 23)) shouldBe bt2020
    signal(windowed(1, 0, 0, 20, 12)).shouldBeNull()
  }

  @Test
  fun `sub-layer ordering is held to the buffer size and never shrinks`() {
    signal(ordered(Triple(4, 2, 0L), Triple(9, 6, 4294967294L))) shouldBe bt2020
    signal(ordered(Triple(15, 15, 0L))) shouldBe bt2020
    signal(ordered(Triple(16, 0, 0L))).shouldBeNull()
    signal(ordered(Triple(3, 4, 0L))).shouldBeNull()
    signal(ordered(Triple(4, 2, 0L), Triple(3, 2, 0L))).shouldBeNull()
    signal(ordered(Triple(4, 2, 0L), Triple(4, 1, 0L))).shouldBeNull()
    signal(ordered(Triple(4, 2, 4294967295L))).shouldBeNull()
  }

  @Test
  fun `block sizes and transform depths are held to their ranges`() {
    for (sizes in listOf(listOf(3, 0, 0, 3, 0, 0), listOf(1, 1, 1, 2, 2, 1), listOf(0, 3, 0, 3, 4, 4))) {
      signal(SpsShape(blockSizes = sizes, vui = bt2020Vui)) shouldBe bt2020
    }
    val broken =
      listOf(
        listOf(0, 4, 0, 3, 0, 0),
        listOf(0, 0, 0, 1, 0, 0),
        listOf(0, 3, 1, 2, 0, 0),
        listOf(0, 3, 0, 4, 0, 0),
        listOf(0, 3, 0, 3, 5, 0),
        listOf(0, 3, 0, 3, 0, 5),
      )
    for (sizes in broken) signal(SpsShape(blockSizes = sizes, vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(blockSizes = listOf(3, 0, 0, 3, 0, 0), width = 96, vui = bt2020Vui)).shouldBeNull()
  }

  @Test
  fun `pcm sizes stay within the bit depth and block sizes`() {
    val largeBlocks = listOf(1, 2, 0, 3, 0, 0)
    signal(SpsShape(pcm = listOf(8, 7, 0, 1), bitDepthMinus8 = 2, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(pcm = listOf(7, 7, 1, 1), blockSizes = largeBlocks, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(pcm = listOf(8, 7, 0, 1), vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(pcm = listOf(7, 8, 0, 1), vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(pcm = PCM, blockSizes = largeBlocks, vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(pcm = listOf(7, 7, 3, 0), vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(pcm = listOf(7, 7, 0, 3), vui = bt2020Vui)).shouldBeNull()
  }

  @Test
  fun `a short-term set holds no more pictures than the buffer`() {
    signal(buffered(4) { ue(1).explicit(listOf(-1, -2, -3, -4), emptyList()) }) shouldBe bt2020
    signal(buffered(4) { ue(1).explicit(listOf(-1, -2), listOf(1, 2)) }) shouldBe bt2020
    signal(buffered(4) { ue(1).explicit(listOf(-1, -2, -3, -4, -5), emptyList()) }).shouldBeNull()
    signal(buffered(4) { ue(1).explicit(listOf(-1, -2), listOf(1, 2, 3)) }).shouldBeNull()
    val fourDeltas: Bits.() -> Unit = { ue(2).explicit(listOf(-1, -3), listOf(2)).predicted(-1, List(4) { true }) }
    signal(buffered(4, fourDeltas)) shouldBe bt2020
    signal(buffered(3, fourDeltas)).shouldBeNull()
  }

  @Test
  fun `a predicted set's delta is held to its range`() {
    for (delta in listOf(32768, 100, -32768)) signal(predictedBy(delta)) shouldBe bt2020
    signal(predictedBy(32769)).shouldBeNull()
  }

  @Test
  fun `a predicted short-term set takes the delta count derived for the set before it`() {
    signal(SpsShape(shortTermSets = { predictedChain(setFourEntries = 5) }, vui = bt2020Vui)) shouldBe bt2020
  }

  @Test
  fun `a last predicted set written for the wrong delta count shifts the signal`() {
    signal(SpsShape(shortTermSets = { predictedChain(5, sets = 5) }, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(shortTermSets = { predictedChain(4, sets = 5) }, vui = bt2020Vui)) shouldNotBe bt2020
    signal(SpsShape(shortTermSets = { predictedChain(6, sets = 5) }, vui = bt2020Vui)) shouldNotBe bt2020
  }

  @Test
  fun `long-term pictures take the picture order count width`() {
    val pocs = List(32) { it * 2047 }
    signal(SpsShape(pocLsbMinus4 = 12, longTermPocs = pocs, vui = bt2020Vui)) shouldBe bt2020
    signal(SpsShape(pocLsbMinus4 = 0, longTermPocs = listOf(15, 0), vui = bt2020Vui)) shouldBe bt2020
  }

  @Test
  fun `emulation prevention bytes are dropped before reading`() {
    val shape = SpsShape(vui = bt2020Vui)
    val rbsp = shape.bits().rbsp()
    val nal = shape.nal()
    (nal.size > rbsp.size) shouldBe true
    nal.toList().windowed(3).any { it == listOf<Byte>(0, 0, 3) } shouldBe true
    spsSignal(nal) shouldBe bt2020
  }

  @Test
  fun `a unit that is not a base layer parameter set or breaks a field's range is not understood`() {
    signal(SpsShape(nalType = 34, vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(layer = 1, vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(subLayers = List(7) { false to false }, vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(chromaFormat = 4, vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(pocLsbMinus4 = 13, vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(shortTermSets = { ue(65) }, vui = bt2020Vui)).shouldBeNull()
    signal(SpsShape(shortTermSets = { ue(1).explicit(List(16) { -1 - it }, emptyList()) }, vui = bt2020Vui))
      .shouldBeNull()
    signal(SpsShape(longTermPocs = List(33) { 0 }, vui = bt2020Vui)).shouldBeNull()
    spsSignal(ByteArray(0)).shouldBeNull()
  }

  @Test
  fun `a unit cut short at any byte is not understood`() {
    for (shape in shapes()) {
      val nal = shape.nal()
      for (length in 0 until nal.size - 1) spsSignal(nal.copyOf(length)).shouldBeNull()
    }
  }

  @Test
  fun `a unit cut at any bit never throws`() {
    for (shape in shapes()) {
      val bits = shape.bits()
      for (count in 0 until bits.size) {
        spsSignal(escape(bits.rbsp(count, stop = false)))
        spsSignal(escape(bits.rbsp(count, stop = true)))
      }
    }
  }

  @Test
  fun `a corrupted unit never throws`() {
    val random = Random(41)
    for (nal in shapes().map { it.nal() }) {
      repeat(2000) {
        val corrupted = nal.copyOf()
        repeat(1 + it % 4) { corrupted[random.nextInt(corrupted.size)] = random.nextInt(256).toByte() }
        spsSignal(corrupted)
      }
    }
    repeat(2000) {
      spsSignal(byteArrayOf(0x42, 0x01) + random.nextBytes(random.nextInt(80)))
      spsSignal(random.nextBytes(random.nextInt(80)))
    }
  }

  // The coding of an 8-bit 4:2:0 item beside BT.709 primaries and the sRGB transfer, the samples every hvcC and av1C
  // here describe.
  private fun eightBit420(
    matrix: Int,
    fullRange: Boolean,
    codec: CodecKind,
    codestream: CodestreamSignal? = null,
  ) = YcbcrCoding(matrix, fullRange, codec, codestream, bitDepth = 8, chromaFormat = 1, primaries = 1, transfer = 13)

  @Test
  fun `a heic's coding carries the signal of its hvcC's first sequence parameter set`() {
    val described = SpsShape(vui = vui(fullRange = true, colour = Triple(1, 13, 1))).nal()
    val other = SpsShape(vui = bt2020Vui).nal()
    val nclx = nclx(1, 13, matrix = 6, fullRange = true)
    val file = heifItems(listOf(nclx, hvccOf(VPS_NAL, described, other, PPS_NAL)), itemInfo = iinf(1 to "hvc1"))
    pictureYcbcrCoding(file) shouldBe eightBit420(6, true, CodecKind.Hevc, CodestreamSignal(1, 13, 1, true))
    val hev1 = heifItems(listOf(nclx, hvccOf(SpsShape().nal())), itemInfo = iinf(1 to "hev1"))
    pictureYcbcrCoding(hev1)?.codestream shouldBe nothing
  }

  @Test
  fun `a broken array after the sequence parameter set does not hide it`() {
    val box = hvccOf(VPS_NAL, SpsShape(vui = bt2020Vui).nal(), PPS_NAL)
    val length = box.size - PPS_NAL.size - 2
    box[length] = 0xFF.toByte()
    box[length + 1] = 0xFF.toByte()
    val heic = heifItems(listOf(nclx(1, 13), box), itemInfo = iinf(1 to "hvc1"))
    pictureYcbcrCoding(heic)?.codestream shouldBe bt2020
  }

  @Test
  fun `a grid carries its first tile's signal`() {
    val sps = SpsShape(vui = bt2020Vui).nal()
    val grid = heifItems(listOf(nclx(1, 13)), tile = listOf(hvccOf(sps)), itemInfo = iinf(1 to "grid", 2 to "hvc1"))
    pictureYcbcrCoding(grid)?.codestream shouldBe bt2020
  }

  @Test
  fun `an avif and a heic without a readable parameter set carry no signal or samples but keep their nclx`() {
    val sps = SpsShape(vui = bt2020Vui).nal()
    val nclx = nclx(1, 13, matrix = 1, fullRange = false)
    val avif = heifItems(listOf(nclx, av1c(monochrome = false)), itemInfo = iinf(1 to "av01"))
    pictureYcbcrCoding(avif) shouldBe YcbcrCoding(1, false, CodecKind.Av1, null, primaries = 1, transfer = 13)
    pictureYcbcrCoding(heifItems(listOf(nclx, hvccOf(sps)), itemInfo = iinf(1 to "av01")))?.codestream.shouldBeNull()
    val unread =
      listOf(hvccOf(VPS_NAL, PPS_NAL), hvccOf(sps.copyOf(20)), hvccOf(PPS_NAL + sps), hvcc(chromaFormat = 1))
    for (configuration in unread) {
      val heic = heifItems(listOf(nclx, configuration), itemInfo = iinf(1 to "hvc1"))
      pictureYcbcrCoding(heic) shouldBe YcbcrCoding(1, false, CodecKind.Hevc, null, primaries = 1, transfer = 13)
    }
  }

  @Test
  fun `a cut or corrupted heic never throws and keeps its signal whole or drops it`() {
    val sps = SpsShape(scalingLists = scalingLists(), vui = bt2020Vui).nal()
    val heic = heifItems(listOf(nclx(1, 13), hvccOf(VPS_NAL, sps, PPS_NAL)), itemInfo = iinf(1 to "hvc1"))
    for (length in heic.indices) {
      pictureYcbcrCoding(heic.copyOf(length))?.codestream?.let { it shouldBe bt2020 }
    }
    val random = Random(43)
    repeat(2000) {
      val corrupted = heic.copyOf()
      repeat(1 + it % 4) { corrupted[random.nextInt(corrupted.size)] = random.nextInt(256).toByte() }
      pictureYcbcrCoding(corrupted)
    }
  }

  private fun shapes(): List<SpsShape> =
    listOf(
      SpsShape(vui = bt2020Vui),
      SpsShape(subLayers = listOf(true to true, false to true), orderingForAll = true, vui = bt2020Vui),
      SpsShape(conformanceWindow = listOf(1, 2, 3, 4), pcm = PCM, scalingLists = scalingLists(), vui = bt2020Vui),
      SpsShape(shortTermSets = { predictedChain(setFourEntries = 5) }, longTermPocs = listOf(3, 9), vui = bt2020Vui),
    )

  // Seven sets, each predicted one deriving its deltas from the set before by H.265 equations 7-61 and 7-62:
  // 0 explicit, s0 -1 -3 and s1 2
  // 1 from 0 by -1, flags used, kept, dropped, used: s0 -1 -2 -4
  // 2 from 1 by +2, all used: s0 -2 and s1 1 2, the deltas that land on 0 dropped
  // 3 from 2 by -3, all used: s0 -1 -2 -3 -5, four deltas so set 4 writes five flag pairs
  // 4 from 3 by +1, all dropped: empty
  // 5 from 4 by +1, its one flag used: s1 1
  // 6 explicit after a cleared prediction flag, empty
  // Only the first [sets] are written, so set 4 can be the last one, with nothing after it to resynchronise on.
  private fun Bits.predictedChain(
    setFourEntries: Int,
    sets: Int = 7,
  ) {
    val chain =
      listOf<Bits.() -> Unit>(
        { explicit(listOf(-1, -3), listOf(2)) },
        { predicted(-1, listOf(true, false, null, true)) },
        { predicted(2, List(4) { true }) },
        { predicted(-3, List(4) { true }) },
        { predicted(1, List(setFourEntries) { null }) },
        { predicted(1, listOf(true)) },
        { flag(false).explicit(emptyList(), emptyList()) },
      )
    ue(sets)
    chain.take(sets).forEach { it() }
  }

  // Each flag true for used by the current picture, false for kept as a delta only and null for dropped.
  private fun Bits.predicted(
    deltaRps: Int,
    flags: List<Boolean?>,
  ): Bits {
    flag(true).flag(deltaRps < 0).ue(abs(deltaRps) - 1)
    for (used in flags) {
      flag(used == true)
      if (used != true) flag(used == false)
    }
    return this
  }

  private fun scaled(
    dc: Int? = null,
    coefficient: Int? = null,
    reference: Int? = null,
  ) = SpsShape(scalingLists = scalingLists(dc = dc, coefficient = coefficient, reference = reference), vui = bt2020Vui)

  private fun windowed(
    chromaFormat: Int,
    vararg offsets: Int,
  ) = SpsShape(chromaFormat = chromaFormat, conformanceWindow = offsets.toList(), vui = bt2020Vui)

  private fun ordered(vararg ordering: Triple<Int, Int, Long>) =
    SpsShape(subLayers = listOf(false to false), orderingForAll = true, ordering = ordering.toList(), vui = bt2020Vui)

  private fun buffered(
    buffering: Int,
    sets: Bits.() -> Unit,
  ) = SpsShape(ordering = listOf(Triple(buffering, 0, 0L)), shortTermSets = sets, vui = bt2020Vui)

  // A one-picture set, then one predicted from it by deltaRps that keeps both its pictures.
  private fun predictedBy(deltaRps: Int): SpsShape {
    val sets: Bits.() -> Unit = { ue(2).explicit(listOf(-1), emptyList()).predicted(deltaRps, listOf(true, true)) }
    return SpsShape(shortTermSets = sets, vui = bt2020Vui)
  }
}

/**
 * Writes an explicit short-term set, [negative] descending from zero and [positive] ascending, each one used by the
 * current picture.
 */
internal fun Bits.explicit(
  negative: List<Int>,
  positive: List<Int>,
): Bits {
  ue(negative.size).ue(positive.size)
  var poc = 0
  for (delta in negative) {
    ue(poc - delta - 1).flag(true)
    poc = delta
  }
  poc = 0
  for (delta in positive) {
    ue(delta - poc - 1).flag(true)
    poc = delta
  }
  return this
}
