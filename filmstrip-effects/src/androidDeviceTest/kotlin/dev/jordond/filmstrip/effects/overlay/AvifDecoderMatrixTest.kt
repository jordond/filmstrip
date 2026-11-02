package dev.jordond.filmstrip.effects.overlay

import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import dev.jordond.filmstrip.internal.pictureYcbcrCoding
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.RestatedColour
import dev.jordond.filmstrip.media.RestatedHeif
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.heifWithRestatedColour
import dev.jordond.filmstrip.media.nearestReadable
import dev.jordond.filmstrip.test.AvifColourPictures
import dev.jordond.filmstrip.test.AvifPicture
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.landedRgb
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android's AVIF decoder handed every AVIF still the way an overlay hands it over.
 *
 * Read through [read], each AVIF lands on the RGB the matrix and range [decoderLanding] gives for this API level make
 * of its YCbCr samples, and reports the limits it names, and an AVIF without an `nclx` lands as
 * [YcbcrCoding.decoderLanding] gives for the codestream it reaches the decoder with. So does a still recording each
 * matrix at each range, painted flat and painted with its blue difference at the edge of its range. One core refuses,
 * such as one naming the identity matrix on subsampled samples, is refused with core's reason, and one whose nearest
 * matrix lands it past the bound, such as one naming the identity matrix on 4:4:4 samples, is refused naming core's
 * figures. Each band of API levels has a test of its own pinning what it lands short of, and one pins that only API 36
 * lands through the matrix the `nclx` names rather than the codestream's. Another pins that an AVIF whose codestream
 * alone names the PQ transfer lands on its stored codes. That is Android's own decoder, pinned so a platform that
 * starts reading another matrix, range or transfer shows here.
 *
 * Skipped by name before API 31, which has no AVIF decoder.
 */
class AvifDecoderMatrixTest {
  @Test
  fun everyAvifDecodesWhereItsDecoderLandsIt() {
    assumeAvifDecodes()
    val misses = STILLS.mapNotNull { still -> arrival("$still", still.bytes(), still.ycbcr, landingOf(still)) }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun everyMatrixLandsWhereThisApiLevelLandsItAndNamesItsLimit() {
    assumeAvifDecodes()
    val misses =
      PAINTED.flatMap { still ->
        MATRICES.flatMap { matrix ->
          // The edge still's blue difference lies past the top of the limited range, so it clips there on every matrix.
          listOf(true, false).filter { it || still != EDGE }.mapNotNull { full ->
            val file = recording(still, matrix, full)
            arrival("$still as matrix $matrix ${range(full)}", file, still.ycbcr, file.ownLanding())
          }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun beforeApi33EveryAvifLandsThroughBt601AtLimitedRangeAndSaysSo() {
    assumeAvifDecodes()
    assumeTrue("Android's AVIF decoder reads the codestream from API 33", Build.VERSION.SDK_INT < WIDE_MATRIX_API)
    val stills =
      listOf(
        Triple("BT.709 full", recording(FLAT, BT709, true), 2),
        Triple("BT.2020 limited", recording(FLAT, BT2020, false), 1),
        Triple("BT.601 full", recording(FLAT, BT601, true), 1),
        Triple("BT.601 limited", recording(FLAT, BT601, false), 0),
        Triple("no nclx", UNDESCRIBED.bytes(), 1),
      )
    val misses =
      stills.mapNotNull { (what, file, limits) ->
        val landing = checkNotNull(file.ownLanding()) { "$what has no landing" }
        val ycbcr = if (what == "no nclx") UNDESCRIBED.ycbcr else FLAT.ycbcr
        "$what lands through ${landing.matrix} ${range(landing.fullRange)} with ${landing.limits}"
          .takeIf { landing.matrix != BT601 || landing.fullRange || landing.limits.size != limits }
          ?: arrival(what, file, ycbcr, landing)
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun onApi33ABt709AvifAtLimitedRangeLandsThroughTheNearestMatrixAndOneWithoutAnNclxAsItsCodestreamStates() {
    assumeAvifDecodes()
    assumeTrue("This test pins API 33's AVIF decoder", Build.VERSION.SDK_INT == WIDE_MATRIX_API)
    val limited = recording(FLAT, BT709, false)
    val readable = readableMatrices(false, WIDE_MATRIX_API, avif = true, tenBit = false)
    val nearest = checkNotNull(reading(limited).nearestReadable(readable))
    val misses =
      listOfNotNull(
        arrival(
          "BT.709 limited",
          limited,
          FLAT.ycbcr,
          DecoderLanding(nearest.matrix, false, listOf(DecoderLimit.LandedThrough(avif = true, nearest))),
        ),
        arrival("BT.709 full", recording(FLAT, BT709, true), FLAT.ycbcr, DecoderLanding(BT709, true, emptyList())),
        arrival(
          "no nclx",
          UNDESCRIBED.bytes(),
          UNDESCRIBED.ycbcr,
          DecoderLanding(BT601, undescribedRange(), emptyList()),
        ),
      )

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun fromApi33ToApi35AnFccOrSmpte240AvifLandsThroughTheNearestMatrixAndSaysSo() {
    assumeAvifDecodes()
    val sdk = Build.VERSION.SDK_INT
    assumeTrue("Android's AVIF decoder reads FCC and SMPTE 240M from API 36", sdk in WIDE_MATRIX_API until MORE_API)
    val misses =
      listOf(FCC, SMPTE_240).flatMap { matrix ->
        listOf(true, false).mapNotNull { full ->
          val file = recording(FLAT, matrix, full)
          val landing = checkNotNull(file.ownLanding())
          val limit = landing.limits.singleOrNull() as? DecoderLimit.LandedThrough
          val short = limit == null || !limit.nearest.withinBound
          "matrix $matrix ${range(full)} reports ${landing.limits}".takeIf { short }
            ?: arrival("matrix $matrix ${range(full)}", file, FLAT.ycbcr, landing)
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun fromApi34EveryBt709AvifLandsAsItsNclxGives() {
    assumeAvifDecodes()
    assumeTrue("Android's AVIF decoder reads every BT.709 AVIF from API 34", Build.VERSION.SDK_INT > WIDE_MATRIX_API)
    val misses =
      listOf(true, false).mapNotNull { full ->
        val landing = DecoderLanding(BT709, full, emptyList())
        arrival("BT.709 ${range(full)}", recording(FLAT, BT709, full), FLAT.ycbcr, landing)
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun fromApi36AnFccSmpte240OrFullRangeYcgcoAvifLandsAsItsNclxGives() {
    assumeAvifDecodes()
    assumeTrue("Android's AVIF decoder reads FCC and SMPTE 240M from API 36", Build.VERSION.SDK_INT >= MORE_API)
    val readings = listOf(FCC to true, FCC to false, SMPTE_240 to true, SMPTE_240 to false, YCGCO to true)
    val misses =
      readings.mapNotNull { (matrix, full) ->
        val landing = DecoderLanding(matrix, full, emptyList())
        arrival("matrix $matrix ${range(full)}", recording(FLAT, matrix, full), FLAT.ycbcr, landing)
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // The codestream states BT.601 beside its primaries while the nclx names another matrix, a disagreement
  // forAndroidDecoder never leaves in an AVIF it restates.
  @Test
  fun fromApi33AnAvifLandsThroughItsNclxMatrixOnlyOnApi36() {
    val sdk = Build.VERSION.SDK_INT
    assumeTrue("Android's AVIF decoder reads the codestream from API 33", sdk >= WIDE_MATRIX_API)
    val misses =
      listOf(true, false).flatMap { full ->
        val bt709 = restatedAsBt601(recording(FLAT, BT709, full))
        val bt2020Constant = restatedAsBt601(recording(FLAT, BT2020_CONSTANT, full))
        val viaNclx = sdk >= MORE_API
        val bt601 = (BT601 to full).takeUnless { viaNclx }
        listOfNotNull(
          stillMiss("nclx BT.709, codestream BT.601 ${range(full)}", bt709, (if (viaNclx) BT709 else BT601) to full),
          stillMiss("nclx matrix 10, codestream BT.601 ${range(full)}", bt2020Constant, bt601),
        )
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun everyPictureWithoutAnNclxLandsAsItsCodestreamSaysUnlessItsLimitIsNamed() {
    assumeAvifDecodes()
    val avifs =
      AvifColourPictures.CODESTREAM_COLOUR_AVIFS
        .filter { it.nclx == null }
        .map { Triple(it.name, it.bytes(), it.ycbcr) }
    val heics =
      ColourPictures.CODESTREAM_COLOUR_HEICS.filter { it.nclx == null }.map { Triple(it.name, it.bytes(), it.ycbcr) }
    val misses =
      (avifs + heics).mapNotNull { (still, bytes, ycbcr) ->
        val landing = checkNotNull(bytes.landingHere()) { "$still has no landing" }
        val stated = landedRgb(bytes, ycbcr).fold(0xFF) { argb, code -> (argb shl 8) or code }
        val lands = ycbcr.argbThrough(landing.matrix, landing.fullRange, bytes)
        arrival(still, bytes, ycbcr, landing)
          ?: "$still lands on ${lands.hex()} where core says ${stated.hex()}".takeIf {
            landing.limits.isEmpty() && channelsOff(lands, stated) > CODED_TOLERANCE
          }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // A codestream naming the PQ transfer and BT.2020 primaries, with no nclx to override it, reaches the decoder still
  // naming PQ and lands on the codes its samples store, as on every other backend, with nothing converted out of PQ.
  @Test
  fun aPqCodestreamWithoutAnNclxLandsOnItsStoredCodes() {
    assumeAvifDecodes()
    val still = AvifColourPictures.AVIF_CODESTREAM_PQ_LIMITED
    val file = still.bytes()
    val landing = checkNotNull(file.landingHere()) { "$still has no landing" }
    val handed = pictureYcbcrCoding(file.forAndroidDecoder(Build.VERSION.SDK_INT).bytes)?.codestream
    assertEquals(PQ_TRANSFER, handed?.transfer, "$still reaches the decoder as $handed")
    val picture = ImageSource.Bytes(file).read(context = null)
    val decoded = assertIs<DecodedPicture>(picture, "$still came to $picture")
    val space = decoded.bitmap.colorSpace

    assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), space, "$still decoded into $space")
    assertNull(arrival("$still", file, still.ycbcr, landing))
  }

  // Android's AVIF decoder reads no identity matrix, and the nearest matrix it reads lands a 4:4:4 identity still past
  // the bound, so it is refused naming core's figures before the decoder sees it.
  @Test
  fun anIdentityAvifIsRefusedPastTheBound() {
    assumeAvifDecodes()
    val misses =
      AvifColourPictures.NCLX_IDENTITY_AVIFS.mapNotNull { still ->
        val file = still.bytes()
        val coding = checkNotNull(pictureYcbcrCoding(file))
        val sdk = Build.VERSION.SDK_INT
        val readable = readableMatrices(coding.fullRange, sdk, avif = true, tenBit = false)
        val landedFullRange = coding.fullRange && sdk >= WIDE_MATRIX_API
        val nearest = checkNotNull(coding.reading.nearestReadable(readable, coding.fullRange, landedFullRange))
        val want = pastBound(avif = true, nearest)
        val landing = file.ownLanding()
        "$still lands with ${landing?.refusal}, not $want".takeIf { nearest.withinBound || landing?.refusal != want }
          ?: file.refusalMiss("$still", landing)
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  private fun assumeAvifDecodes() {
    assumeTrue("Android decodes AVIF from API 31", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
  }

  private fun landingOf(still: AvifPicture): DecoderLanding? {
    if (still.nclx == null) return checkNotNull(still.bytes().landingHere())
    return still.bytes().ownLanding()
  }

  private fun reading(file: ByteArray): YcbcrReading = checkNotNull(pictureYcbcrCoding(file)).reading

  // The range core reads for the AVIF without an nclx, from its codestream.
  private fun undescribedRange(): Boolean = checkNotNull(pictureYcbcrCoding(UNDESCRIBED.bytes())).fullRange

  // How [file] read through read misses landing where [landing] says, with the limits it names, or null. One core
  // refuses, which has no landing, or one landing past the bound misses unless it is refused for it.
  private fun arrival(
    what: String,
    file: ByteArray,
    ycbcr: List<Int>,
    landing: DecoderLanding?,
  ): String? {
    if (landing.refused) return file.refusalMiss(what, landing)
    checkNotNull(landing)
    val picture = ImageSource.Bytes(file).read(context = null)
    if (picture !is DecodedPicture) return "$what came to $picture"
    if (picture.limits != landing.limits) return "$what reported ${picture.limits}, not ${landing.limits}"
    val read = picture.bitmap.getPixel(picture.bitmap.width / 2, picture.bitmap.height / 2)
    val want = ycbcr.argbThrough(landing.matrix, landing.fullRange, file)
    val off = channelsOff(read, want)
    if (off <= CODED_TOLERANCE) return null
    val readings =
      READINGS.joinToString { (matrix, full) ->
        "$matrix ${range(full)} ${ycbcr.argbThrough(matrix, full, file).hex()}"
      }
    return "$what read ${read.hex()} where ${landing.matrix} ${range(landing.fullRange)} gives ${want.hex()}, " +
      "$off off. Readings: $readings"
  }

  // [still] with its nclx recording BT.709 primaries, the sRGB transfer, [matrix] and [full] range.
  private fun recording(
    still: AvifPicture,
    matrix: Int,
    full: Boolean,
  ): ByteArray {
    val file = still.bytes()
    val nclx = String(file, Charsets.ISO_8859_1).indexOf("nclx") + 4
    val fields = byteArrayOf(0, BT709.toByte(), 0, SRGB_TRANSFER, 0, matrix.toByte(), if (full) FULL_RANGE else 0)
    return file.also { fields.copyInto(it, nclx) }
  }

  // [file] with its colour descriptions hidden and its codestream stating BT.601 beside BT.601 primaries, its nclx
  // still naming its own matrix.
  private fun restatedAsBt601(file: ByteArray): ByteArray {
    val described = file.withColourDescriptionsHidden(keepNclxPrimaries = true)
    val restated = heifWithRestatedColour(described) { _, _, _ -> RestatedColour(BT601, BT601) }
    return assertIs<RestatedHeif.Restated>(restated).bytes.withColourDescriptionsHidden()
  }

  // How a straight decode of [file] misses landing through the matrix and range [lands] gives, or handing back no
  // frame when it is null, or null.
  private fun stillMiss(
    what: String,
    file: ByteArray,
    lands: Pair<Int, Boolean>?,
  ): String? {
    val options = BitmapFactory.Options().apply { inPremultiplied = false }
    val bitmap = BitmapFactory.decodeByteArray(file, 0, file.size, options)
    if (lands == null) return bitmap?.let { "$what handed back a frame where it should hand back none" }
    bitmap ?: return "$what handed back no frame"
    val read = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    val want = FLAT.ycbcr.argbThrough(lands.first, lands.second, file)
    val off = channelsOff(read, want)
    return "$what read ${read.hex()} where ${lands.first} ${range(lands.second)} gives ${want.hex()}, $off off"
      .takeIf { off > CODED_TOLERANCE }
  }

  private fun range(full: Boolean): String = if (full) "full" else "limited"

  private companion object {
    const val BT709 = 1
    const val FCC = 4
    const val BT601 = 6
    const val SMPTE_240 = 7
    const val YCGCO = 8
    const val BT2020 = 9
    const val BT2020_CONSTANT = 10
    const val WIDE_MATRIX_API = 33
    const val MORE_API = 36
    const val SRGB_TRANSFER: Byte = 13
    const val FULL_RANGE = 0x80.toByte()
    const val CODED_TOLERANCE = 2
    const val PQ_TRANSFER = 16

    // Every matrix a still records here, the identity 0 and the ones no API level reads included.
    val MATRICES = listOf(0, BT709, 2, FCC, 5, BT601, SMPTE_240, YCGCO, BT2020, 10, 12, 14)
    val READINGS = listOf(BT601, BT709, BT2020).flatMap { matrix -> listOf(matrix to true, matrix to false) }
    val FLAT: AvifPicture = ColourPictures.NCLX_BT709_LIMITED
    val UNDESCRIBED: AvifPicture = ColourPictures.PROFILE_DISPLAY_P3
    val EDGE: AvifPicture = ColourPictures.NCLX_BT709_FULL_EDGE_CHROMA
    val PAINTED = listOf(ColourPictures.NCLX_DISPLAY_P3, FLAT, EDGE)
    val STILLS = ColourPictures.AVIFS + EDGE
  }
}
