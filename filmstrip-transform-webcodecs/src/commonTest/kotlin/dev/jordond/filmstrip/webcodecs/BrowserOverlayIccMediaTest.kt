@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.color.authoredHdrTexels
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.png.pngSamples
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.IccPictures
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.test.ycbcrToRgb
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.BrowserHdrMediaTest.Companion.LUMA_TOLERANCE
import dev.jordond.filmstrip.webcodecs.OverlayPictures.PICTURES
import dev.jordond.filmstrip.webcodecs.OverlayPictures.PIXELS
import dev.jordond.filmstrip.webcodecs.OverlayPictures.PNG
import dev.jordond.filmstrip.webcodecs.OverlayPictures.SIXTEEN_BIT_PICTURES
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.browserRenderOf
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.render.hdrOverlayTexels
import dev.jordond.filmstrip.webcodecs.internal.render.sdrOverlayCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Color-tagged overlay pictures drawn into a browser export and a preview, and read back.
 *
 * A PNG reaches the compositor as the samples core's PNG reader reads from it, and any other picture as its codes read
 * back through a 2D canvas from a bitmap decoded with no color conversion. A tagged picture then has to land where the
 * shared functions put those for the color [pictureColor] reads from its bytes: [pictureSrgbCodes] on SDR, and
 * [pictureLight] blended with the fill by [OverlayPixels.hdrSourceOver] on PQ and HLG. An untagged picture is one of
 * them and lands where it always has. Every picture sits over the fill in the gap before a late primary, once opaque
 * and once at half opacity, and two of them store half alpha.
 *
 * Chrome decodes no HEIC, so one is refused as undecodable even once its codestream states the coding its `nclx` names.
 */
class BrowserOverlayIccMediaTest {
  @Test
  fun everyPngReachesTheCompositorAsTheSamplesItStores() {
    for (picture in PICTURES + SIXTEEN_BIT_PICTURES) {
      val stored = picture.stored ?: continue
      val samples = withClue("$picture") { pngSamples(picture.bytes).shouldNotBeNull() }.rgba
      val middle = ((PNG.height / 2) * PNG.width + PNG.width / 2) * RGBA_CHANNELS
      val texel = samples.copyOfRange(middle, middle + RGBA_CHANNELS)

      withClue("$picture") { pictureSrgbCodes(null, texel).single() shouldBe stored.srgbCodes(null) }
      withClue("$picture") { pictureLight(null, texel) shouldBe stored.light(null) }
    }
  }

  // A browser bitmap keeps only the high byte of a sixteen-bit sample. These samples are no code times 257, so the full
  // samples land somewhere the high bytes do not.
  @Test
  fun aSixteenBitPngConvertsFromItsFullSamples() {
    for (picture in SIXTEEN_BIT_PICTURES) {
      val texel = picture.stored.shouldBeInstanceOf<StoredTexel.SixteenBit>().samples
      val color = pictureColor(picture.bytes)
      withClue("$picture samples are codes times 257") {
        texel.take(3).none { it % SAMPLE_PER_CODE == 0 } shouldBe true
      }
      val samples = ShortArray(PIXELS * RGBA_CHANNELS) { texel[it % RGBA_CHANNELS].toShort() }
      val highBytes = ByteArray(samples.size) { (texel[it % RGBA_CHANNELS] ushr BYTE_BITS).toByte() }

      val codes = pictureSrgbCodes(color, samples).flatMap { rgbaOf(it) }.toByteArray()
      withClue("$picture") { sdrOverlayCodes(samples, color) shouldBe codes }
      withClue("$picture") { hdrOverlayTexels(samples, color) shouldBe premultiplied(pictureLight(color, samples)) }
      withClue("$picture") {
        hdrOverlayTexels(samples, color).contentEquals(hdrOverlayTexels(highBytes, color)) shouldBe false
      }
    }
  }

  // The compositor converts Chrome's decode of the AVIF the resolver lands, so that decode is held to the picture's
  // YCbCr under the matrix and range its nclx names. A decode that dropped the range misses by far more than rounding.
  // Chrome decodes limited range BT.709 and BT.2020 a few codes off H.273, its own limit the effects module pins, so
  // such a picture is held to land nearer its nclx's reading than the same samples at the other range or through
  // another matrix.
  @Test
  fun everyAvifDecodesToItsYcbcrUnderItsNclx() =
    runTest {
      val misses =
        ColorPictures.AVIFS.mapNotNull { picture ->
          val matrix = picture.nclx?.matrix ?: UNSPECIFIED
          val primaries = picture.nclx?.primaries ?: UNSPECIFIED
          val transfer = picture.nclx?.transfer ?: UNSPECIFIED
          val fullRange = picture.nclx?.fullRange ?: true
          val (y, cb, cr) = picture.ycbcr
          val expected = ycbcrToRgb(y, cb, cr, matrix, primaries, transfer, fullRange)
          val decoded = AuthoredColors.channelsOf(decodedCodeOf(picture.bytes()))
          val landed =
            if (!fullRange && matrix in CHROME_DRIFTING_MATRICES) {
              val others =
                listOf(ycbcrToRgb(y, cb, cr, matrix, primaries, transfer, fullRange = true)) +
                  (OTHER_MATRICES - matrix).map { ycbcrToRgb(y, cb, cr, it, primaries, transfer, fullRange) }
              others.all { codeDistance(decoded, expected) < codeDistance(decoded, it) }
            } else {
              codeDistance(decoded, expected) <= YCBCR_ROUNDING
            }
          if (landed) null else "$picture decoded $decoded for ${expected.toList()}"
        }

      withClue(misses.joinToString("; ")) { misses.isEmpty() shouldBe true }
    }

  @Test
  fun aHeicOverlayIsRefusedAsUndecodable() =
    runTest {
      for (picture in ColorPictures.HEICS + ColorPictures.HEIC_UNTAGGED) {
        val overlay = ImageOverlay(ImageSource.ofBytes(picture.bytes()), Corner.TopStart)
        val verdict = engine().plan(compositionOf(null, listOf(overlay)), SDR_SPEC)

        val reasons = withClue("$picture") { verdict.shouldBeInstanceOf<Verdict.Incapable>() }.reasons
        val refused = reasons.filterIsInstance<FilmstripError.UnsupportedEffect>()
        withClue("$picture: $refused") {
          refused.any { it.specId == overlay.id && UNDECODABLE in it.message } shouldBe true
        }
      }
    }

  @Test
  fun untaggedCodesMapToTheLightTheyAlwaysHave() {
    val codes = midRangeCodes()

    hdrOverlayTexels(codes, color = null) shouldBe premultiplied(authoredHdrTexels(argbOf(codes)))
  }

  @Test
  fun anSrgbProfileLeavesTheCodesAsStored() {
    val codes = midRangeCodes()
    val color = pictureColor(IccPictures.SRGB.pngOf(PNG)).shouldNotBeNull()

    sdrOverlayCodes(codes, color) shouldBe codes
  }

  @Test
  fun everyPictureLandsWhereItsColorDoesOnSdr() =
    runTest {
      val misses = batches().flatMap { cells -> sdrMissesOf(cells) }

      withClue(misses.joinToString("; ")) { misses.isEmpty() shouldBe true }
    }

  @Test
  fun everyPictureLandsWhereItsLightDoesOnBothTransfers() =
    runTest {
      if (!Filmstrip { webCodecsBackend() }.encodesHdr()) return@runTest

      val misses =
        HdrTransfer.entries.flatMap { transfer ->
          batches().flatMap { cells -> hdrMissesOf(transfer, cells).map { "$transfer $it" } }
        }

      withClue(misses.joinToString("; ")) { misses.isEmpty() shouldBe true }
    }

  @Test
  fun aPreviewFrameShowsEveryPictureWhereItsColorDoesOnSdr() =
    runTest {
      val misses =
        batches().flatMap { cells -> previewReadingsOf(null, cells) }.mapNotNull { reading ->
          val expected = reading.cell.sdrCodes(FILL)
          val miss = (0 until 3).maxOf { abs(reading.read[it] - expected[it]) }
          if (miss <= AuthoredColors.TOLERANCE) null else "$reading for ${expected.toList()}"
        }

      withClue(misses.joinToString("; ")) { misses.isEmpty() shouldBe true }
    }

  // A renderer rounds every half-float tie one way, so the whole run has to land under a single rule.
  @Test
  fun aPreviewFrameShowsEveryPictureWhereItsLightDoesOnBothTransfers() =
    runTest {
      if (!Filmstrip { webCodecsBackend() }.encodesHdr()) return@runTest

      val readings =
        HdrTransfer.entries.flatMap { transfer -> batches().flatMap { cells -> previewReadingsOf(transfer, cells) } }
      val missesByRule =
        HALF_FLOAT_TIES.associateWith { tiesUp ->
          readings.mapNotNull { reading ->
            val expected = reading.cell.presented(FILL, tiesUp = tiesUp)
            val miss = (0 until 3).maxOf { abs(reading.read[it] - expected[it]) }
            val levels = reading.cell.presentedLevels(FILL, tiesUp = tiesUp).toList()
            if (miss <= PREVIEW_TOLERANCE) null else "$reading for $levels"
          }
        }

      withClue(
        missesByRule.entries.joinToString("\n") { (tiesUp, misses) -> "ties up $tiesUp: ${misses.joinToString("; ")}" },
      ) {
        missesByRule.values.any { it.isEmpty() } shouldBe true
      }
    }

  @Test
  fun aHalfFloatTieRoundsUpOrToEvenByItsRule() {
    val step = 2.0.pow(-12).toFloat()
    halfFloat(1246.5f * step, tiesUp = false) shouldBe 1246 * step
    halfFloat(1246.5f * step, tiesUp = true) shouldBe 1247 * step
    halfFloat(1247.5f * step, tiesUp = false) shouldBe 1248 * step
    halfFloat(1247.5f * step, tiesUp = true) shouldBe 1248 * step

    val subnormal = 2.0.pow(-24).toFloat()
    halfFloat(2.5f * subnormal, tiesUp = false) shouldBe 2 * subnormal
    halfFloat(2.5f * subnormal, tiesUp = true) shouldBe 3 * subnormal
    halfFloat(-2.5f * subnormal, tiesUp = false) shouldBe -2 * subnormal
  }

  // A preview that converted no picture would show each tagged one where its stored texel lands untagged. Twice the
  // preview bound has to sit below the smallest such miss under either tie rule, or one reading could land within the
  // bound of both. A picture whose color gives the same sRGB codes as untagged describes sRGB itself, so it has no
  // wrong reading to tell apart and is left out.
  @Test
  fun thePreviewBoundIsBelowTheSmallestMissOfTheStoredCodes() =
    runTest {
      val misses =
        batches().flatten().mapNotNull { cell ->
          if (cell.stored.srgbCodes(cell.color) == cell.stored.srgbCodes(null)) return@mapNotNull null
          val miss =
            HALF_FLOAT_TIES.minOf { tiesUp ->
              val shown = cell.presented(FILL, tiesUp = tiesUp)
              HALF_FLOAT_TIES.minOf { wrongTies ->
                val wrong = cell.presented(FILL, asUntagged = true, tiesUp = wrongTies)
                (0 until 3).maxOf { abs(shown[it] - wrong[it]) }
              }
            }
          cell to miss
        }
      val (cell, smallest) = misses.minBy { it.second }

      withClue("$cell misses by only $smallest untagged, too close for a bound of $PREVIEW_TOLERANCE to tell apart") {
        (2 * PREVIEW_TOLERANCE < smallest) shouldBe true
      }
    }

  @Test
  fun theOverlayBoundIsAtMostTheWidestBlendBound() {
    withClue("overlays are held to $LUMA_TOLERANCE codes, past ${HdrOverlayCells.MAX_BLEND_TOLERANCE}") {
      (LUMA_TOLERANCE <= HdrOverlayCells.MAX_BLEND_TOLERANCE) shouldBe true
    }
  }

  private suspend fun sdrMissesOf(cells: List<OverlayCell>): List<String> {
    val composition = compositionOf(null, cells.map { it.spec })
    val gap = gapOf(decodeFrames(exported(composition, SDR_SPEC)).map { it.timestampUs to it })
    return cells.zip(rectsOf(null, composition)).mapNotNull { (cell, rect) ->
      val read = gap.at(rect.middleX, rect.middleY)
      val expected = cell.sdrCodes(FILL)
      val tolerance = if (cell.translucent) OverlayPixels.ENCODED_TOLERANCE else AuthoredColors.TOLERANCE
      val miss = maxOf(abs(read.red - expected[0]), abs(read.green - expected[1]), abs(read.blue - expected[2]))
      if (miss <= tolerance) null else "$cell read $read for ${expected.toList()}"
    }
  }

  private suspend fun hdrMissesOf(
    transfer: HdrTransfer,
    cells: List<OverlayCell>,
  ): List<String> {
    val composition = compositionOf(transfer, cells.map { it.spec })
    val frames = decodeTenBitFrames(exported(composition, HDR_SPEC), transfer)
    val gap = gapOf(frames.map { it.timestampUs to it })
    return cells.zip(rectsOf(transfer, composition)).mapNotNull { (cell, rect) ->
      val (luma, cb, cr) = tenBitCodesFromSignal(transfer.signalFromNits(cell.nits(FILL)))
      val (readCb, readCr) = gap.chromaAt(rect.middleX, rect.middleY)
      val readLuma = gap.lumaAt(rect.middleX, rect.middleY)
      val landed =
        abs(readLuma - luma) <= LUMA_TOLERANCE &&
          abs(readCb - cb) <= LUMA_TOLERANCE &&
          abs(readCr - cr) <= LUMA_TOLERANCE
      if (landed) null else "$cell read ($readLuma, $readCb, $readCr) for ($luma, $cb, $cr)"
    }
  }

  // An SDR preview runs the export's compositor with no codec, and a kept grade's preview shows its half-float buffer
  // through the SDR display curve, clamped at reference white.
  private suspend fun previewReadingsOf(
    transfer: HdrTransfer?,
    cells: List<OverlayCell>,
  ): List<PreviewReading> {
    val composition = compositionOf(transfer, cells.map { it.spec })
    val spec = if (transfer == null) SDR_SPEC else HDR_SPEC
    val resolved = engine().resolve(composition, spec).shouldBeInstanceOf<ResolveResult.Resolved>().composition
    val rects = rectsOf(transfer, composition)
    val preview = resolved.toBrowserPreview(composition)
    try {
      val drawn = withClue("$transfer drew no preview frame in the gap") { preview.frameAt(GAP_TIME).shouldNotBeNull() }
      return cells.zip(rects).map { (cell, rect) ->
        val column = (rect.middleX * drawn.size.width).toInt()
        val row = (rect.middleY * drawn.size.height).toInt()
        val offset = (row * drawn.size.width + column) * RGBA_CHANNELS
        PreviewReading(transfer, cell, IntArray(3) { drawn.pixels[offset + it].toInt() and BYTE_MASK })
      }
    } finally {
      preview.release()
    }
  }

  private class PreviewReading(
    val transfer: HdrTransfer?,
    val cell: OverlayCell,
    val read: IntArray,
  ) {
    override fun toString(): String = "${transfer ?: "SDR"} $cell previewed ${read.toList()}"
  }

  private fun <T> gapOf(frames: List<Pair<Double, T>>): T {
    val gapUs = GAP_TIME.inWholeMicroseconds.toDouble()
    val (timestampUs, gap) = frames.minBy { abs(it.first - gapUs) }
    withClue("the file has no frame in the gap") { (timestampUs < LATE_START.inWholeMicroseconds) shouldBe true }
    return gap
  }

  /**
   * Where the compositor draws each of the composition's overlays in the gap, read off the same lowering an export
   * runs, in the order the composition lists them.
   */
  private suspend fun rectsOf(
    transfer: HdrTransfer?,
    composition: EditComposition,
  ): List<NormalizedRect> {
    val spec = if (transfer == null) SDR_SPEC else HDR_SPEC
    val resolved = engine().resolve(composition, spec).shouldBeInstanceOf<ResolveResult.Resolved>().composition
    val render = browserRenderOf(resolved, composition)
    render.hdrTransfer shouldBe transfer
    return render.compositionOverlays.map {
      withClue("an overlay is hidden in the gap") {
        it.drawAt(GAP_TIME).shouldNotBeNull()
      }.rect
    }
  }

  private suspend fun compositionOf(
    transfer: HdrTransfer?,
    overlays: List<ImageOverlay>,
  ): EditComposition {
    val clip =
      if (transfer == null) {
        makeClip(width = WIDTH, height = HEIGHT, frames = CLIP_FRAMES, frameRate = FRAME_RATE, color = Rgb.Blue)
      } else {
        makeHdrClip(transfer, width = WIDTH, height = HEIGHT, frames = CLIP_FRAMES, frameRate = FRAME_RATE) { PICTURE }
      }
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clip))), start = LATE_START)),
      effects = overlays,
      audio = AudioSpec.Remove,
      fill = Fill.Solid(FILL),
    )
  }

  private suspend fun exported(
    composition: EditComposition,
    spec: ExportSpec,
  ): MediaSource {
    val engine = engine()
    val plan =
      when (val verdict = engine.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
      }
    val statuses = engine.export(plan, MediaSink.Uri("")).toList()
    statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()?.let {
      throw AssertionError("the export failed: ${it.error.message}")
    }
    val success = (statuses.filterIsInstance<ExportStatus.Success>().singleOrNull()).shouldNotBeNull()
    return MediaSource.Uri(success.output.shouldBeInstanceOf<MediaSink.Uri>().uri)
  }

  private fun codeDistance(
    decoded: List<Int>,
    expected: IntArray,
  ): Int = (0 until 3).maxOf { abs(decoded[it] - expected[it]) }

  private fun engine(): BrowserExportEngine =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    )

  /**
   * Every picture, opaque and at half opacity, laid out [PICTURES_PER_FRAME] to a frame with each picture's stored
   * texel, decoded the way the compositor decodes it where it is not known ahead.
   */
  private suspend fun batches(): List<List<OverlayCell>> =
    (PICTURES + SIXTEEN_BIT_PICTURES).chunked(PICTURES_PER_FRAME).map { batch ->
      batch.flatMapIndexed { index, picture ->
        val stored = picture.stored ?: StoredTexel.EightBit(decodedCodeOf(picture.bytes))
        val color = pictureColor(picture.bytes)
        listOf(1f, HALF).mapIndexed { pass, opacity ->
          val offset = OverlayOffset((index % COLUMNS) * CELL_WIDTH, ((index / COLUMNS) * 2 + pass) * CELL_HEIGHT)
          val spec =
            ImageOverlay(
              ImageSource.ofBytes(picture.bytes),
              Corner.TopStart,
              margin = CELL_MARGIN,
              scale = IMAGE_SCALE,
              opacity = opacity,
              animation = OverlayAnimation { OverlayFrame(offset = offset) },
            )
          OverlayCell(picture, spec, opacity, color, stored)
        }
      }
    }

  private companion object {
    const val UNDECODABLE = "could not be decoded as an image."

    const val PICTURES_PER_FRAME = 6
    const val COLUMNS = 3
    const val WIDTH = 256
    const val HEIGHT = 192
    const val FRAME_RATE = 30
    const val CLIP_FRAMES = 6
    val LATE_START = 200.milliseconds

    // The middle of the gap, on the output's own frame grid.
    val GAP_TIME: Duration = outputSlotTime(outputSlotOf(LATE_START / 2, FRAME_RATE), FRAME_RATE)

    // The clip after the gap. The overlays never sit over it, so its light only has to be a legal grade.
    val PICTURE = floatArrayOf(120f, 180f, 75f)
    val FILL = AuthoredColors.MID_GREY

    // A square badge three twentieths of the frame wide, a column a third of the frame wide and a row a quarter of
    // it tall.
    const val IMAGE_SCALE = 0.15f
    const val CELL_MARGIN = 0.02f
    const val CELL_WIDTH = 0.3f
    const val CELL_HEIGHT = 0.24f
    const val HALF = 0.5f

    val SDR_SPEC = ExportSpec(bitrate = MEASURING_BITRATE, audioCodec = AudioCodec.None, frameRate = FRAME_RATE)
    val HDR_SPEC =
      ExportSpec(
        bitrate = MEASURING_BITRATE,
        audioCodec = AudioCodec.None,
        frameRate = FRAME_RATE,
        hdr = HdrMode.KeepHdr,
      )

    // How far a kept grade's preview reading may sit from what the rule shows once every half-float buffer on the way
    // is modelled, in eight-bit codes per channel. Measured at 0 on SwiftShader, the renderer CI runs, and on Metal.
    const val PREVIEW_TOLERANCE = 0f

    // A value exactly halfway between two half floats is stored as either one. IEEE 754 rounds the tie to even, and
    // SwiftShader's blend rounds it up: a linear picture at half opacity over mid grey blends green to 1246.5 steps
    // of 2^-12 and is stored as 1247, which shows as 149 where the even 1246 shows as 148. Metal rounds every tie to
    // even.
    val HALF_FLOAT_TIES = listOf(false, true)

    // How far Chrome's decode of an AVIF may sit from the shared YCbCr decode, in eight-bit codes per channel.
    const val YCBCR_ROUNDING = 1

    // The matrix, primaries and transfer an AVIF with no nclx decodes under, as its codestream signals none.
    const val UNSPECIFIED = 2

    // BT.709 and BT.2020, which Chrome decodes off H.273 at limited range, and the matrices a drifting decode is held
    // further from.
    val CHROME_DRIFTING_MATRICES = setOf(1, 9)
    val OTHER_MATRICES = setOf(1, 6, 9)

    const val SAMPLE_PER_CODE = 257
    const val BYTE_BITS = 8
    const val RGBA_CHANNELS = 4
    const val BYTE_MASK = 0xFF
  }
}
