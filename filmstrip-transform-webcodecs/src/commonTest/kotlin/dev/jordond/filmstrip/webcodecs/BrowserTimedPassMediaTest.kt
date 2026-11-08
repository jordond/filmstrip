package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A pass whose texture matrix moves with composition time, drawn through a real export and read back.
 *
 * The passes here come from a resolver of the test's own, so what is measured is the compositor evaluating a moving
 * matrix per frame and keeping it in its place in the chain, not any one built-in effect.
 */
class BrowserTimedPassMediaTest {
  @Test
  fun aTimedPassSamplesADifferentRegionOnEachSideOfItsSwitch() =
    runTest {
      val switch = SwitchRegion(before = LEFT_HALF, after = RIGHT_HALF, at = SWITCH_AT)
      val frames = exported(listOf(switch))

      withClue("the last slot is past the switch") {
        (outputSlotTime(frames.lastIndex.toLong(), FRAME_RATE) >= SWITCH_AT) shouldBe true
      }
      val first = frames.first().at(PROBE_X, PROBE_Y)
      val last = frames.last().at(PROBE_X, PROBE_Y)
      val expectedFirst = bandAt(LEFT_HALF.inside(PROBE_X))
      val expectedLast = bandAt(RIGHT_HALF.inside(PROBE_X))
      withClue("before the switch the probe read $first, and the left half puts $expectedFirst there") {
        first.isNear(expectedFirst) shouldBe true
      }
      withClue("after the switch the probe read $last, and the right half puts $expectedLast there") {
        last.isNear(expectedLast) shouldBe true
      }
    }

  // A crop, a mirror and a crop do not commute, and every other order of the three lands the probe on another band, so
  // only the chain's own order reads the band worked out here.
  @Test
  fun aTimedPassBetweenFixedOnesKeepsChainOrder() =
    runTest {
      val effects = listOf(CropRect(LEFT_HALF), TimedMirror, FixedRegion(RIGHT_HALF))
      val frame = exported(effects).last()

      val sourceX = LEFT_HALF.inside(mirrored(RIGHT_HALF.inside(PROBE_X)))
      val expected = bandAt(sourceX)
      val read = frame.at(PROBE_X, PROBE_Y)
      withClue("the probe read $read, and the chain in order samples source x $sourceX, which is $expected") {
        read.isNear(expected) shouldBe true
      }
    }

  private suspend fun exported(effects: List<EffectSpec>): List<OutputFrame> {
    val bytes = makePixelClip(frames = FRAMES, frameRate = FRAME_RATE) { _, x, _ -> BANDS[x * BANDS.size / WIDTH] }
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = effects)))),
        audio = AudioSpec.Remove,
      )
    val filmstrip =
      Filmstrip {
        webCodecsBackend()
        addEffectResolver(TimedResolver)
      }
    val statuses = filmstrip.export(planOf(filmstrip, composition), MediaSink.Uri("")).toList()
    val failure = statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()
    if (failure != null) throw AssertionError("the export failed: ${failure.error.message}")
    val success = statuses.filterIsInstance<ExportStatus.Success>().singleOrNull().shouldNotBeNull()
    return decodeFrames(outputOf(success)).also { withClue("decoded frames") { it.size shouldBe FRAMES } }
  }

  private fun bandAt(x: Double): Rgb = BANDS[(x * BANDS.size).toInt().coerceIn(0, BANDS.lastIndex)]
}

private data class SwitchRegion(
  val before: NormalizedRect,
  val after: NormalizedRect,
  val at: Duration,
) : EffectSpec {
  override val id: String get() = "test.switchRegion"
  override val stage: EffectStage get() = EffectStage.Geometry
}

private data object TimedMirror : EffectSpec {
  override val id: String get() = "test.timedMirror"
  override val stage: EffectStage get() = EffectStage.Geometry
}

private data class FixedRegion(
  val rect: NormalizedRect,
) : EffectSpec {
  override val id: String get() = "test.fixedRegion"
  override val stage: EffectStage get() = EffectStage.Geometry
}

private val TimedResolver =
  EffectResolver { spec, _, _ ->
    when (spec) {
      is SwitchRegion -> {
        resolved(
          WebGlPass(PROGRAM) { time ->
            regionMatrix(
              if (time <
                spec.at
              ) {
                spec.before
              } else {
                spec.after
              },
            )
          },
        )
      }
      is TimedMirror -> {
        resolved(WebGlPass(PROGRAM) { _ -> MIRROR })
      }
      is FixedRegion -> {
        resolved(WebGlPass(PROGRAM, mapOf(TEX_MATRIX to regionMatrix(spec.rect))))
      }
      else -> {
        null
      }
    }
  }

private fun resolved(pass: WebGlPass): EffectResolution = EffectResolution.Resolved(PlatformEffect(pass))

// Only the horizontal axis matters to the banded fixture, so these read u alone.
private fun regionMatrix(rect: NormalizedRect): FloatArray =
  floatArrayOf(rect.width, 0f, 0f, 0f, rect.height, 0f, rect.left, 1f - rect.bottom, 1f)

private fun NormalizedRect.inside(x: Double): Double = left + x * width

private fun mirrored(x: Double): Double = 1.0 - x

private val LEFT_HALF = NormalizedRect(0f, 0f, 0.5f, 1f)
private val RIGHT_HALF = NormalizedRect(0.5f, 0f, 1f, 1f)
private val MIRROR = floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 1f)
private val BANDS = listOf(Rgb.Red, Rgb.Green, Rgb.Blue, Rgb.Grey)
private val SWITCH_AT = 200.milliseconds

private const val PROGRAM = "test.texture"
private const val TEX_MATRIX = "uTexMatrix"
private const val WIDTH = 64
private const val FRAMES = 12
private const val FRAME_RATE = 30
private const val PROBE_X = 0.25
private const val PROBE_Y = 0.5
