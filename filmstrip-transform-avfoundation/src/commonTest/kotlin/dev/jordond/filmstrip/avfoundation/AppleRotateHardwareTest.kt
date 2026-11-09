package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.effects.geometry.turnedFrame
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A rotation that is not a quarter turn, drawn by Core Image onto the frame the planner sized for it.
 *
 * Every expected pixel is read off a quarter turn of the same clip, at the point the source pixel behind it lands
 * there, so the Core Image pass, the encoder and the 4:2:0 round trip sit on both sides of the comparison. A turn the
 * wrong way, about the wrong point or onto a frame shrunk or cut to fit reads another part of the picture.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleRotateHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a thirty degree turn draws the bounding box with black corners`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture(SOURCE)
      val rotate = Rotate(THIRTY)
      val quarter = exported(composition(source, Rotate(QUARTER_TURN)), "rotate-quarter")
      val sourceSize = Size(quarter.height, quarter.width)
      val frame = rotate.turnedFrame(sourceSize)

      val composition = composition(source, rotate)
      planned(composition) shouldBe frame
      val turned = exported(composition, "rotate-free")
      withClue("the turned export decoded at ${turned.width}x${turned.height}") {
        Size(turned.width, turned.height) shouldBe frame
      }

      val turn = Turn(THIRTY, sourceSize, frame)
      val flat = turn.flatPoints(quarter).shouldNotBeEmpty()
      assertReadsQuarter(turned, quarter, turn, flat.minBy { turn.fromCentre(it) }, "the point nearest the centre")
      assertReadsQuarter(turned, quarter, turn, flat.maxBy { turn.fromCentre(it) }, "the point nearest the edge")

      corners(frame).forEach { corner ->
        withClue("the corner at $corner maps to ${turn.sourceOf(corner)}, which has to fall outside $sourceSize") {
          turn.inside(corner, margin = 0) shouldBe false
        }
        val read = turned.read(corner)
        withClue("the corner at $corner outside the turned picture read $read") {
          (read.first <= BLACK && read.second <= BLACK && read.third <= BLACK).shouldBeTrue()
        }
      }
    }

  private fun composition(
    source: String,
    rotate: Rotate?,
  ): Composition =
    compositionOf {
      clip(MediaSource.of(source)) { trim(0.seconds, 1.seconds) }
      if (rotate != null) effects { rotate(rotate.degrees) }
    }

  private suspend fun planned(composition: Composition): Size =
    when (val verdict = filmstrip.plan(composition, ExportSpec())) {
      is Verdict.Capable -> verdict.plan.output.size
      is Verdict.Degraded -> verdict.plan.output.size
      is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
    }

  private suspend fun exported(
    composition: Composition,
    name: String,
  ): FrameProbe {
    val output = NSTemporaryDirectory() + "filmstrip-apple-$name.mp4"
    withContext(Dispatchers.Default) {
      val statuses = filmstrip.export(composition, ExportSpec(), MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
      }
    }
    return frameOf(output).also { NSFileManager.defaultManager.removeItemAtPath(output, null) }
  }

  private fun assertReadsQuarter(
    turned: FrameProbe,
    quarter: FrameProbe,
    turn: Turn,
    point: Point,
    name: String,
  ) {
    val drawn = turned.read(point)
    val expected = quarter.read(turn.quarterOf(point))
    withClue("$name at $point read $drawn, and the quarter turn reads $expected at ${turn.quarterOf(point)}") {
      (distance(drawn, expected) <= DRIFT).shouldBeTrue()
    }
  }

  // A patch a few pixels in from each corner, so the read is the corner's own pixels rather than the encoder's edge.
  private fun corners(frame: Size): List<Point> {
    val near = EDGE.toDouble()
    val farX = (frame.width - EDGE).toDouble()
    val farY = (frame.height - EDGE).toDouble()
    return listOf(Point(near, near), Point(farX, near), Point(near, farY), Point(farX, farY))
  }

  private data class Point(
    val x: Double,
    val y: Double,
  )

  // A counter-clockwise turn about the centre, on a frame whose y axis points down. A point the turn carries to
  // (u', v') from the frame's centre came from (u' cos - v' sin, u' sin + v' cos) from the source's.
  private class Turn(
    degrees: Int,
    val source: Size,
    val frame: Size,
  ) {
    private val radians = degrees * PI / HALF_TURN

    fun sourceOf(point: Point): Point {
      val u = point.x - frame.width / 2.0
      val v = point.y - frame.height / 2.0
      return Point(
        u * cos(radians) - v * sin(radians) + source.width / 2.0,
        u * sin(radians) + v * cos(radians) + source.height / 2.0,
      )
    }

    fun inside(
      point: Point,
      margin: Int,
    ): Boolean {
      val at = sourceOf(point)
      return at.x in margin.toDouble()..(source.width - margin).toDouble() &&
        at.y in margin.toDouble()..(source.height - margin).toDouble()
    }

    // Where the source pixel behind point lands under a quarter turn counter-clockwise, which carries (x, y) to
    // (y, width - x).
    fun quarterOf(point: Point): Point {
      val at = sourceOf(point)
      return Point(at.y, source.width - at.x)
    }

    fun fromCentre(point: Point): Double = hypot(point.x - frame.width / 2.0, point.y - frame.height / 2.0)

    // Points on a grid over the frame that the turn carries inside the source and whose source pixels are flat around
    // them. The pattern's bars meet in hard edges, and a turn resamples an edge in whatever light its backend works
    // in, so only a flat point reads the same either side of it.
    fun flatPoints(quarter: FrameProbe): List<Point> =
      (1..STEPS)
        .flatMap { row ->
          (1..STEPS).map { column ->
            Point(frame.width * column / (STEPS + 1.0), frame.height * row / (STEPS + 1.0))
          }
        }.filter { inside(it, margin = FLAT) && quarter.isFlatAround(quarterOf(it)) }
  }

  private companion object {
    const val SOURCE = "apple_export_a.mp4"
    val TIMEOUT = 2.seconds * 60
    const val THIRTY = 30
    const val QUARTER_TURN = 90
    const val HALF_TURN = 180.0
    const val EDGE = 4
    const val FLAT = 12
    const val STEPS = 12

    // Summed across the three channels, for the two encodes behind the comparison and the turn's own resampling.
    const val DRIFT = 36

    // How far the source may move across a point's neighbourhood for that point to count as flat.
    const val FLAT_DRIFT = 12

    // The encoder's black, with room for the 4:2:0 round trip at the edge of the picture.
    const val BLACK = 24

    fun FrameProbe.read(at: Point): Triple<Int, Int, Int> = average((at.x / width).toFloat(), (at.y / height).toFloat())

    fun FrameProbe.isFlatAround(at: Point): Boolean {
      val middle = read(at)
      return (-1..1).flatMap { dx -> (-1..1).map { dy -> Point(at.x + dx * FLAT, at.y + dy * FLAT) } }.all {
        distance(read(it), middle) <= FLAT_DRIFT
      }
    }
  }
}
