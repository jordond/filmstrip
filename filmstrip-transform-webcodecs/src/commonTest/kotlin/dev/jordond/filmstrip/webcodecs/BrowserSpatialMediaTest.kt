package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.color.hlgNitsFromScene
import dev.jordond.filmstrip.color.hlgSceneFromDisplayNits
import dev.jordond.filmstrip.color.hlgSceneFromNits
import dev.jordond.filmstrip.color.nitsFromSdrSignal
import dev.jordond.filmstrip.color.sdrSignalFromHlgScene
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.color.vignetteGain
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.effects.spatial.Vignette
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * A blur and a vignette drawn through the real compositor, each read against the shared function it lowers.
 */
class BrowserSpatialMediaTest {
  @Test
  fun aBlurAtExportHeightMatchesTheSharedPlan() =
    runTest {
      assertBlurMatches(verticalEdge(), MID_SIGMA, heightPx = null, expectedWidth = WIDTH)
    }

  @Test
  fun aBlurAtPreviewHeightMatchesTheSharedPlan() =
    runTest {
      assertBlurMatches(verticalEdge(), MID_SIGMA, heightPx = HEIGHT / 2, expectedWidth = WIDTH / 2)
    }

  @Test
  fun aBlurOnAWidthItsFactorDoesNotDivideMatchesTheSharedPlan() =
    runTest {
      WIDTH % BlurPlan.of(UNEVEN_SIGMA.toDouble(), WIDTH).factor shouldBeGreaterThan 0

      assertBlurMatches(verticalEdge(), UNEVEN_SIGMA, heightPx = null, expectedWidth = WIDTH)
    }

  // The edge runs across the frame, so only the pass down it moves the read.
  @Test
  fun aBlurAcrossAHorizontalEdgeMatchesTheSharedPlan() =
    runTest {
      val source =
        MediaSource.Bytes(
          makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _, y -> if (y < HEIGHT / 2) LIGHT else DARK },
        )

      assertBlurMatches(source, MID_SIGMA, heightPx = null, expectedWidth = WIDTH)
    }

  @Test
  fun aCompositionBlurOnAContainedClipKeepsTheFillInTheBarsAndThePictureAtItsEdge() =
    runTest {
      val fill = Fill.Solid(FILL_ARGB)
      val layout = listOf<EffectSpec>(Crop(AspectRatio.Square, Fit.Contain))
      val plain = frameOf(listOf(Clip(verticalEdge())), effects = layout, fill = fill)
      val blurred = frameOf(listOf(Clip(verticalEdge())), effects = layout + GaussianBlur(MID_SIGMA), fill = fill)
      val width = plain.size.width
      val height = plain.size.height
      // The clip's picture is the layout's width and its own aspect tall, centred. The frame under the fill holds a
      // pixel of it where the pixel's centre falls inside, with no partial coverage at its edge.
      val pictureHeight = width.toDouble() * HEIGHT / WIDTH
      val top = (height - pictureHeight) / 2.0
      val alpha = DoubleArray(width * height) { if (it / width + 0.5 in top..(top + pictureHeight)) 1.0 else 0.0 }
      val firstRow = (0 until height).first { alpha[it * width] == 1.0 }
      firstRow shouldBeGreaterThan 0
      // The edge runs down the picture, so its middle row is every row of it.
      val middle = height / 2 * width
      val picture = DoubleArray(width * height) { plain.redAt(middle + it % width) / MAX_CODE }

      val reference = blurPlane(picture, alpha, width, height, MID_SIGMA.toDouble())
      val fillRed = (FILL_ARGB shr RED_SHIFT and BYTE_MASK).toDouble()
      var worst = 0.0
      for (index in reference.indices) {
        val expected = if (alpha[index] == 1.0) reference[index] * MAX_CODE else fillRed
        worst = maxOf(worst, abs(blurred.redAt(index) - expected))
      }
      val besideBar = (firstRow - 1) * width + width / 2
      withClue("the bar row beside the picture read ${blurred.redAt(besideBar)}") {
        blurred.redAt(besideBar) shouldBe fillRed
      }
      withClue(
        "the picture's first row read ${blurred.redAt(firstRow * width)} and its middle ${blurred.redAt(middle)}",
      ) {
        abs(blurred.redAt(firstRow * width) - blurred.redAt(middle)) shouldBeLessThanOrEqualTo DRAWN_CODES
      }
      withClue("the drawn blur is $worst codes off the shared reference over the fill at its worst pixel") {
        worst shouldBeLessThanOrEqualTo DRAWN_CODES
      }
    }

  @Test
  fun aBlurOnAKeptPqGradeMatchesTheSharedPlanOnTheSdrSignal() = runTest { assertKeptBlur(HdrTransfer.Pq) }

  // HLG runs on scene light per channel, so an edge between two colors lands elsewhere if the pass read display light.
  @Test
  fun aBlurOnAKeptHlgGradeMatchesTheSharedPlanOnTheSdrSignal() = runTest { assertKeptBlur(HdrTransfer.Hlg) }

  @Test
  fun aVignetteAtPreviewHeightFollowsTheSharedGain() =
    runTest {
      val source = MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> Rgb.Grey })

      assertVignette(source, VIGNETTE_POINTS, onClip = true, contained = false, heightPx = HEIGHT / 2)
    }

  @Test
  fun aVignetteFollowsTheSharedGainAlongItsRamp() =
    runTest {
      val source = MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> Rgb.Grey })

      assertVignette(source, VIGNETTE_POINTS, onClip = true, contained = false)
    }

  @Test
  fun aClipVignetteOnAContainedClipSpansTheClipsOwnFrame() =
    runTest {
      val source = MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> Rgb.Grey })

      assertVignette(source, CONTAINED_POINTS, onClip = true, contained = true)
    }

  @Test
  fun aCompositionVignetteOnAContainedClipSpansTheLayout() =
    runTest {
      val source = MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> Rgb.Grey })

      assertVignette(source, CONTAINED_POINTS, onClip = false, contained = true)
    }

  @Test
  fun aVignetteOnAKeptPqGradeTakesTheSharedGainOnTheSdrSignal() = runTest { assertKeptGrade(HdrTransfer.Pq) }

  // HLG decodes to scene light first, so this fails if the compositor held that rather than display light.
  @Test
  fun aVignetteOnAKeptHlgGradeTakesTheSharedGainOnTheSdrSignal() = runTest { assertKeptGrade(HdrTransfer.Hlg) }

  @Test
  fun aClipOverlayStaysSharpOverTheBlur() =
    runTest {
      val source = MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, _ -> Rgb.Black })
      val overlay =
        ImageOverlay(ImageSource.ofBytes(OverlayPixels.pngOf(Size(4, 4), OVERLAY_ARGB)), Corner.TopStart, 0f, 0.25f)
      val sharp = frameOf(listOf(Clip(source, effects = listOf(overlay))))
      val blurred = frameOf(listOf(Clip(source, effects = listOf(GaussianBlur(0.1f), overlay))))

      // Five pixels inside the overlay's right edge, where a blur of it would take about half its light.
      val x = (WIDTH * 0.25 - 5.0) / WIDTH
      val y = 0.1
      withClue("the overlay read ${blurred.at(x, y)} over the blur and ${sharp.at(x, y)} without it") {
        blurred.at(x, y).isNear(sharp.at(x, y), DRAWN_CODES.toInt()) shouldBe true
      }
    }

  // Reads a vignette at each of [points] against the shared gain where that point sits on the frame it spans, which is
  // the clip's own frame [onClip] and the layout otherwise. [contained] lays the clip into a square output, so the two
  // frames part.
  private suspend fun assertVignette(
    source: MediaSource,
    points: List<Pair<Double, Double>>,
    onClip: Boolean,
    contained: Boolean,
    heightPx: Int? = null,
  ) {
    val vignette = Vignette()
    val layout = if (contained) listOf<EffectSpec>(Crop(AspectRatio.Square, Fit.Contain)) else emptyList()
    val plain = frameOf(listOf(Clip(source)), heightPx, effects = layout)
    val drawn =
      if (onClip) {
        frameOf(listOf(Clip(source, effects = listOf(vignette))), heightPx, effects = layout)
      } else {
        frameOf(listOf(Clip(source)), heightPx, effects = layout + vignette)
      }
    heightPx?.let { drawn.size.height shouldBe it }
    val width = drawn.size.width.toDouble()
    val height = drawn.size.height.toDouble()
    // How much of the output's height the frame the vignette spans takes, centred on it.
    val band = if (onClip) width * HEIGHT / WIDTH / height else 1.0
    val top = (1.0 - band) / 2.0

    for ((x, y) in points) {
      val gain =
        vignetteGain(
          ((x * width).toInt() + 0.5) / width,
          (((y * height).toInt() + 0.5) / height - top) / band,
          width / (height * band),
          vignette.strength.toDouble(),
          vignette.radius.toDouble(),
          vignette.softness.toDouble(),
        )
      val expected = plain.at(x, y).red * gain
      val read = drawn.at(x, y).red
      withClue("at $x, $y the vignette read $read, and the shared gain $gain puts it at $expected") {
        abs(read - expected) shouldBeLessThanOrEqualTo DRAWN_CODES
      }
    }
  }

  // A vignette on a kept grade multiplies the SDR signal the light encodes to, so each channel lands on the light of
  // its signal times the shared gain.
  private suspend fun assertKeptGrade(transfer: HdrTransfer) {
    if (!webCodecsFilmstrip().encodesHdr()) return

    val vignette = Vignette()
    val bytes = makeHdrClip(transfer, width = HDR_SIDE, height = HDR_SIDE) { KEPT_NITS }
    val composition =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = listOf(vignette))))),
        audio = AudioSpec.Remove,
      )
    val spec = ExportSpec(bitrate = MEASURING_BITRATE, audioCodec = AudioCodec.None, hdr = HdrMode.KeepHdr)
    val success = exportOf(composition, MediaSink.Uri(""), spec)
    val frame = decodeTenBitFrames(outputOf(success), transfer).firstOrNull().shouldNotBeNull()

    val (x, y) = KEPT_POINT
    val gain =
      vignetteGain(
        ((x * frame.width).toInt() + 0.5) / frame.width,
        ((y * frame.height).toInt() + 0.5) / frame.height,
        frame.width.toDouble() / frame.height,
        vignette.strength.toDouble(),
        vignette.radius.toDouble(),
        vignette.softness.toDouble(),
      ).toFloat()
    val read = frame.nitsAt(x, y)
    repeat(3) { channel ->
      val expected = nitsFromSdrSignal(sdrSignalFromNits(KEPT_NITS[channel]) * gain)
      withClue(
        "$transfer channel $channel read ${read[channel]}, and the gain $gain on the signal puts it at $expected",
      ) {
        abs(read[channel] - expected) shouldBeLessThanOrEqualTo maxOf(expected * KEPT_TOLERANCE, NITS_FLOOR)
      }
    }
  }

  // Reads a blur of [source] over the whole frame against the shared reference run on the frame drawn without it.
  private suspend fun assertBlurMatches(
    source: MediaSource,
    sigma: Float,
    heightPx: Int?,
    expectedWidth: Int,
  ) {
    val plain = frameOf(listOf(Clip(source)), heightPx)
    val blurred = frameOf(listOf(Clip(source, effects = listOf(GaussianBlur(sigma)))), heightPx)
    blurred.size.width shouldBe expectedWidth

    val width = plain.size.width
    val height = plain.size.height
    val plane = plain.redPlane()
    val reference = blurPlane(plane, DoubleArray(plane.size) { 1.0 }, width, height, sigma.toDouble())
    var worst = 0.0
    var spread = 0.0
    for (index in reference.indices) {
      worst = maxOf(worst, abs(blurred.redAt(index) - reference[index] * MAX_CODE))
      spread = maxOf(spread, abs(reference[index] - plane[index]) * MAX_CODE)
    }

    withClue("the reference blur moves the edge by $spread codes, so the read is not of a sharp frame") {
      spread shouldBeGreaterThan SHARP_SPREAD
    }
    withClue("the drawn blur is $worst codes off the shared reference at its worst pixel") {
      worst shouldBeLessThanOrEqualTo DRAWN_CODES
    }
  }

  // A blur on a kept grade runs on the SDR signal each channel's light encodes to, which on HLG is the signal of its
  // scene light read per channel. The preview shows light under reference white through the SDR display curve, so the
  // light of the frame drawn without the blur is read back off it, blurred by the shared reference on the signal and
  // carried back to the code the preview shows.
  private suspend fun assertKeptBlur(transfer: HdrTransfer) {
    // The fixture is a ten-bit encode, which a page without an HDR encoder cannot make.
    if (!webCodecsFilmstrip().encodesHdr()) return

    val source =
      MediaSource.Bytes(
        makeHdrClip(transfer, width = HDR_SIDE, height = HDR_SIDE) { y ->
          if (y <
            HDR_SIDE / 2
          ) {
            KEPT_TOP
          } else {
            KEPT_BOTTOM
          }
        },
      )
    val spec = ExportSpec(audioCodec = AudioCodec.None, frameRate = FRAME_RATE, hdr = HdrMode.KeepHdr)
    val plain = frameOf(listOf(Clip(source)), spec = spec)
    val drawn = frameOf(listOf(Clip(source, effects = listOf(GaussianBlur(MID_SIGMA)))), spec = spec)

    val pixels = HDR_SIDE * HDR_SIDE
    val signals =
      Array(pixels) {
        signalOf(
          DoubleArray(3) { channel ->
            nitsFromSdrSignal(plain.codeAt(it, channel) / MAX_CODE)
          },
          transfer,
        )
      }
    val opaque = DoubleArray(pixels) { 1.0 }
    val blurred =
      Array(3) { channel ->
        blurPlane(
          DoubleArray(pixels) {
            signals[it][channel]
          },
          opaque,
          HDR_SIDE,
          HDR_SIDE,
          MID_SIGMA.toDouble(),
        )
      }
    var worst = 0.0
    var spread = 0.0
    for (index in 0 until pixels) {
      val nits = nitsOf(DoubleArray(3) { blurred[it][index] }, transfer)
      repeat(3) { channel ->
        val expected = sdrSignalFromNits(nits[channel]).coerceAtMost(1.0) * MAX_CODE
        worst = maxOf(worst, abs(drawn.codeAt(index, channel) - expected))
        spread = maxOf(spread, abs(plain.codeAt(index, channel) - expected))
      }
    }
    withClue("the reference blur moves the edge by $spread codes, so the read is not of a sharp frame") {
      spread shouldBeGreaterThan SHARP_SPREAD
    }
    withClue("$transfer read $worst codes off the shared reference on the signal at its worst pixel") {
      worst shouldBeLessThanOrEqualTo DRAWN_CODES
    }
  }

  private fun signalOf(
    nits: DoubleArray,
    transfer: HdrTransfer,
  ): DoubleArray =
    when (transfer) {
      HdrTransfer.Pq -> DoubleArray(3) { sdrSignalFromNits(nits[it]) }
      HdrTransfer.Hlg -> hlgSceneFromNits(nits).let { scene -> DoubleArray(3) { sdrSignalFromHlgScene(scene[it]) } }
    }

  private fun nitsOf(
    signal: DoubleArray,
    transfer: HdrTransfer,
  ): DoubleArray =
    when (transfer) {
      HdrTransfer.Pq -> DoubleArray(3) { nitsFromSdrSignal(signal[it]) }
      HdrTransfer.Hlg -> hlgNitsFromScene(DoubleArray(3) { hlgSceneFromDisplayNits(nitsFromSdrSignal(signal[it])) })
    }

  private suspend fun verticalEdge(): MediaSource =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, FRAME_RATE) { _, x, _ -> if (x < WIDTH / 2) LIGHT else DARK },
    )

  private fun PreviewFrame.redAt(index: Int): Double = codeAt(index, 0)

  private fun PreviewFrame.codeAt(
    index: Int,
    channel: Int,
  ): Double = (pixels[index * RGBA + channel].toInt() and BYTE_MASK).toDouble()

  private fun PreviewFrame.redPlane(): DoubleArray = DoubleArray(size.width * size.height) { redAt(it) / MAX_CODE }

  private suspend fun frameOf(
    clips: List<Clip>,
    heightPx: Int? = null,
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Black,
    spec: ExportSpec = ExportSpec(heightPx = heightPx, videoCodec = VideoCodec.H264),
  ): PreviewFrame {
    val composition =
      Composition(tracks = listOf(Track(clips)), effects = effects, audio = AudioSpec.Remove, fill = fill)
    val resolved =
      webCodecsExportEngine(
        components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
        prober = BrowserProber(),
      ).resolve(composition, spec)
        .shouldBeInstanceOf<ResolveResult.Resolved>()
        .composition
    val preview = resolved.toBrowserPreview(composition)
    try {
      return preview.frameAt(outputSlotTime(MIDDLE_SLOT, FRAME_RATE)).shouldNotBeNull()
    } finally {
      preview.release()
    }
  }

  private companion object {
    // A sigma of 0.05 boxes this down by two, which divides it, and a sigma of 0.07 by three, which does not.
    const val WIDTH = 320
    const val HEIGHT = 180
    const val FRAMES = 12
    const val FRAME_RATE = 30
    const val MIDDLE_SLOT = 6L
    const val OVERLAY_ARGB = 0xFFF0F0F0.toInt()
    const val FILL_ARGB = 0xFF3080C0.toInt()
    const val RED_SHIFT = 16
    const val MID_SIGMA = 0.05f
    const val UNEVEN_SIGMA = 0.07f
    const val MAX_CODE = 255.0
    const val RGBA = 4
    const val BYTE_MASK = 0xFF

    // Each eight bit target between the steps rounds once, and the read rounds once more.
    const val DRAWN_CODES = 2.0

    // A blur of a 180 code edge at a sigma of 16 pixels moves the columns beside it by far more than this.
    const val SHARP_SPREAD = 40.0

    val LIGHT = Rgb(220, 220, 220)
    val DARK = Rgb(40, 40, 40)

    // The centre, a point part way down the ramp, one near the radius on the diagonal and one near a corner.
    val VIGNETTE_POINTS = listOf(0.5 to 0.5, 0.15 to 0.5, 0.2 to 0.2, 0.03 to 0.05)

    // Inside the picture of a clip contained in a square, each part way down the ramp on both its frame and the layout.
    val CONTAINED_POINTS = listOf(0.5 to 0.5, 0.15 to 0.5, 0.08 to 0.3, 0.9 to 0.7)

    // Half way along the ramp at the default shape, where the gain on the signal and on light part by a quarter.
    val KEPT_POINT = 0.1 to 0.2

    // Middle of the range on both transfers and off-white on every channel.
    val KEPT_NITS = floatArrayOf(120f, 180f, 75f)

    // Two colors under reference white that lean opposite ways, so the light each channel is blurred in shows.
    val KEPT_TOP = floatArrayOf(170f, 60f, 20f)
    val KEPT_BOTTOM = floatArrayOf(20f, 70f, 160f)

    // Large enough that the gain moves about a percent a pixel at KEPT_POINT.
    const val HDR_SIDE = 256

    // The encode and the transfer each way, the same allowance BrowserHdrMediaTest gives an HLG grade.
    const val KEPT_TOLERANCE = 0.06f

    // Below this the transfer's own quantisation dominates and a relative bound says nothing.
    const val NITS_FLOOR = 1.5f
  }
}
