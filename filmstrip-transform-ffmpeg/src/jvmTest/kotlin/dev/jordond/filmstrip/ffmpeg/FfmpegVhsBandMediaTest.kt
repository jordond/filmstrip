package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.filters.tape.TapePictureParams
import dev.jordond.filmstrip.effects.filters.tape.TapePictureSignal
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.TrackingSettle
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.VHS_HEIGHT
import dev.jordond.filmstrip.ffmpeg.support.VHS_WIDTH
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.ffmpeg.support.lumaPlaneOf
import dev.jordond.filmstrip.ffmpeg.support.rampAt
import dev.jordond.filmstrip.ffmpeg.support.tickOf
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads where ffmpeg puts a rolling [Vhs] band on a clip that opens two seconds into the composition, which counts the
 * band from the clip's first frame, and how strong it is there, on its live planes and on its `geq` graph, against
 * [TapePictureSignal]'s band.
 */
class FfmpegVhsBandMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a band anchored to its clip's slot is at the top on the clip's first frame`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(clip(), clip(listOf(ANCHORED))))
      val topRow = (0 until VIRTUAL_ROWS_READ).maxBy { weightAt(ANCHORED, FIRST_SLOT, it) }

      topRow shouldBe 0
      listOf(false, true).forEach { live ->
        val planes = draw.formPlanes(composition, LAST_SLOT + 1, live = live)
        SLOTS.forEach { slot ->
          withClue("live $live slot $slot") { assertBandReads(frameOf(planes, slot), ANCHORED, slot) }
        }
      }
    }

  @Test
  fun `the band on a clip that opens mid composition moves with its anchor`() =
    runBlocking<Unit> {
      val anchored = draw.composition(listOf(clip(), clip(listOf(ANCHORED))))
      val plain = draw.composition(listOf(clip(), clip(listOf(UNANCHORED))))

      val first = frameOf(draw.formPlanes(anchored, FIRST_SLOT + 1, live = false), FIRST_SLOT)
      val second = frameOf(draw.formPlanes(plain, FIRST_SLOT + 1, live = false), FIRST_SLOT)

      val differing =
        (0 until VHS_HEIGHT).count { y ->
          abs(first.luma(VHS_WIDTH / 2, y) - second.luma(VHS_WIDTH / 2, y)) >
            8
        }
      differing shouldBeGreaterThan VHS_HEIGHT / 10
    }

  @Test
  fun `a band set to be at the top a second in reads the signal on that frame and on the first`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(clip(), clip(listOf(UNANCHORED))))
      val top = (0 until VIRTUAL_ROWS_READ).maxBy { weightAt(UNANCHORED, FIRST_SLOT + FILTER_RATE, it) }

      top shouldBe 0
      listOf(false, true).forEach { live ->
        val planes = draw.formPlanes(composition, FIRST_SLOT + FILTER_RATE + 1, live = live)
        listOf(FIRST_SLOT, FIRST_SLOT + FILTER_RATE).forEach { slot ->
          withClue("live $live slot $slot") { assertBandReads(frameOf(planes, slot), UNANCHORED, slot) }
        }
      }
    }

  @Test
  fun `a band that settles reads the signal at the start the middle and the end of its settle and after it`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(clip(), clip(listOf(SETTLING))))

      listOf(false, true).forEach { live ->
        val planes = draw.formPlanes(composition, AFTER_SLOT + 1, live = live)
        SETTLE_SLOTS.forEach { slot ->
          val strength = TapePictureSignal.bandSettle(tickOf(slot), TapePictureParams.of(SETTLING, OPENS))
          withClue("live $live slot $slot strength $strength") {
            assertBandReads(frameOf(planes, slot), SETTLING, slot, rowsOf = SETTLING_FULL)
          }
        }
      }
    }

  @Test
  fun `a band that has settled is gone and a second pass of it draws nothing`() =
    runBlocking<Unit> {
      val composition = draw.composition(listOf(clip(), clip(listOf(SETTLING))))
      val gone = draw.composition(listOf(clip(), clip(listOf(Vhs(tracking = Tracking.Off)))))

      val settled = frameOf(draw.formPlanes(composition, AFTER_SLOT + 1, live = false), AFTER_SLOT)
      val bare = frameOf(draw.formPlanes(gone, AFTER_SLOT + 1, live = false), AFTER_SLOT)

      TapePictureSignal.bandSettle(tickOf(AFTER_SLOT), TapePictureParams.of(SETTLING, OPENS)) shouldBe 0.0
      (0 until VHS_HEIGHT).forEach { y ->
        COLUMNS.forEach { column ->
          withClue("row $y column $column") { abs(settled.luma(column, y) - bare.luma(column, y)) shouldBeLessThan 2 }
        }
      }
    }

  private fun assertBandReads(
    frame: Yuv420Frame,
    look: Vhs,
    slot: Int,
    rowsOf: Vhs = look,
  ) {
    val rows =
      (0 until VHS_HEIGHT).filter {
        weightAt(rowsOf, slot, TapePictureSignal.virtualRow(it, VHS_HEIGHT)) > MID_BAND
      }
    rows.size shouldBeGreaterThan VHS_HEIGHT / 10
    rows.forEach { y ->
      COLUMNS.forEach { column ->
        val want =
          lumaPlaneOf(look, tickOf(slot), column, y, ::rampAt, VHS_WIDTH, VHS_HEIGHT, TapePictureParams.of(look, OPENS))
        withClue("row $y column $column") { abs(frame.luma(column, y) - want) shouldBeLessThan LUMA_CODES }
      }
    }
  }

  private fun weightAt(
    look: Vhs,
    slot: Int,
    row: Int,
  ) = TapePictureSignal.trackingWeight(look.seed, tickOf(slot), row, TapePictureParams.of(look, OPENS))

  private fun clip(effects: List<Vhs> = emptyList()) = draw.clipOf(VHS_WIDTH, VHS_HEIGHT, ::rampAt, effects)

  private fun frameOf(
    frames: ByteArray,
    slot: Int,
  ): Yuv420Frame {
    val bytes = VHS_WIDTH * VHS_HEIGHT * 3 / 2
    return Yuv420Frame(VHS_WIDTH, VHS_HEIGHT, frames.copyOfRange(bytes * slot, bytes * (slot + 1)))
  }

  private companion object {
    const val VIRTUAL_ROWS_READ = 480
    const val FIRST_SLOT = 2 * FILTER_RATE
    const val LAST_SLOT = FIRST_SLOT + FILTER_RATE
    const val MID_BAND = 0.5
    const val LUMA_CODES = 4.0
    val SLOTS = listOf(FIRST_SLOT, FIRST_SLOT + FILTER_RATE / 4, LAST_SLOT)
    val COLUMNS = 16 until VHS_WIDTH - 16 step 24

    // A band at the top on the clip's first frame that falls to nothing over one second, 30 frames, with the same band
    // that never settles beside it to pick the rows the band sits on.
    const val AFTER_SLOT = FIRST_SLOT + FILTER_RATE * 3 / 2
    val SETTLE_SLOTS =
      listOf(
        FIRST_SLOT,
        FIRST_SLOT + FILTER_RATE / 4,
        FIRST_SLOT + FILTER_RATE / 2,
        FIRST_SLOT + FILTER_RATE,
        AFTER_SLOT,
      )
    val SETTLING =
      Vhs(
        tracking =
          Tracking.Rolling(
            3.seconds,
            height = 0.3f,
            snow = 0.5f,
            settle = TrackingSettle(1.seconds, Easing.Linear),
          ),
      )
    val SETTLING_FULL = Vhs(tracking = Tracking.Rolling(3.seconds, height = 0.3f, snow = 0.5f))
    val ANCHORED = Vhs(tracking = Tracking.Rolling(3.seconds, height = 0.3f, snow = 0.5f))
    val UNANCHORED = Vhs(tracking = Tracking.Rolling(3.seconds, height = 0.3f, snow = 0.5f, at = 1.seconds))

    // The clip's slot opens here, which the band counts from.
    val OPENS = 2.seconds
  }
}
