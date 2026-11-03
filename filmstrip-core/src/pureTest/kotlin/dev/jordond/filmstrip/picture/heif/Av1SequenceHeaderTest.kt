package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.deferring
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.random.Random
import kotlin.test.Test

/**
 * Reads and rewrites hand-built AV1 sequence headers, comparing each rewrite byte for byte with the header built from
 * scratch around the new color config, so every other bit is checked.
 */
class Av1SequenceHeaderTest {
  private val p3 = CodestreamSignal(12, 13, 6, true)
  private val bt709Limited = CodestreamSignal(1, 1, 1, false)

  private val shapes =
    listOf(
      SequenceShape(),
      SequenceShape(fullRange = false, chromaPosition = 2),
      SequenceShape(description = Triple(1, 13, 1)),
      SequenceShape(reduced = false, still = false, operatingPoints = listOf(OperatingPoint(level = 3))),
      SequenceShape(reduced = false, timing = true, ticksPerPicture = 1L shl 20),
      SequenceShape(reduced = false, timing = true, ticksPerPicture = 0, decoderModel = true),
      SequenceShape(
        reduced = false,
        timing = true,
        decoderModel = true,
        initialDisplayDelay = true,
        operatingPoints =
          listOf(
            OperatingPoint(idc = 0x307, level = 9, tier = true, decoderModel = true, displayDelay = 5),
            OperatingPoint(idc = 0x103, level = 4, displayDelay = null),
            OperatingPoint(idc = 0x101, level = 12, tier = false, decoderModel = true, displayDelay = 0),
          ),
      ),
      SequenceShape(reduced = false, frameIds = true, orderHint = true),
      SequenceShape(reduced = false, chooseScreenContent = false, forceScreenContent = true, chooseIntegerMv = false),
      SequenceShape(reduced = false, chooseScreenContent = false, forceScreenContent = false),
      SequenceShape(highBitDepth = true, description = Triple(9, 16, 9), fullRange = false),
      SequenceShape(profile = 1, highBitDepth = true),
      SequenceShape(profile = 2),
      SequenceShape(profile = 2, highBitDepth = true, twelveBit = true, subsampling = 0 to 0),
      SequenceShape(profile = 2, highBitDepth = true, twelveBit = true, subsampling = 1 to 0),
      SequenceShape(profile = 2, highBitDepth = true, twelveBit = true, subsampling = 1 to 1, chromaPosition = 1),
      SequenceShape(mono = true, fullRange = false),
      SequenceShape(mono = true, description = Triple(1, 13, 0)),
      SequenceShape(separateUvDeltaQ = true, filmGrain = true),
      SequenceShape(padding = 3),
    )

  @Test
  fun `every header reads back with the signal and layout it was built with`() {
    for (shape in shapes) {
      val layout = sequenceHeaderLayout(shape.payload())!!
      layout.signal shouldBe shape.signal
      layout.profile shouldBe shape.profile
      layout.mono shouldBe shape.mono
      layout.twelveBit shouldBe shape.twelveBit
      (layout.subsamplingX to layout.subsamplingY) shouldBe shape.effectiveSubsampling
      layout.paddingBytes shouldBe shape.padding
    }
  }

  @Test
  fun `bt709 primaries with the srgb transfer and identity matrix imply full range and no subsampling`() {
    val layout = sequenceHeaderLayout(SequenceShape(profile = 1, description = Triple(1, 13, 0)).payload())!!
    layout.signal shouldBe CodestreamSignal(1, 13, 0, true)
    (layout.subsamplingX to layout.subsamplingY) shouldBe (0 to 0)
    layout.chromaPosition.shouldBeNull()
  }

  @Test
  fun `a rewrite changes only the color config`() {
    for (shape in shapes) {
      for (target in listOf(p3, bt709Limited)) {
        sequenceHeaderStating(shape.payload(), target) shouldBe shape.stating(target).payload()
      }
    }
  }

  @Test
  fun `an identity matrix header gains subsampling bits that keep its samples unsubsampled`() {
    val srgb = Triple(1, 13, 0)
    val profileOne = SequenceShape(profile = 1, description = srgb)
    sequenceHeaderStating(profileOne.payload(), p3) shouldBe profileOne.stating(p3).payload()
    val twelveBit =
      SequenceShape(profile = 2, highBitDepth = true, twelveBit = true, description = srgb, subsampling = 0 to 0)
    sequenceHeaderStating(twelveBit.payload(), p3) shouldBe twelveBit.stating(p3).payload()
    for (unsignalled in listOf(SequenceShape(description = srgb), SequenceShape(profile = 2, description = srgb))) {
      sequenceHeaderStating(unsignalled.payload(), p3).shouldBeNull()
    }
  }

  @Test
  fun `a header that already agrees comes back as itself`() {
    val payload = SequenceShape(description = Triple(12, 13, 6)).payload()
    sequenceHeaderStating(payload, p3) shouldBeSameInstanceAs payload
  }

  @Test
  fun `the identity matrix is written as 0 for unsubsampled samples only`() {
    val identities =
      listOf(CodestreamSignal(1, 13, 0, true), CodestreamSignal(2, 2, 0, false), CodestreamSignal(9, 16, 0, true))
    for (shape in shapes) {
      for (identity in identities) {
        val payload = shape.payload()
        val rewritten = sequenceHeaderStating(payload, identity)
        when {
          shape.signal == identity -> {
            rewritten shouldBeSameInstanceAs payload
          }
          shape.mono || shape.effectiveSubsampling != (0 to 0) -> {
            rewritten.shouldBeNull()
          }
          else -> {
            rewritten shouldBe shape.stating(identity).payload()
            sequenceHeaderLayout(rewritten!!)!!.signal shouldBe identity
          }
        }
      }
    }
  }

  @Test
  fun `an identity header takes the target's primaries and transfer`() {
    val srgb = SequenceShape(profile = 1, description = Triple(1, 13, 0))
    val bt2020 = SequenceShape(profile = 1, description = Triple(9, 16, 0), fullRange = false)
    val targets =
      mapOf(
        srgb to listOf(CodestreamSignal(2, 2, 0, true), CodestreamSignal(9, 16, 0, true)),
        bt2020 to listOf(CodestreamSignal(2, 2, 0, false), CodestreamSignal(1, 13, 0, true)),
      )
    for ((shape, signals) in targets) {
      for (target in signals) {
        val rewritten = sequenceHeaderStating(shape.payload(), target)
        rewritten shouldBe shape.stating(target).payload()
        sequenceHeaderLayout(rewritten!!)!!.signal shouldBe target
      }
    }
  }

  @Test
  fun `the srgb identity description is written without a range or subsampling bits`() {
    val srgb = CodestreamSignal(1, 13, 0, true)
    val unsubsampled =
      listOf(
        SequenceShape(profile = 1),
        SequenceShape(profile = 1, fullRange = false, description = Triple(9, 16, 9)),
        SequenceShape(profile = 2, highBitDepth = true, twelveBit = true, subsampling = 0 to 0),
      )
    for (shape in unsubsampled) {
      val rewritten = sequenceHeaderStating(shape.payload(), srgb)!!
      rewritten shouldBe shape.stating(srgb).payload()
      val layout = sequenceHeaderLayout(rewritten)!!
      (layout.subsamplingX to layout.subsamplingY) shouldBe (0 to 0)
      sequenceHeaderStating(shape.payload(), srgb.copy(fullRange = false)).shouldBeNull()
    }
  }

  @Test
  fun `headers outside the syntax read as nothing`() {
    sequenceHeaderLayout(SequenceShape(profile = 3).payload()).shouldBeNull()
    sequenceHeaderLayout(SequenceShape(still = false).payload()).shouldBeNull()
    val payload = SequenceShape().payload()
    sequenceHeaderLayout(payload + byteArrayOf(1)).shouldBeNull()
    sequenceHeaderLayout(payload.copyOf().also { it[it.size - 1] = 0 }).shouldBeNull()
    sequenceHeaderLayout(ByteArray(0)).shouldBeNull()
  }

  @Test
  fun `every truncation and seeded corruption gives nothing or a header stating the target`() {
    val random = Random(53)
    for (shape in shapes) {
      val payload = shape.payload()
      for (size in 0 until payload.size) shouldStateOrRefuse(payload.copyOf(size))
      repeat(FUZZ_ROUNDS) {
        val corrupted = payload.copyOf()
        repeat(1 + random.nextInt(3)) {
          val at = random.nextInt(corrupted.size)
          corrupted[at] = (corrupted[at].toInt() xor (1 shl random.nextInt(8))).toByte()
        }
        shouldStateOrRefuse(corrupted)
      }
    }
  }

  @Test
  fun `leading obus stop at the first frame and read every size field`() {
    val header = SequenceShape().obu(sizeBytes = 3, extension = true)
    val frame = obu(FRAME_OBU, byteArrayOf(1, 2, 3))
    val stream = obu(TEMPORAL_DELIMITER_OBU, ByteArray(0)) + obu(METADATA_OBU, byteArrayOf(9)) + header + frame
    val view = ByteView(stream, stream.size)
    val obus = view.leadingObus(0, stream.size)
    obus.map { it.type } shouldBe listOf(TEMPORAL_DELIMITER_OBU, METADATA_OBU, SEQUENCE_HEADER)
    obus.last().sizeBytes shouldBe 3
    obus.last().end shouldBe stream.size - frame.size
    view.sequenceHeaderLayouts(0, stream.size).map { it?.signal } shouldBe listOf(SequenceShape().signal)
    val unsized = SequenceShape().obu(sizeBytes = 0)
    ByteView(unsized, unsized.size).leadingObus(0, unsized.size).single().end shouldBe unsized.size
  }

  @Test
  fun `an obu header keeps its size width when the new size fits and widens when it does not`() {
    val padded = SequenceShape().obu(sizeBytes = 2)
    val view = ByteView(padded, padded.size)
    val obu = view.leadingObus(0, padded.size).single()
    view.obuHeader(obu, 20) shouldBe byteArrayOf(0x0A, 0x94.toByte(), 0)
    view.obuHeader(obu, 200) shouldBe byteArrayOf(0x0A, 0xC8.toByte(), 1)
    val narrow = SequenceShape().obu()
    val narrowView = ByteView(narrow, narrow.size)
    narrowView.obuHeader(narrowView.leadingObus(0, narrow.size).single(), 200) shouldBe
      byteArrayOf(0x0A, 0xC8.toByte(), 1)
  }

  @Test
  fun `a size past four bytes of value or eight bytes of field is malformed`() {
    val tooLarge = byteArrayOf(0x0A, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x10)
    deferring { ByteView(tooLarge, tooLarge.size).leadingObus(0, tooLarge.size) }.shouldBeNull()
    val tooLong = byteArrayOf(0x0A) + ByteArray(8) { 0x80.toByte() } + byteArrayOf(0)
    deferring { ByteView(tooLong, tooLong.size).leadingObus(0, tooLong.size) }.shouldBeNull()
    val forbidden = byteArrayOf(0x8A.toByte(), 0)
    deferring { ByteView(forbidden, forbidden.size).leadingObus(0, forbidden.size) }.shouldBeNull()
  }

  private fun shouldStateOrRefuse(payload: ByteArray) {
    val rewritten = sequenceHeaderStating(payload, p3) ?: return
    sequenceHeaderLayout(rewritten)?.signal shouldBe p3
  }

  private companion object {
    const val FUZZ_ROUNDS = 150
    const val TEMPORAL_DELIMITER_OBU = 2
    const val METADATA_OBU = 5
    const val FRAME_OBU = 6
  }
}
