package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Color
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.turnedFrame
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test

/**
 * A rotation that is not a quarter turn, drawn by media3 onto the frame the planner sized for it.
 *
 * Every expected pixel is read off an export of the same clip with no rotation, at the point a counter-clockwise turn
 * about the centre carries there, so the encoder and the 4:2:0 round trip sit on both sides of the comparison. A turn
 * the wrong way, about the wrong point or onto a frame shrunk or cut to fit reads another part of the picture.
 */
class AndroidRotateTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aThirtyDegreeTurnDrawsTheBoundingBoxWithBlackCorners() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture()
      val rotate = Rotate(THIRTY)
      val sourceSize =
        probe
          .infoOf(source)
          .video
          .shouldNotBeNull()
          .displaySize
      val frame = rotate.turnedFrame(sourceSize)

      val plan = probe.plan(composition(source, rotate), ExportSpec())
      plan.output.size shouldBe frame

      val plain = probe.frame(probe.export(composition(source, null), ExportSpec()))
      val turned = probe.frame(probe.export(plan))
      withClue("the turned export decoded at ${turned.width}x${turned.height}") {
        Size(turned.width, turned.height) shouldBe frame
      }

      val turn = Turn(THIRTY, sourceSize, frame)
      assertReadsPlain(turned, plain, turn, Point(frame.width / 2.0, frame.height / 2.0), "the centre")
      val inner = turn.flatPoints(plain).shouldNotBeNull()
      inner.forEach { assertReadsPlain(turned, plain, turn, it, "a point toward the edge") }

      corners(frame).forEach { corner ->
        withClue("the corner at $corner maps to ${turn.sourceOf(corner)}, which has to fall outside $sourceSize") {
          turn.inside(corner, margin = 0) shouldBe false
        }
        val read = turned.patch(corner)
        withClue("the corner at $corner outside the turned picture read $read") {
          (read.first <= BLACK && read.second <= BLACK && read.third <= BLACK) shouldBe true
        }
      }
    }

  private fun composition(
    source: MediaSource,
    rotate: Rotate?,
  ): EditComposition = EditComposition(listOf(Track(listOf(Clip(source)))), listOfNotNull(rotate))

  private fun assertReadsPlain(
    turned: Bitmap,
    plain: Bitmap,
    turn: Turn,
    point: Point,
    name: String,
  ) {
    val drawn = turned.patch(point)
    val expected = plain.patch(turn.sourceOf(point))
    withClue("$name at $point read $drawn, and the unturned export reads $expected at ${turn.sourceOf(point)}") {
      (distance(drawn, expected) <= DRIFT) shouldBe true
    }
  }

  // A patch a few pixels in from each corner, so the read is the corner's own pixels rather than the encoder's edge.
  private fun corners(frame: Size): List<Point> {
    val near = EDGE.toDouble()
    val farX = (frame.width - EDGE - SIDE).toDouble()
    val farY = (frame.height - EDGE - SIDE).toDouble()
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

    // Points on a grid over the frame's lower right quarter, nearest the corner first, that the turn carries inside
    // the source and whose source pixels are flat around them, so the resampling a turn does cannot move what they
    // read. The first two are enough to tell a frame shrunk to fit from one drawn at the source's own scale.
    fun flatPoints(plain: Bitmap): List<Point>? =
      (STEPS downTo 1)
        .flatMap { row ->
          (STEPS downTo 1).map { column ->
            Point(frame.width / 2.0 * (1 + column / (STEPS + 1.0)), frame.height / 2.0 * (1 + row / (STEPS + 1.0)))
          }
        }.filter { inside(it, margin = FLAT) && plain.isFlatAround(sourceOf(it)) }
        .take(2)
        .takeIf { it.isNotEmpty() }
  }

  private companion object {
    const val THIRTY = 30
    const val HALF_TURN = 180.0
    const val SIDE = 8
    const val EDGE = 4
    const val FLAT = 12
    const val STEPS = 8

    // Summed across the three channels, for the two encodes behind the comparison and the turn's own resampling.
    const val DRIFT = 36

    // The encoder's black, with room for the 4:2:0 round trip at the edge of the picture.
    const val BLACK = 24

    fun Bitmap.patch(at: Point): Triple<Int, Int, Int> {
      val left = (at.x.roundToInt() - SIDE / 2).coerceIn(0, width - SIDE)
      val top = (at.y.roundToInt() - SIDE / 2).coerceIn(0, height - SIDE)
      var red = 0
      var green = 0
      var blue = 0
      for (x in left until left + SIDE) {
        for (y in top until top + SIDE) {
          val pixel = getPixel(x, y)
          red += Color.red(pixel)
          green += Color.green(pixel)
          blue += Color.blue(pixel)
        }
      }
      val sampled = SIDE * SIDE
      return Triple(red / sampled, green / sampled, blue / sampled)
    }

    fun Bitmap.isFlatAround(at: Point): Boolean {
      val middle = patch(at)
      return listOf(
        Point(at.x - FLAT, at.y),
        Point(at.x + FLAT, at.y),
        Point(at.x, at.y - FLAT),
        Point(at.x, at.y + FLAT),
      ).all { distance(patch(it), middle) <= DRIFT }
    }
  }
}
