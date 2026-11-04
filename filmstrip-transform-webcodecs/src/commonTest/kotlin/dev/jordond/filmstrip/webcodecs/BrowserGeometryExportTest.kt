package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
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
import kotlin.test.Test

/**
 * Rotate and Scale drawn through a real browser export and read back out of the written file.
 *
 * Every frame size comes from `frameAfter` and every fitted edge from `containScale` or `coverScale`, so a case
 * measures the compositor against the shared geometry rather than against a number of its own. The fixtures carry a
 * different color in each quadrant or band, so a frame turned or fitted the wrong way reads a different color.
 */
class BrowserGeometryExportTest {
  @Test
  fun eachQuarterTurnMovesTheQuadrantsCounterClockwise() =
    runTest {
      for (degrees in listOf(90, 180, 270)) {
        val frame = exported(single(quadrantClip(), clipEffects = listOf(Rotate(degrees)))).last()
        val turns = degrees / QUARTER_TURN

        withClue("Rotate($degrees)") {
          Size(frame.width, frame.height) shouldBe frameAfter(Rotate(degrees), QUADRANT_SIZE)
          for ((x, y) in QUADRANT_PROBES) {
            assertReads(quadrantAt(turned(turns, x, y)), frame.at(x, y), "($x, $y)")
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
        assertReads(quadrantAt(turned(1, turnedX, turnedY)), frame.at(x, y), "($x, $y)")
      }
    }

  @Test
  fun aCompositionRotationTurnsEveryClip() =
    runTest {
      val bytes = quadrantClip()
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes)), Clip(MediaSource.Bytes(bytes))))),
          effects = listOf(Rotate(QUARTER_TURN)),
          audio = AudioSpec.Remove,
        )
      val frames = exported(composition)

      for ((label, frame) in listOf("the first clip" to frames.first(), "the second clip" to frames.last())) {
        withClue(label) {
          Size(frame.width, frame.height) shouldBe frameAfter(Rotate(QUARTER_TURN), QUADRANT_SIZE)
          for ((x, y) in QUADRANT_PROBES) {
            assertReads(quadrantAt(turned(1, x, y)), frame.at(x, y), "($x, $y)")
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
          EditComposition(
            tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(opener)), Clip(MediaSource.Bytes(wide))))),
            effects = listOf(scale),
            audio = AudioSpec.Remove,
          )
        val frame = exported(composition).last()
        val output = frameAfter(scale, OPENER_SIZE)

        withClue("$fit") {
          Size(frame.width, frame.height) shouldBe output
          val (halfW, halfH) = halfExtents(fit, BANDED_SIZE, output)
          for ((x, y) in listOf(EDGE_PROBE to 0.5, 0.5 to BAR_PROBE, 0.5 to 0.5)) {
            assertReads(fittedAt(halfW, halfH, x, y), frame.at(x, y), "($x, $y)")
          }
        }
      }
    }

  @Test
  fun aPreviewDrawsTheRotatedCroppedFitTheExportWrites() =
    runTest {
      val opener = makeClip(width = OPENER_SIZE.width, height = OPENER_SIZE.height, frames = OPENER_FRAMES)
      val composition =
        EditComposition(
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
          assertReads(written[slot].at(x, y), drawn.at(x, y), "the preview at ($x, $y)")
        }
      } finally {
        preview.release()
      }
    }

  private suspend fun exported(composition: EditComposition): List<OutputFrame> =
    decodeFrames(outputOf(exportOf(composition, MediaSink.Uri(""))))

  private suspend fun previewOf(composition: EditComposition) =
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
  ): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = clipEffects)))),
      audio = AudioSpec.Remove,
    )

  private fun assertReads(
    expected: Rgb,
    read: Rgb,
    where: String,
  ) {
    withClue("$where read $read, and $expected was expected") { read.isNear(expected) shouldBe true }
  }
}

private suspend fun quadrantClip(): ByteArray =
  makePixelClip(width = QUADRANT_SIZE.width, height = QUADRANT_SIZE.height, frameRate = FRAME_RATE) { _, x, y ->
    quadrantAt((x + 0.5) / QUADRANT_SIZE.width to (y + 0.5) / QUADRANT_SIZE.height)
  }

private suspend fun bandedClip(): ByteArray =
  makePixelClip(width = BANDED_SIZE.width, height = BANDED_SIZE.height, frameRate = FRAME_RATE) { _, x, _ ->
    bandAt((x + 0.5) / BANDED_SIZE.width)
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
