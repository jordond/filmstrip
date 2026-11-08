package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.regionAt
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.geometry.frameAfter
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Ken Burns drawn through a real browser export and read back out of the written file.
 *
 * The fixture is a grey ramp across its width, so the level a probe reads says how far along the source it sampled.
 * Every expected level comes from `regionAt` at the slot's own composition time, and the pan is read inside its
 * travel as well as at its ends, where any pacing at all would agree.
 */
class BrowserKenBurnsMediaTest {
  @Test
  fun aPanSamplesTheRegionTheSharedCurveGivesAtEachSlot() =
    runTest {
      for ((easing, fractions) in listOf(Easing.EaseInOut to PAN_FRACTIONS, Easing.Linear to LINEAR_FRACTIONS)) {
        val pan = KenBurns(FROM, TO, easing)
        val frames = exported(single(listOf(pan)))

        withClue("$easing") {
          frames.size shouldBe FRAMES
          for (fraction in fractions) {
            val slot = slotAt(fraction)
            val region = pan.regionAt(outputSlotTime(slot.toLong(), FRAME_RATE), SPAN)
            assertLevel(region.inside(PROBE_X), frames[slot].at(PROBE_X, PROBE_Y), "slot $slot at $fraction")
          }
        }
      }
    }

  // A zoom changes the region's width, which a probe at the centre cannot see, so it is read either side of it too.
  @Test
  fun aZoomSamplesTheRegionTheSharedCurveGivesMidTravel() =
    runTest {
      val zoom = KenBurns(NormalizedRect.Full, INNER, Easing.Linear)
      val frames = exported(single(listOf(zoom)))
      val slot = slotAt(MID_FRACTION)

      val region = zoom.regionAt(outputSlotTime(slot.toLong(), FRAME_RATE), SPAN)
      for (x in ZOOM_PROBES) {
        assertLevel(region.inside(x), frames[slot].at(x, PROBE_Y), "slot $slot at x $x")
      }
    }

  // Listed out of order, the clip still crops, then pans inside the crop, then scales. A pan before the crop would
  // travel the whole source rather than its left half, and read another level.
  @Test
  fun aPanRunsAfterACropAndBeforeAScale() =
    runTest {
      val pan = KenBurns(FROM, TO)
      val crop = CropRect(LEFT_HALF)
      val scale = Scale(SCALED_HEIGHT)
      val frames = exported(single(listOf(scale, pan, crop)))
      val slot = slotAt(MID_FRACTION)

      val expectedSize = frameAfter(scale, frameAfter(pan, frameAfter(crop, RAMP_SIZE)))
      Size(frames[slot].width, frames[slot].height) shouldBe expectedSize
      val region = pan.regionAt(outputSlotTime(slot.toLong(), FRAME_RATE), SPAN)
      assertLevel(LEFT_HALF.inside(region.inside(PROBE_X)), frames[slot].at(PROBE_X, PROBE_Y), "slot $slot")
    }

  @Test
  fun aPreviewMidPanDrawsTheFrameTheExportWrites() =
    runTest {
      val composition = single(listOf(KenBurns(FROM, TO)))
      val written = exported(composition)
      val slot = slotAt(MID_FRACTION)
      val preview = previewOf(composition)
      try {
        val drawn = preview.frameAt(outputSlotTime(slot.toLong(), FRAME_RATE)).shouldNotBeNull()
        for (x in listOf(0.2, PROBE_X, 0.8)) {
          val expected = written[slot].at(x, PROBE_Y)
          val read = drawn.at(x, PROBE_Y)
          withClue("the preview read $read at $x, and the export wrote $expected") {
            read.isNear(expected, LEVEL_TOLERANCE) shouldBe true
          }
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

  private suspend fun single(effects: List<EffectSpec>): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(rampClip()), effects = effects)))),
      audio = AudioSpec.Remove,
    )

  // The ramp is grey, so it rides the full resolution luma plane and comes back far closer than a chroma edge does.
  private fun assertLevel(
    sourceX: Double,
    read: Rgb,
    where: String,
  ) {
    val expected = levelAt(sourceX)
    withClue("$where read $read, and source x $sourceX is $expected") {
      read.isNear(expected, LEVEL_TOLERANCE) shouldBe true
    }
  }
}

private suspend fun rampClip(): ByteArray =
  makePixelClip(
    width = RAMP_SIZE.width,
    height = RAMP_SIZE.height,
    frames = FRAMES,
    frameRate = FRAME_RATE,
  ) { _, x, _ ->
    levelAt((x + 0.5) / RAMP_SIZE.width)
  }

private fun levelAt(x: Double): Rgb {
  val level = (x.coerceIn(0.0, 1.0) * MAX_LEVEL).roundToInt()
  return Rgb(level, level, level)
}

private fun slotAt(fraction: Double): Int = (fraction * FRAMES).roundToInt().coerceAtMost(FRAMES - 1)

private fun NormalizedRect.inside(x: Double): Double = left + x * width

private val FROM = NormalizedRect(0f, 0f, 0.2f, 1f)
private val TO = NormalizedRect(0.8f, 0f, 1f, 1f)
private val LEFT_HALF = NormalizedRect(0f, 0f, 0.5f, 1f)
private val INNER = NormalizedRect(0.3f, 0f, 0.7f, 1f)
private val RAMP_SIZE = Size(128, 64)
private val SPAN = TimeRange(Duration.ZERO, FRAMES.seconds / FRAME_RATE)

// The ends and three points inside the travel, where EaseInOut and Linear part. Linear is read where the two differ
// as well as at the middle they share.
private val PAN_FRACTIONS = listOf(0.0, 0.4, 0.5, 0.6, 1.0)
private val LINEAR_FRACTIONS = listOf(0.4, 0.5)

private const val FRAMES = 30
private const val FRAME_RATE = 30
private const val SCALED_HEIGHT = 48
private const val MID_FRACTION = 0.4
private const val PROBE_X = 0.5
private val ZOOM_PROBES = listOf(0.2, 0.8)
private const val PROBE_Y = 0.5
private const val MAX_LEVEL = 255
private const val LEVEL_TOLERANCE = 8
