package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.turnedFrame
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.CHANNEL_DRIFT
import dev.jordond.filmstrip.ffmpeg.support.regionAverage
import dev.jordond.filmstrip.ffmpeg.support.sourceSize
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Duration

/**
 * A rotation that is not a quarter turn, drawn by ffmpeg onto the frame the planner sized for it.
 *
 * Every expected pixel is the source's own, read at the point a counter-clockwise turn about the centre carries there,
 * so a turn the wrong way, about the wrong point or onto the wrong frame reads another part of the picture.
 */
class FfmpegRotateMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a thirty degree turn draws the picture turned onto the planned frame`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      assertTurned(THIRTY)
    }

  @Test
  fun `a forty five degree turn draws the picture turned onto the planned frame`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      assertTurned(FORTY_FIVE)
    }

  private suspend fun assertTurned(degrees: Int) {
    val source = fixture.landscape
    val sourceSize = fixture.sourceSize(source)
    val rotate = Rotate(degrees)
    val (plan, output) = exported(rotate, "rotate-$degrees")
    val frame = rotate.turnedFrame(sourceSize)

    plan.output.size shouldBe frame
    fixture.sourceSize(output) shouldBe frame

    val turn = Turn(degrees, sourceSize, frame)
    val centre = Point(frame.width / 2.0, frame.height / 2.0)
    assertReadsSource(output, source, turn, centre, "the centre")

    val inner = innerPoint(source, turn).shouldNotBeNull()
    assertReadsSource(output, source, turn, inner, "a point toward the corner")

    // The top left corner of the frame lies outside the turned picture, so nothing of the source reaches it.
    val corner = Point(PROBE.toDouble(), PROBE.toDouble())
    withClue("the corner maps to ${turn.sourceOf(corner)}, which has to fall outside $sourceSize") {
      turn.inside(corner, margin = 0) shouldBe false
    }
    val read = regionAverage(output.absolutePath, corner.x.toInt(), corner.y.toInt(), PROBE, Duration.ZERO)
    withClue("the corner outside the turned picture read $read") {
      read.toList().all { it <= CHANNEL_DRIFT }.shouldBeTrue()
    }
    output.delete()
  }

  // The first point of a grid over the frame's lower right quarter, nearest the corner first, that the turn carries
  // inside the source and whose source pixels are flat around it, so the resampling a turn does cannot move what it
  // reads.
  private fun innerPoint(
    source: File,
    turn: Turn,
  ): Point? {
    val candidates =
      (STEPS downTo 1).flatMap { row ->
        (STEPS downTo 1).map { column ->
          Point(
            turn.frame.width / 2.0 * (1 + column / (STEPS + 1.0)),
            turn.frame.height / 2.0 * (1 + row / (STEPS + 1.0)),
          )
        }
      }
    return candidates.firstOrNull { point -> turn.inside(point, margin = FLAT) && isFlat(source, turn.sourceOf(point)) }
  }

  private fun isFlat(
    source: File,
    at: Point,
  ): Boolean {
    val middle = read(source, at)
    return listOf(
      Point(at.x - FLAT, at.y),
      Point(at.x + FLAT, at.y),
      Point(at.x, at.y - FLAT),
      Point(at.x, at.y + FLAT),
    ).all { near(read(source, it), middle) }
  }

  private fun assertReadsSource(
    output: File,
    source: File,
    turn: Turn,
    point: Point,
    name: String,
  ) {
    val drawn = read(output, point)
    val expected = read(source, turn.sourceOf(point))
    withClue("$name at $point read $drawn, and the source reads $expected at ${turn.sourceOf(point)}") {
      near(drawn, expected).shouldBeTrue()
    }
  }

  private fun read(
    file: File,
    at: Point,
  ): Triple<Double, Double, Double> =
    regionAverage(file.absolutePath, at.x.roundToInt() - PROBE / 2, at.y.roundToInt() - PROBE / 2, PROBE, Duration.ZERO)

  private fun near(
    a: Triple<Double, Double, Double>,
    b: Triple<Double, Double, Double>,
  ): Boolean = a.toList().zip(b.toList()).all { (x, y) -> abs(x - y) <= CHANNEL_DRIFT }

  private suspend fun exported(
    rotate: Rotate,
    name: String,
  ): Pair<ExportPlan, File> {
    val output = File.createTempFile("filmstrip-$name", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        clip(MediaSource.of(fixture.landscape.absolutePath))
        effects { add(rotate) }
      }
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, ExportSpec())) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }
    val finished =
      fixture.filmstrip
        .export(plan, MediaSink.of(output.absolutePath))
        .toList()
        .last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    finished.shouldBeInstanceOf<ExportStatus.Success>()
    return plan to output
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
  }

  private companion object {
    const val THIRTY = 30
    const val FORTY_FIVE = 45
    const val HALF_TURN = 180.0
    const val PROBE = 8
    const val FLAT = 12
    const val STEPS = 8
  }
}
