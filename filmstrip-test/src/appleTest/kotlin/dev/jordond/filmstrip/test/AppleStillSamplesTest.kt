package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.evaluator
import dev.jordond.filmstrip.color.rgbMatrix
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.test.AppleStillSamples.EIGHT_BIT_STEP
import dev.jordond.filmstrip.test.AppleStillSamples.MAX_CODE
import dev.jordond.filmstrip.test.AppleStillSamples.SAMPLE_MASK
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.fail

/**
 * Holds [AppleStillSamples.asWritten] to failing where Apple's decode of a HEIC handed over as written is unmeasured on
 * every platform. That is a full range `nclx` over a codestream that signals limited range. Holds every HEIC and AVIF
 * decode on an iPhone to failing as unmeasured too, asked for on any host.
 *
 * Also holds every HEIC still, alone, as a grid and handed over as written, and every AVIF still alone and as a grid,
 * to the bitmap ImageIO decodes it to. That bitmap is what the overlay raster reads a HEIC or an AVIF from, so each
 * still has to land on one of the decodes [AppleStillSamples] lists for it. On macOS a HEIC lands on any whole codes
 * within ImageIO's own drift of the unrounded reading, and the first of them is [ycbcrToRgb]'s. A miss names the OS
 * build it was read on, since the decodes were measured on one build of each platform. A still whose decode is
 * unmeasured here is one more miss, so one run reports every still.
 */
class AppleStillSamplesTest {
  private val limitedUnderFullNclx =
    HeicPicture(
      "HEIC nclx BT.709 full, VUI BT.709 limited",
      CicpTag(1, 13, 1, true),
      profile = null,
      ColorPictures.HEIC_NCLX_BT709_FULL.ycbcr,
      HevcVui.LimitedRangeBt709,
    )

  private val heics =
    ColorPictures.HEICS_WITH_SIGNAL_VARIANTS + AppleStillSamples.BT709_MATRIX_OTHER_PRIMARIES +
      ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA

  private val avifs = ColorPictures.AVIFS + ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA

  @Test
  fun failsAFullRangeNclxOverALimitedRangeCodestreamAsWritten() {
    val read = shortArrayOf(1, 2, 3, 4)

    val failure = assertFailsWith<AssertionError> { AppleStillSamples.asWritten(limitedUnderFullNclx, read) }

    failure.message.orEmpty() shouldContain "unmeasured"
    failure.message.orEmpty() shouldContain "handed over as written"
    failure.message.orEmpty() shouldContain "${read.toList()}"
  }

  @Test
  fun failsEveryHeicAndAvifDecodeOnAnIphone() {
    val read = shortArrayOf(5, 6, 7, 8)
    val asked =
      heics.flatMap { heic ->
        listOf<Pair<String, () -> Unit>>(
          "$heic" to { AppleStillSamples.of(heic, read, ApplePlatform.Iphone) },
          "$heic as written" to { AppleStillSamples.asWritten(heic, read, ApplePlatform.Iphone) },
        )
      } + avifs.map { avif -> "AVIF $avif" to { AppleStillSamples.of(avif, read, ApplePlatform.Iphone) } }

    asked.forEach { (name, decode) ->
      val failure = assertFailsWith<AppleStillSamples.UnmeasuredDecode>("$name was measured on an iPhone") { decode() }
      failure.message.orEmpty() shouldContain "unmeasured"
      failure.message.orEmpty() shouldContain "iOS hardware"
      failure.message.orEmpty() shouldContain "${read.toList()}"
    }
  }

  @Test
  fun failsAMatrixNoDecodeWasMeasuredUnder() {
    for (matrix in UNMEASURED_MATRICES) {
      val nclx = CicpTag(BT709_PRIMARIES, SRGB_TRANSFER, matrix, true)
      val avif = AvifPicture("AVIF nclx matrix $matrix", nclx, profile = null, ColorPictures.FLAT_YCBCR)
      val heic = HeicPicture("HEIC nclx matrix $matrix", nclx, profile = null, ColorPictures.FLAT_YCBCR)
      for (platform in listOf(ApplePlatform.MacOs, ApplePlatform.Simulator)) {
        val asked =
          listOf<Pair<String, () -> Unit>>(
            avif.name to { AppleStillSamples.of(avif, null, platform) },
            heic.name to { AppleStillSamples.of(heic, null, platform) },
            "${heic.name} as written" to { AppleStillSamples.asWritten(heic, null, platform) },
          )
        asked.forEach { (name, decode) ->
          val failure = assertFailsWith<IllegalArgumentException>("$name on ${platform.label}") { decode() }
          failure.message.orEmpty() shouldContain "unmeasured"
        }
      }
    }
  }

  @Test
  fun anUnmeasuredDecodeIsOneMissAmongTheOthers() {
    val misses =
      (heics + limitedUnderFullNclx).mapNotNull { heic ->
        AppleStillSamples.missOrUnmeasured {
          AppleStillSamples.asWritten(heic, null, ApplePlatform.MacOs)
          null
        }
      }

    withClue("the unmeasured decodes were $misses") { misses.size shouldBe 1 }
    misses.single() shouldContain limitedUnderFullNclx.name
  }

  @Test
  fun anAssertionOtherThanAnUnmeasuredDecodeIsNotTurnedIntoAMiss() {
    val failure =
      assertFailsWith<AssertionError> {
        AppleStillSamples.missOrUnmeasured { fail("a plain failure") }
      }

    withClue("the plain failure became $failure") { (failure is AppleStillSamples.UnmeasuredDecode) shouldBe false }
    failure.message shouldBe "a plain failure"
  }

  @Test
  fun aHeicsFirstDecodeOnMacOsIsItsRoundedReading() {
    heics.forEach { still ->
      val first = AppleStillSamples.of(still, null, ApplePlatform.MacOs).first()
      withClue("$still") { first shouldBe AppleStillSamples.rounded(still, asWritten = false) }

      // A full range nclx over a limited range codestream has no decode as written, which the unmeasured case holds.
      val written =
        try {
          AppleStillSamples.asWritten(still, null, ApplePlatform.MacOs).first()
        } catch (_: AppleStillSamples.UnmeasuredDecode) {
          return@forEach
        }
      withClue("$still as written") { written shouldBe AppleStillSamples.rounded(still, asWritten = true) }
    }
  }

  @Test
  fun aHeicsRoundedReadingIsTheWholeCodesYcbcrToRgbGives() {
    heics.forEach { still ->
      val nclx = still.nclx ?: return@forEach
      val (y, cb, cr) = still.ycbcr
      val expected = ycbcrToRgb(y, cb, cr, nclx.matrix, nclx.primaries, nclx.transfer, nclx.fullRange).toList()

      val rounded = AppleStillSamples.rounded(still, asWritten = false)

      withClue("$still") { rounded.take(RGB).codes() shouldBe expected }
    }
  }

  // Mid grey luma under the furthest blue and red differences reads below black on red and above white on blue.
  @Test
  fun aRoundedReadingOfAnOutOfGamutDecodeStaysInsideTheCodeRange() {
    val ycbcr = listOf(MID_CODE, MAX_CODE, 0)
    val (y, cb, cr) = ycbcr
    val weights = ycbcrReading(BT709_MATRIX, BT709_PRIMARIES, SRGB_TRANSFER).shouldBeInstanceOf<YcbcrReading.KrKb>()
    val color = AppleStillSamples.StillColor(BT709_MATRIX, BT709_PRIMARIES, SRGB_TRANSFER, weights)
    val unclamped =
      checkNotNull(weights.rgbMatrix(EIGHT_BITS, fullRange = true)).rgb(y.toDouble(), cb.toDouble(), cr.toDouble())
    withClue("${unclamped.toList()} is inside the gamut") {
      (unclamped.any { it < 0.0 } && unclamped.any { it > 1.0 }) shouldBe true
    }

    val decode = AppleStillSamples.HeicDecode(ycbcr, color, full = true)
    val rounded = AppleStillSamples.roundedReading(decode).take(RGB).codes()

    rounded shouldBe ycbcrToRgb(y, cb, cr, BT709_MATRIX, BT709_PRIMARIES, SRGB_TRANSFER, true).toList()
    withClue("red and blue of $rounded") { listOf(rounded.first(), rounded.last()) shouldBe listOf(0, MAX_CODE) }
  }

  // Handed over as written the edge chroma still reads past black on red and green and past white on blue.
  @Test
  fun theAsWrittenEdgeChromaHeicsDecodesOnMacOsStayInsideTheCodeRange() {
    val still = ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA
    val box =
      listOf(0, 1).flatMap { red -> listOf(0, 1).flatMap { green -> listOf(254, 255).map { listOf(red, green, it) } } }

    val decodes = AppleStillSamples.asWritten(still, null, ApplePlatform.MacOs).map { it.take(RGB).codes() }

    withClue("$still as written") { decodes.toSet() shouldBe box.toSet() }
    withClue("$still as written repeats a decode") { decodes.size shouldBe box.size }
  }

  // The edge chroma still's blue reads between whole codes, so its decodes reach past rounding on both sides.
  @Test
  fun aHeicOnMacOsDecodesToEveryWholeCodeWithinImageIoDriftOfTheUnroundedReading() {
    val still = ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA
    val nclx = checkNotNull(still.nclx)
    val (y, cb, cr) = still.ycbcr
    val evaluator = ycbcrReading(nclx.matrix, nclx.primaries, nclx.transfer).evaluator(EIGHT_BITS, nclx.fullRange)
    val reading = evaluator.rgb(y, cb, cr).map { it * MAX_CODE }
    val reachable =
      reading.map { code ->
        (0..MAX_CODE).filter { abs(it - code) <= AppleStillSamples.WHOLE_CODE_DRIFT }.toSet()
      }

    val decodes = AppleStillSamples.of(still, null, ApplePlatform.MacOs).map { it.take(RGB).codes() }

    withClue("$still repeats a decode") { decodes.toSet().size shouldBe decodes.size }
    withClue("$still decodes $decodes") {
      decodes.size shouldBe reachable.fold(1) { count, codes -> count * codes.size }
    }
    reachable.forEachIndexed { channel, codes ->
      withClue("channel $channel of $still read at ${reading[channel]}") {
        decodes.map { it[channel] }.toSet() shouldBe codes
      }
    }
    withClue("no channel of $still reaches past rounding, read at $reading") {
      reachable.any { it.size > 2 } shouldBe true
    }
  }

  @Test
  fun aReadInsideTheDriftBoxNarrowsAHeicsDecodesOnMacOsToThatRead() {
    val still = ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA
    val box = AppleStillSamples.of(still, null, ApplePlatform.MacOs)
    withClue("the drift box of $still holds one decode, so there is nothing to narrow") { (box.size > 1) shouldBe true }
    val read = box.last()

    val decodes = AppleStillSamples.of(still, read, ApplePlatform.MacOs)

    withClue("$still read at ${read.toList()}") { decodes.map { it.toList() } shouldBe listOf(read.toList()) }
  }

  @Test
  fun aReadOutsideTheDriftBoxKeepsEveryOneOfAHeicsDecodesOnMacOs() {
    val still = ColorPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA
    val box = AppleStillSamples.of(still, null, ApplePlatform.MacOs).map { it.toList() }
    val read = AppleStillSamples.wholeCodeTexel(intArrayOf(MID_CODE, MID_CODE, MID_CODE))

    val decodes = AppleStillSamples.of(still, read, ApplePlatform.MacOs).map { it.toList() }

    withClue("${read.toList()} sits inside the drift box of $still") { (read.toList() !in box) shouldBe true }
    withClue("$still read at ${read.toList()}") { decodes shouldBe box }
  }

  @Test
  fun everyHeicDecodesToTheWholeCodesImageIoGives() {
    val misses =
      heics.flatMap { heic ->
        listOfNotNull(
          imageIoMiss("$heic", heifWithExplicitCodestreamColor(heic.bytes()).handed) {
            AppleStillSamples.of(heic, it)
          },
          imageIoMiss("$heic grid", heifWithExplicitCodestreamColor(heic.gridBytes()).handed) {
            AppleStillSamples.of(heic, it)
          },
          imageIoMiss("$heic as written", heic.bytes()) { AppleStillSamples.asWritten(heic, it) },
        )
      }

    withClue("Model misses on ${osBuild()}:\n" + misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun aHeicUnderAnUnreadMatrixDecodesThroughItsLandingToItsModel() {
    val still = AppleStillSamples.UNREAD_MATRIX
    val nclx = checkNotNull(still.nclx)
    val landed = checkNotNull(AppleStillSamples.decoderMatrix(nclx, unsubsampled = false, EIGHT_BITS))
    withClue("$still lands exactly through matrix ${landed.matrix}") { landed.exact shouldBe false }

    val miss =
      imageIoMiss("$still", landedBytes(still.bytes(), nclx, landed.matrix)) { AppleStillSamples.landed(still, it) }

    withClue("Model miss on ${osBuild()}") { miss shouldBe null }
  }

  @Test
  fun everyAvifDecodesWithinRoundingOfItsModel() {
    val misses =
      avifs.flatMap { avif ->
        listOfNotNull(
          imageIoMiss("AVIF $avif", heifWithExplicitCodestreamColor(avif.bytes()).handed) {
            AppleStillSamples.of(avif, it)
          },
          imageIoMiss(
            "AVIF $avif grid",
            heifWithExplicitCodestreamColor(avif.gridBytes()).handed,
          ) { AppleStillSamples.of(avif, it) },
        )
      }

    withClue("Model misses on ${osBuild()}:\n" + misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  private fun List<Short>.codes(): List<Int> = map { (it.toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP }

  private companion object {
    const val RGB = 3
    const val BT709_PRIMARIES = 1
    const val BT709_MATRIX = 1
    const val SRGB_TRANSFER = 13

    // Identity, YCgCo, BT.2020 constant luminance and the two matrices derived from chromaticities, 12 on these
    // primaries reading as BT.709 does.
    val UNMEASURED_MATRICES = listOf(0, 8, 10, 12, 13)
    const val EIGHT_BITS = 8
    const val MID_CODE = 128
  }
}
