@file:OptIn(BetaInteropApi::class)

package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.tape.TAPE_PICTURE_KERNEL_LIMIT
import dev.jordond.filmstrip.effects.filters.tape.TapeLook
import dev.jordond.filmstrip.effects.filters.tape.TapePictureFrame
import dev.jordond.filmstrip.effects.filters.tape.TapePictureParams
import dev.jordond.filmstrip.effects.filters.tape.TapePictureRamp
import dev.jordond.filmstrip.effects.filters.tape.TapePictureSignal
import dev.jordond.filmstrip.effects.filters.tape.TapeSettle
import dev.jordond.filmstrip.effects.filters.tape.tapePictureKernelLimit
import dev.jordond.filmstrip.effects.filters.tape.tapePictureResolution
import dev.jordond.filmstrip.effects.filters.tape.tapePictureStep
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBA8
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads what a tape look draws through the Core Image kernel while its band rolls from, and its strength settles from,
 * the first frame of a clip that opens late, against [TapePictureSignal].
 */
@OptIn(ExperimentalForeignApi::class)
class VhsSettleCoreImageHardwareTest {
  @Test
  fun `a rolling band on a clip that opens late is at the top on its first frame`() {
    val weight = { seconds: Double, y: Int ->
      TapePictureSignal.trackingWeight(
        ANCHORED.seed,
        TapePictureSignal.tickAt(seconds),
        TapePictureSignal.virtualRow(y, HEIGHT),
        TapePictureParams.of(ANCHORED, SLOT.start),
      )
    }

    weight(OPENS, 2) shouldBeGreaterThan 0.5
    weight(OPENS, HEIGHT - 3) shouldBeGreaterThan 0.5
    weight(OPENS + ANCHORED_PERIOD / 4, HEIGHT / 4) shouldBeGreaterThan 0.5
    listOf(OPENS, OPENS + ANCHORED_PERIOD / 4).forEach { seconds ->
      val frame = drawn(ANCHORED, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT, seconds, SLOT)
      listOf(2, HEIGHT / 4, HEIGHT / 2, HEIGHT - 3).forEach { y ->
        assertRowMatches(frame, ANCHORED, y, HEIGHT, WIDTH, ::rampTexel, seconds = seconds, span = SLOT)
      }
    }
  }

  @Test
  fun `a rolling band set to be at the top two seconds in is there on that frame and not on the first`() {
    val params = TapePictureParams.of(ANCHORED_AFTER, SLOT.start)
    val weight = { seconds: Double, y: Int ->
      TapePictureSignal.trackingWeight(
        ANCHORED_AFTER.seed,
        TapePictureSignal.tickAt(seconds),
        TapePictureSignal.virtualRow(y, HEIGHT),
        params,
      )
    }

    weight(OPENS + ANCHOR_AT, 2) shouldBeGreaterThan 0.5
    weight(OPENS, 2) shouldBeLessThan 0.5
    listOf(OPENS, OPENS + ANCHOR_AT).forEach { seconds ->
      val frame = drawn(ANCHORED_AFTER, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT, seconds, SLOT)
      listOf(2, HEIGHT / 4, HEIGHT / 2, HEIGHT - 3).forEach { y ->
        assertRowMatches(frame, ANCHORED_AFTER, y, HEIGHT, WIDTH, ::rampTexel, seconds = seconds, span = SLOT)
      }
    }
  }

  @Test
  fun `a settling band reads the signal at the start the middle and the end of its settle and after it`() {
    val params = TapePictureParams.of(SETTLING, SLOT.start)
    val strengths =
      SETTLE_READS.map { (seconds, y) ->
        val frame = drawn(SETTLING, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT, seconds, SLOT)
        assertRowMatches(frame, SETTLING, y, HEIGHT, WIDTH, ::rampTexel, seconds = seconds, span = SLOT)
        TapePictureSignal.bandSettle(TapePictureSignal.tickAt(seconds), params)
      }

    strengths[0] shouldBe 1.0
    strengths[1] shouldBe (0.5 plusOrMinus 0.01)
    strengths[2] shouldBe 0.0
    strengths[3] shouldBe 0.0
  }

  @Test
  fun `a settling look reads the signal before at the start in the middle at the end and after its settle`() {
    val lock = TapePictureRamp.of(LOCKING, SLOT).shouldNotBeNull()
    val levels =
      LOCK_READS.map { seconds ->
        val frame = drawn(LOCKING, ramp(WIDTH, HEIGHT), WIDTH, HEIGHT, seconds, SLOT)
        READ_ROWS.forEach { y ->
          assertRowMatches(frame, LOCKING, y, HEIGHT, WIDTH, ::rampTexel, seconds = seconds, span = SLOT)
        }
        lock.levelAt(TapePictureSignal.tickAt(seconds))
      }

    levels[0] shouldBe 1.0
    levels[1] shouldBe 1.0
    levels[2] shouldBe (0.65 plusOrMinus 0.01)
    levels[3] shouldBe 0.3.toFloat().toDouble()
    levels[4] shouldBe 0.3.toFloat().toDouble()
  }

  // The resolver's own effect on a clip that opens late, with the band and the settle both left at their defaults,
  // which count from the clip's first frame wherever it opens.
  @Test
  fun `the resolver draws a clip that opens late from its own first frame`() {
    val step =
      tapePictureResolution(CLIP_SETTLING, null)
        .shouldBeInstanceOf<EffectResolution.Resolved>()
        .effect.step
    val first = TapePictureFrame.of(CLIP_SETTLING, OPENS, SLOT, null)

    TapePictureSignal.trackingWeight(CLIP_SETTLING.seed, first.tick, 2, first.params) shouldBeGreaterThan 0.5
    TapePictureRamp.of(CLIP_SETTLING, SLOT).shouldNotBeNull().levelAt(first.tick) shouldBe 1.0
    listOf(OPENS to 2, OPENS + 2.0 to HEIGHT / 4, OPENS + 9.0 to HEIGHT / 2).forEach { (seconds, y) ->
      val frame =
        rendered(
          step.apply(ramp(WIDTH, HEIGHT), FrameInfo(attributes(WIDTH, HEIGHT, SLOT), seconds.seconds)),
          WIDTH,
          HEIGHT,
        )

      assertRowMatches(frame, CLIP_SETTLING, y, HEIGHT, WIDTH, ::rampTexel, seconds = seconds, span = SLOT)
    }
  }

  private companion object {
    // A look that opens at OPENS on the composition's clock at full strength and settles to 0.3 over four seconds.
    val LOCKING = Vhs(intensity = 0.3f, settle = TapeSettle(1f, 4.seconds, Easing.Linear))
    val LOCK_READS = listOf(OPENS - 1.0, OPENS, OPENS + 2.0, OPENS + 4.0, OPENS + 9.0)
    const val OPENS = 10.0
    const val ANCHORED_PERIOD = 8.0
    val CLIP_SETTLING =
      Vhs(
        intensity = 0.3f,
        tracking =
          Tracking.Rolling(
            ANCHORED_PERIOD.seconds,
            height = 0.3f,
            snow = 0.5f,
            settle = TrackingSettle(4.seconds),
          ),
        settle = TapeSettle(1f, 4.seconds, Easing.Linear),
      )
    val SLOT = TimeRange(OPENS.seconds, 60.seconds)
    val ANCHORED = Vhs(tracking = Tracking.Rolling(ANCHORED_PERIOD.seconds, height = 0.3f, snow = 0.5f))
    const val ANCHOR_AT = 2.0
    val ANCHORED_AFTER =
      Vhs(tracking = Tracking.Rolling(ANCHORED_PERIOD.seconds, height = 0.3f, snow = 0.5f, at = ANCHOR_AT.seconds))

    // The same band falling to nothing over four seconds, read at its start, its middle, its end and after it, each on
    // a row the band is on.
    val SETTLING =
      Vhs(
        tracking =
          Tracking.Rolling(
            ANCHORED_PERIOD.seconds,
            height = 0.3f,
            snow = 0.5f,
            settle = TrackingSettle(4.seconds, Easing.Linear),
          ),
      )
    val SETTLE_READS =
      listOf(
        OPENS to 2,
        OPENS + 2.0 to HEIGHT / 4,
        OPENS + 4.0 to HEIGHT / 2,
        OPENS + 6.0 to HEIGHT * 3 / 4,
      )
  }
}
