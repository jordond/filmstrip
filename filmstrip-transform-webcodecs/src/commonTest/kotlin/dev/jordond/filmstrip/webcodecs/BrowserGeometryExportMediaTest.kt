package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.geometry.containScale
import dev.jordond.filmstrip.transform.internal.geometry.coverScale
import dev.jordond.filmstrip.transform.internal.geometry.frameAfter
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * Rotate and Scale drawn through a real browser export and read back out of the written file.
 *
 * Every frame size comes from `frameAfter`, every fitted edge from `containScale` or `coverScale` and every overlay
 * rect from `placedOn` and `rectOn`, so a case measures the compositor against the shared geometry rather than against
 * a number of its own. The fixtures carry a different color in each quadrant or band, so a frame turned or fitted the
 * wrong way reads a different color.
 */
class BrowserGeometryExportMediaTest {
  @Test
  fun eachQuarterTurnMovesTheQuadrantsCounterClockwise() =
    runTest {
      for (degrees in listOf(90, 180, 270)) {
        val frame = exported(single(quadrantClip(), clipEffects = listOf(Rotate(degrees)))).last()
        val turns = degrees / QUARTER_TURN

        withClue("Rotate($degrees)") {
          Size(frame.width, frame.height) shouldBe frameAfter(Rotate(degrees), QUADRANT_SIZE)
          for ((x, y) in QUADRANT_PROBES) {
            assertReads(quadrantAt(turned(turns, x, y)), frame.at(x, y), "($x, $y)", Rgb.DEFAULT_TOLERANCE.toFloat())
          }
        }
      }
    }

  // The crop is listed first and still runs on the turned frame, since rotation leads the canonical order. Cropping
  // first would keep the source's top left quadrant, which is another color.
  @Test
  fun aCropAfterARotationCutsTheTurnedFrame() =
    runTest {
      val crop = CropRect(TOP_LEFT_QUARTER)
      val frame = exported(single(quadrantClip(), clipEffects = listOf(crop, Rotate(QUARTER_TURN)))).last()

      Size(frame.width, frame.height) shouldBe frameAfter(crop, frameAfter(Rotate(QUARTER_TURN), QUADRANT_SIZE))
      for ((x, y) in listOf(0.5 to 0.5, 0.3 to 0.7)) {
        val turnedX = TOP_LEFT_QUARTER.left + x * TOP_LEFT_QUARTER.width
        val turnedY = TOP_LEFT_QUARTER.top + y * TOP_LEFT_QUARTER.height
        assertReads(
          quadrantAt(turned(1, turnedX, turnedY)),
          frame.at(x, y),
          "($x, $y)",
          Rgb.DEFAULT_TOLERANCE.toFloat(),
        )
      }
    }

  @Test
  fun aCompositionRotationTurnsEveryClip() =
    runTest {
      val bytes = quadrantClip()
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes)), Clip(MediaSource.Bytes(bytes))))),
          effects = listOf(Rotate(QUARTER_TURN)),
          audio = AudioSpec.Remove,
        )
      val frames = exported(composition)

      for ((label, frame) in listOf("the first clip" to frames.first(), "the second clip" to frames.last())) {
        withClue(label) {
          Size(frame.width, frame.height) shouldBe frameAfter(Rotate(QUARTER_TURN), QUADRANT_SIZE)
          for ((x, y) in QUADRANT_PROBES) {
            assertReads(quadrantAt(turned(1, x, y)), frame.at(x, y), "($x, $y)", Rgb.DEFAULT_TOLERANCE.toFloat())
          }
        }
      }
    }

  // A wide clip after a 4:3 one that sets the frame. Contain leaves bars above and below it, Crop cuts its sides, and
  // Stretch fills the frame on both axes, so the edge band and the bar row read differently under each.
  @Test
  fun aScaleFitsAWideClipIntoTheFrameItsFitNames() =
    runTest {
      val opener = makeClip(width = OPENER_SIZE.width, height = OPENER_SIZE.height, frames = OPENER_FRAMES)
      val wide = bandedClip()
      for (fit in Fit.entries) {
        val scale = Scale(SCALED_HEIGHT, fit)
        val composition =
          Composition(
            tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(opener)), Clip(MediaSource.Bytes(wide))))),
            effects = listOf(scale),
            audio = AudioSpec.Remove,
          )
        val frame = exported(composition).last()
        val output = frameAfter(scale, OPENER_SIZE)

        withClue("$fit") {
          Size(frame.width, frame.height) shouldBe output
          val (halfW, halfH) = halfExtents(fit, frameAfter(scale, BANDED_SIZE), output)
          for ((x, y) in listOf(EDGE_PROBE to 0.5, 0.5 to BAR_PROBE, 0.5 to 0.5)) {
            assertReads(fittedAt(halfW, halfH, x, y), frame.at(x, y), "($x, $y)", Rgb.DEFAULT_TOLERANCE.toFloat())
          }
        }
      }
    }

  @Test
  fun aPreviewDrawsTheRotatedCroppedFitTheExportWrites() =
    runTest {
      val opener = makeClip(width = OPENER_SIZE.width, height = OPENER_SIZE.height, frames = OPENER_FRAMES)
      val composition =
        Composition(
          tracks =
            listOf(
              Track(
                listOf(
                  Clip(MediaSource.Bytes(opener)),
                  Clip(MediaSource.Bytes(quadrantClip()), effects = listOf(Rotate(QUARTER_TURN))),
                ),
              ),
            ),
          effects = listOf(Scale(SCALED_HEIGHT, Fit.Crop)),
          audio = AudioSpec.Remove,
        )
      val written = exported(composition)
      val slot = written.lastIndex
      val preview = previewOf(composition)
      try {
        val drawn = preview.frameAt(outputSlotTime(slot.toLong(), FRAME_RATE)).shouldNotBeNull()
        for ((x, y) in QUADRANT_PROBES + listOf(EDGE_PROBE to 0.3)) {
          assertReads(
            written[slot].at(x, y),
            drawn.at(x, y),
            "the preview at ($x, $y)",
            Rgb.DEFAULT_TOLERANCE.toFloat(),
          )
        }
      } finally {
        preview.release()
      }
    }

  // A tall turned clip contained in a wide frame leaves bars either side, and the blurred background covers them with
  // the turned frame. The source's edge rows land on the turned frame's sides, so a background scaled or turned from
  // the unturned frame reads another band in the bar.
  @Test
  fun aBlurredFillBehindATurnedClipCoversTheBarsWithTheTurnedFrame() =
    runTest {
      val opener = makeClip(width = OPENER_SIZE.width, height = OPENER_SIZE.height, frames = OPENER_FRAMES)
      val turn = Rotate(QUARTER_TURN)
      val composition =
        Composition(
          tracks =
            listOf(
              Track(
                listOf(
                  Clip(MediaSource.Bytes(opener)),
                  Clip(MediaSource.Bytes(rowBandedClip()), effects = listOf(turn)),
                ),
              ),
            ),
          audio = AudioSpec.Remove,
          fill = Fill.Blurred(radius = FINE_BLUR),
        )
      val written = exported(composition)
      val slot = written.lastIndex
      val drawn = frameAfter(turn, ROW_BANDED_SIZE)
      val (quadW, _) = halfExtents(Fit.Contain, drawn, OPENER_SIZE)
      val (coverW, coverH) = halfExtents(Fit.Crop, drawn, OPENER_SIZE)
      withClue("the turned clip at $quadW of the frame's width leaves no bar at $FILL_PROBES") {
        FILL_PROBES.all { abs(it - 0.5) > quadW / 2 } shouldBe true
      }

      val preview = previewOf(composition)
      try {
        val previewed = preview.frameAt(outputSlotTime(slot.toLong(), FRAME_RATE)).shouldNotBeNull()
        for (x in FILL_PROBES) {
          val expected = bandAt(turned(1, 0.5 + (x - 0.5) / coverW, 0.5 + (FILL_ROW - 0.5) / coverH).second)
          assertReads(expected, written[slot].at(x, FILL_ROW), "the bar at $x", Rgb.DEFAULT_TOLERANCE.toFloat())
          assertReads(
            written[slot].at(x, FILL_ROW),
            previewed.at(x, FILL_ROW),
            "the preview's bar at $x",
            Rgb.DEFAULT_TOLERANCE.toFloat(),
          )
        }
      } finally {
        preview.release()
      }
    }

  // A clip overlay is placed on the clip's own frame, so a composition turn carries it to the turned corner.
  @Test
  fun aClipOverlayUnderACompositionRotationMovesToTheTurnedCorner() =
    runTest {
      val clip =
        makeClip(width = BADGED_SIZE.width, height = BADGED_SIZE.height, frames = OPENER_FRAMES, color = Rgb.Blue)
      val badge = ImageOverlay(BADGE_IMAGE, Corner.TopStart)
      val turn = Rotate(QUARTER_TURN)
      val composition =
        Composition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clip), effects = listOf(badge))))),
          effects = listOf(turn),
          audio = AudioSpec.Remove,
        )
      val written = exported(composition)
      val slot = written.lastIndex
      val frame = written[slot]
      val rect = badge.placedOn(BADGED_SIZE, BADGE_PNG).rectOn(BADGED_SIZE)
      // One counter-clockwise quarter turn carries a source point (x, y) to (y, 1 - x).
      val turnedX = rect.middleY
      val turnedY = 1.0 - rect.middleX

      Size(frame.width, frame.height) shouldBe frameAfter(turn, BADGED_SIZE)
      assertReads(BADGE, frame.at(turnedX, turnedY), "the turned badge", Rgb.DEFAULT_TOLERANCE.toFloat())
      assertReads(
        Rgb.Blue,
        frame.at(rect.middleX, rect.middleY),
        "where it was authored",
        Rgb.DEFAULT_TOLERANCE.toFloat(),
      )

      val preview = previewOf(composition)
      try {
        val previewed = preview.frameAt(outputSlotTime(slot.toLong(), FRAME_RATE)).shouldNotBeNull()
        assertReads(
          frame.at(turnedX, turnedY),
          previewed.at(turnedX, turnedY),
          "the preview's turned badge",
          Rgb.DEFAULT_TOLERANCE.toFloat(),
        )
      } finally {
        preview.release()
      }
    }

  private suspend fun exported(composition: Composition): List<OutputFrame> =
    decodeFrames(outputOf(exportOf(composition, MediaSink.Uri(""))))

  private suspend fun previewOf(composition: Composition) =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    ).resolve(composition, ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None))
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition
      .toBrowserPreview(composition)

  private fun single(
    bytes: ByteArray,
    clipEffects: List<EffectSpec>,
  ): Composition =
    Composition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = clipEffects)))),
      audio = AudioSpec.Remove,
    )
}

private suspend fun quadrantClip(): ByteArray =
  makePixelClip(width = QUADRANT_SIZE.width, height = QUADRANT_SIZE.height, frameRate = FRAME_RATE) { _, x, y ->
    quadrantAt((x + 0.5) / QUADRANT_SIZE.width to (y + 0.5) / QUADRANT_SIZE.height)
  }

private suspend fun bandedClip(): ByteArray =
  makePixelClip(width = BANDED_SIZE.width, height = BANDED_SIZE.height, frameRate = FRAME_RATE) { _, x, _ ->
    bandAt((x + 0.5) / BANDED_SIZE.width)
  }

private suspend fun rowBandedClip(): ByteArray =
  makePixelClip(width = ROW_BANDED_SIZE.width, height = ROW_BANDED_SIZE.height, frameRate = FRAME_RATE) { _, _, y ->
    bandAt((y + 0.5) / ROW_BANDED_SIZE.height)
  }

private fun quadrantAt(point: Pair<Double, Double>): Rgb {
  val (x, y) = point
  return when {
    y < 0.5 && x < 0.5 -> Rgb.Red
    y < 0.5 -> Rgb.Blue
    x < 0.5 -> Rgb.Green
    else -> Rgb.Grey
  }
}

private fun bandAt(x: Double): Rgb =
  when {
    x < EDGE_BAND -> Rgb.Red
    x > 1.0 - EDGE_BAND -> Rgb.Blue
    else -> Rgb.Green
  }

// Where a point of the turned frame was on the source, measured from the top left. One counter-clockwise quarter turn
// brings the source's right edge to the top, so the output's top left came from the source's top right.
private fun turned(
  turns: Int,
  x: Double,
  y: Double,
): Pair<Double, Double> {
  var point = x to y
  repeat(turns) { point = (1.0 - point.second) to point.first }
  return point
}

// Half the drawn quad's width and height as fractions of the output's, under the fit's own scale.
private fun halfExtents(
  fit: Fit,
  drawn: Size,
  output: Size,
): Pair<Double, Double> {
  val scale =
    when (fit) {
      Fit.Contain -> containScale(drawn, output)
      Fit.Crop -> coverScale(drawn, output)
      Fit.Stretch -> return 1.0 to 1.0
    }
  return (drawn.width * scale / output.width).toDouble() to (drawn.height * scale / output.height).toDouble()
}

// The banded clip's color at an output point, or the black fill where the fitted quad does not reach.
private fun fittedAt(
  halfW: Double,
  halfH: Double,
  x: Double,
  y: Double,
): Rgb {
  val sourceX = 0.5 + (x - 0.5) / halfW
  val sourceY = 0.5 + (y - 0.5) / halfH
  return if (sourceX in 0.0..1.0 && sourceY in 0.0..1.0) bandAt(sourceX) else Rgb.Black
}

private val QUADRANT_SIZE = Size(128, 64)
private val BANDED_SIZE = Size(128, 72)
private val OPENER_SIZE = Size(64, 48)
private val TOP_LEFT_QUARTER = NormalizedRect(0f, 0f, 0.5f, 0.5f)
private val QUADRANT_PROBES = listOf(0.25 to 0.25, 0.75 to 0.25, 0.25 to 0.75, 0.75 to 0.75)

private const val QUARTER_TURN = 90
private const val FRAME_RATE = 30
private const val OPENER_FRAMES = 6
private const val SCALED_HEIGHT = 96
private const val EDGE_BAND = 0.1
private const val EDGE_PROBE = 0.03
private const val BAR_PROBE = 0.0625

// Banded the way bandedClip is but down its rows, so a quarter turn lays the bands across the turned frame's width.
private val ROW_BANDED_SIZE = Size(128, 64)

// A sigma of one output pixel, the least sigmaFor gives, so a probe a few pixels inside a band reads that band.
private const val FINE_BLUR = 0.01f

// The middle of the left bar and a point inside each edge band the cover scale lays across the bars.
private val FILL_PROBES = listOf(0.05, 0.2, 0.95)
private const val FILL_ROW = 0.5

private val BADGED_SIZE = Size(256, 128)
private val BADGE_PNG = Size(8, 4)
private val BADGE_IMAGE: ImageSource = ImageSource.ofBytes(OverlayPixels.pngOf(BADGE_PNG, AuthoredColors.MID_GREEN))
private val BADGE = rgbOf(AuthoredColors.MID_GREEN)
