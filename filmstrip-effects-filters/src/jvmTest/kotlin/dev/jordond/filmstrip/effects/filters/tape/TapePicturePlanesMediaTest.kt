package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [TapePictureExpressions]' maps through ffmpeg's own `geq` and reads the planes [TapePicturePlanes] draws against
 * them.
 *
 * The ffmpeg is [hostFfmpeg], the one the build declares as this suite's input.
 */
class TapePicturePlanesMediaTest {
  @Test
  fun `the planes equal the geq's at mid-range ticks and strengths`() {
    listOf(Vhs(), Vhs(intensity = 0.5f), ROLLING_SNOW, EVENT_SNOW).forEach { vhs ->
      val nodes = TapePictureExpressions(vhs.seed, TapePictureParams.of(vhs))
      val inputs =
        tapePictureFragment(vhs, Size(WIDTH, HEIGHT))
          .live
          .shouldNotBeNull()
          .inputs
      val grain = inputs[0]
      val drop = inputs[1]
      TICKS.forEach { tick ->
        withClue("the grain of $vhs at tick $tick") {
          differing(
            grain.planeAt(tick),
            drawn(nodes.grainMap().arguments.associate { it.key to it.value }, tick),
          ) shouldBe
            0
        }
        withClue("the dropouts of $vhs at tick $tick") {
          differing(
            drop.planeAt(tick),
            drawn(nodes.dropMap().arguments.associate { it.key to it.value }, tick),
          ) shouldBe
            0
        }
        if (vhs.tracking != Vhs().tracking) {
          withClue("the snow and its weight of $vhs at tick $tick") {
            val snow = drawn(nodes.snowMap().arguments.associate { it.key to it.value }, tick)
            val weight = drawn(nodes.snowMask().arguments.associate { it.key to it.value }, tick, MASK)
            differing(inputs[2].planeAt(tick), beside(snow, RASTER, weight, MASK)) shouldBe 0
          }
        }
      }
    }
  }

  @Test
  fun `the band of a look that settles up is part way into its fade at the tick the settle is read mid range`() {
    listOf(SETTLING_EVENTS_UP).forEach { vhs ->
      val span = TimeRange(Duration.ZERO)
      val ramp = TapePictureRamp.of(vhs, span).shouldNotBeNull()
      val params = vhs.pictureParams(ramp.levelAt(MID_FADE_TICK), span.start)
      val phase = (MID_FADE_TICK % TapePictureSignal.trackWindowTicks).toDouble() / TapePictureSignal.trackWindowTicks
      val presence = TapePictureSignal.trackingBand(vhs.seed, MID_FADE_TICK, params).strength / sin(PI * phase)

      withClue("the presence of the band of $vhs at tick $MID_FADE_TICK") {
        presence shouldBeGreaterThan 0.0
        presence shouldBeLessThan 1.0
      }
    }
  }

  @Test
  fun `the planes equal the geq's at every tick of a settle and either side of it`() {
    listOf(SETTLING_ROLLING, SETTLING_EVENTS, SETTLING_EVENTS_UP).forEach { vhs ->
      val span = TimeRange(Duration.ZERO)
      val ramp = TapePictureRamp.of(vhs, span).shouldNotBeNull()
      val params = vhs.pictureParams(ramp.peak, span.start)
      val nodes =
        TapePictureExpressions(vhs.seed, params, TapePictureFigures(params, vhs.pictureParams(1.0, span.start), ramp))
      val inputs = tapePictureFragment(vhs, Size(WIDTH, HEIGHT), span = span).live.shouldNotBeNull().inputs
      val ticks =
        listOf(
          0,
          1,
          ramp.endTick / 3,
          ramp.endTick / 2,
          MID_FADE_TICK,
          ramp.endTick - 1,
          ramp.endTick,
          ramp.endTick + 500,
        )

      ticks.forEach { tick ->
        withClue("the grain of $vhs at tick $tick") {
          differing(
            inputs[0].planeAt(tick),
            drawn(nodes.grainMap().arguments.associate { it.key to it.value }, tick),
          ) shouldBe
            0
        }
        withClue("the dropouts of $vhs at tick $tick") {
          differing(
            inputs[1].planeAt(tick),
            drawn(nodes.dropMap().arguments.associate { it.key to it.value }, tick),
          ) shouldBe
            0
        }
        withClue("the snow and its weight of $vhs at tick $tick") {
          val snow = drawn(nodes.snowMap().arguments.associate { it.key to it.value }, tick)
          val weight = drawn(nodes.snowMask().arguments.associate { it.key to it.value }, tick, MASK)
          differing(inputs[2].planeAt(tick), beside(snow, RASTER, weight, MASK)) shouldBe 0
        }
      }
    }
  }

  @Test
  fun `the planes equal the geq's on a hi8 dropout and both read the signal in the middle of its fall`() {
    val hi8 = Hi8(intensity = 0.6f)
    val params = TapePictureParams.of(hi8)
    val nodes = TapePictureExpressions(hi8.seed, params)
    val drop =
      tapePictureFragment(hi8, Size(WIDTH, HEIGHT))
        .live
        .shouldNotBeNull()
        .inputs[1]
    val falling =
      (0 until DROPOUT_TICKS).firstNotNullOf { tick ->
        (0 until HEIGHT).firstNotNullOfOrNull { y ->
          val row = TapePictureSignal.virtualRow(y, HEIGHT)
          (1 until RASTER)
            .firstOrNull { column ->
              val here = TapePictureSignal.dropout(hi8.seed, tick, row, (column + 0.5) / RASTER, params)
              val before = TapePictureSignal.dropout(hi8.seed, tick, row, (column - 0.5) / RASTER, params)
              here in 0.3..0.7 && before > here
            }?.let { column -> Triple(tick, y, column) }
        }
      }
    val (tick, y, column) = falling
    val row = TapePictureSignal.virtualRow(y, HEIGHT)
    val wanted = (0.5 + 255.0 * TapePictureSignal.dropout(hi8.seed, tick, row, (column + 0.5) / RASTER, params)).toInt()

    val plane = drop.planeAt(tick)
    val frame = drawn(nodes.dropMap().arguments.associate { it.key to it.value }, tick)

    differing(plane, frame) shouldBe 0
    withClue("tick $tick row $y column $column") {
      (frame[y * RASTER + column].toInt() and 0xFF) shouldBe wanted
    }
  }

  private fun differing(
    plane: ByteArray,
    frame: ByteArray,
  ): Int {
    plane.size shouldBe frame.size
    return plane.indices.count { plane[it] != frame[it] }
  }

  // Two 4:2:0 frames HEIGHT rows tall as one, [left] then [right] along each row of every plane.
  private fun beside(
    left: ByteArray,
    leftWidth: Int,
    right: ByteArray,
    rightWidth: Int,
  ): ByteArray {
    val planes =
      listOf(Triple(leftWidth, rightWidth, HEIGHT)) + List(2) { Triple(leftWidth / 2, rightWidth / 2, HEIGHT / 2) }
    var leftAt = 0
    var rightAt = 0
    return buildList {
      planes.forEach { (leftRow, rightRow, rows) ->
        repeat(rows) {
          addAll(left.copyOfRange(leftAt, leftAt + leftRow).toList())
          addAll(right.copyOfRange(rightAt, rightAt + rightRow).toList())
          leftAt += leftRow
          rightAt += rightRow
        }
      }
    }.toByteArray()
  }

  private fun drawn(
    planes: Map<String, String>,
    tick: Int,
    width: Int = RASTER,
  ): ByteArray {
    val geq =
      planes.entries.joinToString(
        ":",
      ) { (plane, expression) -> "$plane=${expression.replace(",", "\\,").replace(";", "\\;")}" }
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=${width}x$HEIGHT:d=0.04",
        "-frames:v",
        "1",
        "-/filter:v",
        graphFile("format=yuv420p,settb=1/1000000,setpts=$tick/$TICK_RATE/TB,geq=$geq"),
        "-f",
        "rawvideo",
        "-pix_fmt",
        "yuv420p",
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val frame = process.inputStream.readAllBytes()
    process.waitFor() shouldBe 0
    return frame
  }

  private fun graphFile(graph: String): String =
    java.io.File
      .createTempFile("vhs-planes", ".filtergraph")
      .apply {
        deleteOnExit()
        writeText(graph)
      }.path

  private companion object {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val RASTER = 640
    const val MASK = 2
    const val DROPOUT_TICKS = 200
    val TICKS = listOf(0, 1, 37, 240, 241, 500, 719, 3596, 9001, 20011)
    val ROLLING_SNOW = Vhs(intensity = 0.6f, tracking = Tracking.Rolling(2.5.seconds, height = 0.15f, snow = 0.6f))
    val SETTLE = TapeSettle(1f, 4.seconds, Easing.EaseInOut)
    val SETTLING_ROLLING =
      Vhs(intensity = 0.3f, tracking = Tracking.Rolling(2.5.seconds, height = 0.15f, snow = 0.6f), settle = SETTLE)
    val SETTLING_EVENTS = Vhs(intensity = 0.5f, tracking = Tracking.Events(chance = 1f, snow = 0.4f), settle = SETTLE)
    val SETTLING_EVENTS_UP =
      Vhs(intensity = 1f, tracking = Tracking.Events(chance = 1f, snow = 0.4f), settle = TapeSettle(0.5f, 4.seconds))

    // A tick of a settle up where the band of the default seed is part way in, so the fade arm is read mid range.
    const val MID_FADE_TICK = 160
    val EVENT_SNOW = Vhs(tracking = Tracking.Events(chance = 1f, height = 0.12f, snow = 0.4f))
  }
}
