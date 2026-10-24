package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.nearestReadable
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeicPicture
import dev.jordond.filmstrip.test.HeifStills
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android's HEIC decoder handed every HEIC still the way an overlay hands it over, and handed the file as it stands.
 *
 * Read through [decode], each HEIC with an `nclx` lands on the RGB the matrix [decoderLanding] gives for this API
 * level makes of its YCbCr samples at the box's range, and reports the limits it names. So do its two-by-two grid, a
 * 1280 by 768 still and a still recording each matrix, one core refuses or whose nearest matrix lands it past the bound
 * refused instead, such as YCgCo and the identity matrix. A HEIC without an `nclx` lands as [landingHere] gives. Each
 * band of API levels has a test of its own pinning which of BT.709 and BT.2020 it reads. Handed over with only its
 * colour descriptions hidden, each still lands as its codestream signals, BT.601 at limited range when it is silent,
 * and a codestream stating each pairing of matrix and primaries lands as this API level reads it. Those halves are
 * Android's own decoder, pinned so a platform that starts reading `nclx` or another matrix, or changes its default,
 * shows here. The codestream [forAndroidDecoder] hands the decoder lands on that same reading, so both halves agree on
 * one decode per codestream. Each failure lists every matrix and range reading, so the one the decoder took can be
 * read off.
 *
 * Skipped by name on an Android whose HEIC decoder hands back no frame for the test stills.
 */
class HeicDecoderMatrixTest {
  @Test
  fun everyHeicDecodesWhereItsDecoderLandsIt() {
    assumeHeicDecodes()
    val misses = pictures().mapNotNull { arrival("$it", it.bytes(), it) }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun everyHeicGridDecodesWhereItsDecoderLandsIt() {
    assumeHeicDecodes()
    val grids = pictures().filter { it.nclx != null }
    val first = grids.first().gridBytes()
    assumeTrue(HEIC_GRID_UNDECODED, BitmapFactory.decodeByteArray(first, 0, first.size) != null)
    val misses = grids.mapNotNull { arrival("$it grid", it.gridBytes(), it) }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun aHeicWhoseCodestreamDiffersFromItsNclxOnlyInPrimariesDecodesWhereItsDecoderLandsIt() {
    assumeHeicDecodes()
    val picture = ColourPictures.HEIC_NCLX_BT709_VUI_DISPLAY_P3

    assertNull(arrival("$picture", picture.bytes(), picture))
  }

  @Test
  fun everyMatrixLandsWhereThisApiLevelLandsItAndNamesItsLimit() {
    assumeHeicDecodes()
    val misses =
      MATRICES.flatMap { matrix ->
        listOf(true, false).mapNotNull { full ->
          val coding = Coding(matrix, full)
          landing(coding, recording(coding).ownLanding())
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun belowApi33ABt709OrBt2020HeicLandsThroughBt601AndSaysSo() {
    assumeHeicDecodes()
    assumeTrue("Android's HEIC decoder reads BT.709 and BT.2020 from API 33", Build.VERSION.SDK_INT < WIDE_MATRIX_API)
    val misses =
      listOf(true, false).flatMap { full ->
        listOf(BT709, BT2020).mapNotNull { matrix -> landedThrough(Coding(matrix, full), BT601) }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun onApi33ABt709HeicAtLimitedRangeLandsThroughTheNearestMatrixAndSaysSo() {
    assumeHeicDecodes()
    assumeTrue("This test pins API 33's HEIC decoder", Build.VERSION.SDK_INT == WIDE_MATRIX_API)
    val limited = Coding(BT709, false)
    val readable = readableMatrices(false, WIDE_MATRIX_API, avif = false, tenBit = false)
    val nearest = checkNotNull(pictureYcbcrCoding(recording(limited))?.reading?.nearestReadable(readable)).matrix
    val misses =
      listOfNotNull(
        landedThrough(limited, nearest),
        exact(Coding(BT709, true)),
        exact(Coding(BT2020, true)),
        exact(Coding(BT2020, false)),
      )

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun fromApi34EveryBt709AndBt2020HeicLandsAsItsNclxGives() {
    assumeHeicDecodes()
    assumeTrue("Android's HEIC decoder reads every BT.709 HEIC from API 34", Build.VERSION.SDK_INT > WIDE_MATRIX_API)
    val misses =
      listOf(BT709, BT2020).flatMap { matrix ->
        listOf(true, false).mapNotNull { full -> exact(Coding(matrix, full)) }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun anHdHeicLandsAsASmallOneDoes() {
    assumeHeicDecodes()
    val first = hdRecording(Coding(BT709, true))
    assumeTrue(HEIC_HD_UNDECODED, BitmapFactory.decodeByteArray(first, 0, first.size) != null)
    val misses =
      listOf(BT709, UNSPECIFIED, BT601, SMPTE_240, BT2020).flatMap { matrix ->
        listOf(true, false).mapNotNull { full ->
          val coding = Coding(matrix, full)
          val landsAs = Coding(checkNotNull(hdRecording(coding).ownLanding()).matrix, full)
          miss("HD HEIC nclx $coding", decoded(hdRecording(coding)), YCBCR, landsAs)
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun everyHeicHandedOverAsItStandsDecodesAsItsCodestreamSignals() {
    assumeHeicDecodes()
    val misses =
      pictures().mapNotNull { picture ->
        val hidden = picture.bytes().withColourDescriptionsHidden()
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(hidden, 0, hidden.size, straight())) { "$picture" }
        codestreamMiss("$picture as it stands", bitmap, picture)
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun everyCodestreamPairingLandsAsThisApiLevelReadsIt() {
    assumeHeicDecodes()
    val misses =
      listOf(BT709, UNSPECIFIED, BT601, SMPTE_240, BT2020).flatMap { matrix ->
        listOf(BT709, UNSPECIFIED, BT601, BT2020).flatMap { primaries ->
          listOf(true, false).mapNotNull { full ->
            val file = heifWithExplicitCodestreamColour(recording(Coding(matrix, full), primaries, UNSPECIFIED))
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(file, 0, file.size, straight()))
            val what = "codestream primaries $primaries, ${Coding(matrix, full)}"
            codestreamMiss(what, bitmap, YCBCR, primaries, matrix, full)
          }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  private fun pictures(): List<HeicPicture> =
    ColourPictures.HEICS_WITH_SIGNAL_VARIANTS + ColourPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA

  private fun assumeHeicDecodes() {
    assumeTrue("Android decodes HEIC from API 28", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    val first = ColourPictures.HEICS.first().bytes()
    assumeTrue(HEIC_FIXTURE_UNDECODED, BitmapFactory.decodeByteArray(first, 0, first.size) != null)
  }

  private fun decoded(file: ByteArray): Bitmap =
    checkNotNull(ImageSource.Bytes(file).decode(context = null)) { "the overlay decode handed back nothing" }.bitmap

  // How [file] read through decode misses where [picture]'s decoder lands it, with the limit it names, or null. A HEIC
  // without an nclx lands as the codestream it reaches the decoder with states.
  private fun arrival(
    what: String,
    file: ByteArray,
    picture: HeicPicture,
  ): String? {
    val decoded = checkNotNull(ImageSource.Bytes(file).decode(context = null)) { "$what handed back nothing" }
    val nclx = picture.nclx
    val landing =
      if (nclx == null) {
        checkNotNull(file.landingHere()) { "$what has no landing" }
      } else {
        checkNotNull(file.ownLanding(nclx.fullRange)) { "$what is refused" }
      }
    if (decoded.limits != landing.limits) return "$what reported ${decoded.limits}, not ${landing.limits}"
    val lands = Coding(landing.matrix, landing.fullRange)
    return handedMiss(what, file, lands) ?: miss(what, decoded.bitmap, picture.ycbcr, lands)
  }

  // How a still recording [coding] misses landing as [landing] says, or null. One core refuses, which has no landing,
  // or one landing past the bound misses unless it is refused for it.
  private fun landing(
    coding: Coding,
    landing: DecoderLanding?,
  ): String? {
    val file = recording(coding)
    if (landing.refused) return file.refusalMiss("HEIC nclx $coding", landing)
    checkNotNull(landing)
    val picture = checkNotNull(ImageSource.Bytes(file).decode(context = null)) { "HEIC nclx $coding" }
    if (picture.limits != landing.limits) return "HEIC nclx $coding reported ${picture.limits}, not ${landing.limits}"
    val lands = Coding(landing.matrix, coding.fullRange)
    return handedMiss("HEIC nclx $coding", file, lands) ?: miss("HEIC nclx $coding", picture.bitmap, YCBCR, lands)
  }

  // How a still recording [coding] misses landing through its own matrix with no limit, or null.
  private fun exact(coding: Coding): String? =
    landing(coding, DecoderLanding(coding.matrix, coding.fullRange, emptyList()))

  // How a still recording [coding] misses landing through [matrix] within the bound, naming the landing, or null.
  private fun landedThrough(
    coding: Coding,
    matrix: Int,
  ): String? {
    val landing = checkNotNull(recording(coding).ownLanding())
    val limit = landing.limits.singleOrNull() as? DecoderLimit.LandedThrough
    val named = limit != null && limit.nearest.withinBound && landing.matrix == matrix
    return "HEIC nclx $coding lands through ${landing.matrix} with ${landing.limits}".takeUnless { named }
      ?: landing(coding, landing)
  }

  // How the codestream [forAndroidDecoder] hands the decoder for [file] misses landing on [lands] by this API level's
  // reading of each pairing in codestreamLanding, or null. That reading names every BT.601 matrix as 6.
  private fun handedMiss(
    what: String,
    file: ByteArray,
    lands: Coding,
  ): String? {
    val handed = file.forAndroidDecoder(Build.VERSION.SDK_INT).bytes
    val signal = pictureYcbcrCoding(handed)?.codestream ?: return "$what reaches the decoder with no codestream read"
    val reading = codestreamLanding(signal.primaries, signal.matrix, signal.fullRange ?: false)
    val want = if (lands.matrix in BT601_MATRICES) Coding(BT601, lands.fullRange) else lands
    return "$what reaches the decoder as $signal, read as $reading where it lands on $lands".takeIf { reading != want }
  }

  // A HEIC still whose codestream signals nothing, its nclx recording [primaries], [transfer] and [coding].
  private fun recording(
    coding: Coding,
    primaries: Int = BT709,
    transfer: Int = SRGB_TRANSFER,
  ): ByteArray {
    val file = ColourPictures.HEIC_NCLX_BT709_FULL.bytes()
    val nclx = String(file, Charsets.ISO_8859_1).indexOf("nclx") + 4
    val range = if (coding.fullRange) FULL_RANGE else 0
    val fields = byteArrayOf(0, primaries.toByte(), 0, transfer.toByte(), 0, coding.matrix.toByte(), range)
    return file.also { fields.copyInto(it, nclx) }
  }

  // A 1280 by 768 HEIC still painted the flat colour of every HEIC here, its codestream silent and its nclx recording
  // BT.709 primaries, the sRGB transfer and [coding].
  private fun hdRecording(coding: Coding): ByteArray =
    HeifStills.still(
      brand = "heic",
      itemType = "hvc1",
      configuration = hvcc(HD_HVCC.hex()),
      codestream = HD_SLICE.hex(),
      size = HD_SIZE,
      nclx = CicpTag(BT709, SRGB_TRANSFER, coding.matrix, coding.fullRange),
    )

  // The middle sample sits well inside the flat colour, away from any edge the decoder filters. Android's HEIC decoder
  // lands on BT.601, BT.709 or BT.2020, none of which reads the primaries or transfer beside it.
  private fun miss(
    what: String,
    bitmap: Bitmap,
    ycbcr: List<Int>,
    coding: Coding,
  ): String? {
    val read = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    val want = ycbcr.argbThrough(coding.matrix, BT709, SRGB_TRANSFER, coding.fullRange)
    val off = channelsOff(read, want)
    if (off <= CODED_TOLERANCE) return null
    return "$what read ${read.hex()} where $coding gives ${want.hex()}, $off off. Readings: ${readings(ycbcr)}"
  }

  // How a decode of a codestream stating [primaries], [matrix] and [fullRange], each null when the codestream is
  // silent, misses where this API level lands it, or null. Where it lands on no matrix, a read within a tolerance of
  // any matrix reading misses.
  private fun codestreamMiss(
    what: String,
    bitmap: Bitmap,
    ycbcr: List<Int>,
    primaries: Int?,
    matrix: Int?,
    fullRange: Boolean,
  ): String? {
    codestreamLanding(primaries, matrix, fullRange)?.let { return miss(what, bitmap, ycbcr, it) }
    val read = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    val near =
      READINGS.firstOrNull { (m, full) ->
        channelsOff(read, ycbcr.argbThrough(m, BT709, SRGB_TRANSFER, full)) <= CODED_TOLERANCE
      }
    return near?.let { (m, full) ->
      "$what read ${read.hex()}, on ${Coding(m, full)}, where it should land on no matrix. Readings: ${readings(ycbcr)}"
    }
  }

  private fun codestreamMiss(
    what: String,
    bitmap: Bitmap,
    picture: HeicPicture,
  ): String? {
    val colour = picture.vui.colour
    return codestreamMiss(what, bitmap, picture.ycbcr, colour?.primaries, colour?.matrix, picture.vui.fullRange)
  }

  // Where Android's HEIC decoder on this API level lands a codestream stating [primaries], [matrix] and [fullRange],
  // or null where it lands on no matrix at all. It reads BT.709 only beside BT.709 primaries, from API 33 at full range
  // and from API 34 at limited range, and below API 33 at neither, landing a few codes off every matrix. It reads
  // BT.2020 only beside BT.2020 primaries and from API 33, and BT.601 for anything else. Below API 34 it reads full
  // range only beside a pair it reads or a BT.601 matrix beside BT.601 primaries, and limited range otherwise.
  private fun codestreamLanding(
    primaries: Int?,
    matrix: Int?,
    fullRange: Boolean,
  ): Coding? {
    val sdk = Build.VERSION.SDK_INT
    val paired =
      when {
        matrix == BT709 && primaries == BT709 -> BT709
        matrix == BT2020 && primaries == BT2020 && sdk >= WIDE_MATRIX_API -> BT2020
        else -> BT601
      }
    if (paired == BT709 && (sdk < WIDE_MATRIX_API || (sdk == WIDE_MATRIX_API && !fullRange))) return null
    val bt601Pair = matrix in BT601_MATRICES && primaries in BT601_PRIMARIES
    val readsFullRange = sdk > WIDE_MATRIX_API || paired != BT601 || bt601Pair
    return Coding(paired, fullRange && readsFullRange)
  }

  private fun readings(ycbcr: List<Int>): String =
    READINGS.joinToString { (matrix, full) ->
      "${Coding(matrix, full)} ${ycbcr.argbThrough(matrix, BT709, SRGB_TRANSFER, full).hex()}"
    }

  private fun straight() = BitmapFactory.Options().apply { inPremultiplied = false }

  private fun hvcc(payload: ByteArray): ByteArray {
    val size = BOX_HEADER + payload.size
    val header = byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte())
    return header + "hvcC".encodeToByteArray() + payload
  }

  private fun String.hex(): ByteArray = chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()

  private data class Coding(
    val matrix: Int,
    val fullRange: Boolean,
  ) {
    override fun toString(): String = "matrix $matrix ${if (fullRange) "full" else "limited"}"
  }

  private companion object {
    const val BT709 = 1
    const val UNSPECIFIED = 2
    const val BT601 = 6
    const val SMPTE_240 = 7
    const val BT2020 = 9

    // Every matrix a still records here. 4, FCC, reads exactly as 6 does beside any primaries, and 10, BT.2020 constant
    // luminance, reads as BT.601 even beside BT.2020 primaries.
    val MATRICES = listOf(0, BT709, UNSPECIFIED, 4, 5, BT601, SMPTE_240, 8, BT2020, 10, 12, 14)
    val BT601_MATRICES = listOf(UNSPECIFIED, 5, BT601)
    val BT601_PRIMARIES = listOf(5, BT601)
    val READINGS = listOf(BT601, BT709, BT2020).flatMap { matrix -> listOf(matrix to true, matrix to false) }
    const val WIDE_MATRIX_API = 33

    const val SRGB_TRANSFER = 13
    const val FULL_RANGE = 0x80.toByte()
    const val CODED_TOLERANCE = 2
    const val HEX = 16
    const val BOX_HEADER = 8
    val YCBCR: List<Int> = ColourPictures.HEIC_NCLX_BT709_FULL.ycbcr
    const val HEIC_GRID_UNDECODED = "Android's HEIC decoder hands back no frame for a grid of the ColourPictures stills"
    const val HEIC_HD_UNDECODED = "Android's HEIC decoder hands back no frame for the 1280 by 768 HEIC still"
    val HD_SIZE = Size(1280, 768)

    // From x265 4.3 and ffmpeg 9.0.2 on macOS, from a raw frame of luma 128, blue difference 96 and red difference 176
    // at every sample:
    //   python3 -c "open('hd.yuv','wb').write(bytes([128])*983040+bytes([96])*245760+bytes([176])*245760)"
    //   x265 --input hd.yuv --input-res 1280x768 --fps 1 --frames 1 --profile mainstillpicture --qp 4 --no-info \
    //     -o hd.hevc
    //   ffmpeg -i hd.hevc -c copy -tag:v hvc1 hd.mp4
    // The configuration is the mp4's hvcC payload and the slice is the one coded slice of hd.hevc behind its length.
    // Decoding it back with ffmpeg gives 128, 96 and 176 at every sample.
    const val HD_HVCC =
      "0103700000009000000000005df000fcfdf8f800000f03a00001001840010c01ffff03700000030090000003000003005dba0240" +
        "a10001002742010103700000030090000003000003005da002808030165ba924cae01000003e8000003e8080" +
        "a2000100064401c1718312"
    const val HD_SLICE =
      "000002992801af06718794edbb466c993262c58c61867ffff023ff21f9476f01d8453c86b0209d601f1120800000030000030000" +
        "03000003000003000003000003000003000003000003000003000003000003000003000003000003008b801c86ac55d000000300" +
        "0003000003000003000003000003000003000003000003000003000003000003000003000003000003000003000003000011f000" +
        "0003025c000003000003000003000003000003000003000003000003000003000003000003000003000003000003000003000003" +
        "00015900001c80000003000003000003000003000003000003000003000003000003000003000003000003000003000003000003" +
        "00005440000075800000030000030000030000030000030000030000030000030000030000030000030000030000030000030000" +
        "03004540000115000003000003000003000003000003000003000003000003000003000003000003000003000003000003000003" +
        "00060c00018e00000300000300000300000300000300000300000300000300000300000300000300000300000300000300000300" +
        "2d600002360000030000030000030000030000030000030000030000030000030000030000030000030000030000030000030097" +
        "80000274000003000003000003000003000003000003000003000003000003000003000003000003000003000003000003012300" +
        "02f0000003000003000003000003000003000003000003000003000003000003000003000003000003000003000003015f000310" +
        "000003000003000003000003000003000003000003000003000003000003000003000003000003000003000003020a0003340000" +
        "030000030000030000030000030000030000030000030000030000030000030000030000030000030000030222"
  }
}

/**
 * Why a HEIC device test skips on an Android whose decoder hands back no frame for the [ColourPictures] HEIC stills.
 */
internal const val HEIC_FIXTURE_UNDECODED =
  "Android's HEIC decoder hands back no frame for the 64 by 64 ColourPictures HEIC stills"
