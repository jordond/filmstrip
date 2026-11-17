package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.assumeDecodesTenBitHevc
import dev.jordond.filmstrip.test.assumeEncodesHdr
import dev.jordond.filmstrip.test.eightBitFrameAt
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An export writes one frame on every slot of the grid, whatever rate its sources run at and whatever speed its clips
 * play at, and each slot shows the sample the shared rule picks for it.
 */
class AndroidEverySlotMediaTest {
  private val probe = Media3ExportProbe()
  private val engine =
    media3ExportEngine(ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build())

  @Test
  fun aSourceSlowerThanTheOutputWritesEverySlot() =
    runTest(timeout = TIMEOUT) {
      assertEverySlot(listOf(Clip(probe.fixture(CLIP_24FPS))), ExportSpec(heightPx = 240, frameRate = 30))
    }

  @Test
  fun aSourceSlowerByLessThanOneFrameInFiveWritesEverySlot() =
    runTest(timeout = TIMEOUT) {
      assertEverySlot(listOf(Clip(probe.fixture(CLIP_24FPS))), ExportSpec(heightPx = 240, frameRate = OFF_GRID_RATE))
    }

  @Test
  fun aSlowerClipAfterAFasterOneWritesEverySlotOfBoth() =
    runTest(timeout = TIMEOUT) {
      val clips = listOf(Clip(probe.fixture(CLIP)), Clip(probe.fixture(CLIP_24FPS)))

      assertEverySlot(clips, ExportSpec(heightPx = 240, frameRate = 30))
    }

  @Test
  fun aClipSlowedToHalfWritesEverySlot() =
    runTest(timeout = TIMEOUT) {
      assertEverySlot(listOf(Clip(probe.fixture(CLIP), speed = 0.5)), ExportSpec(heightPx = 240, frameRate = 30))
    }

  @Test
  fun aClipSlowedInTheMiddleOfTheRangeWritesEverySlot() =
    runTest(timeout = TIMEOUT) {
      assertEverySlot(listOf(Clip(probe.fixture(CLIP), speed = 0.7)), ExportSpec(heightPx = 240, frameRate = 30))
    }

  @Test
  fun aTrimmedSlowedClipOpensOnItsTrimStartAndFillsEverySlot() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(probe.fixture(CLIP_24FPS), TimeRange.of(500.milliseconds, 1_500.milliseconds), speed = 0.5)

      assertEverySlot(listOf(clip), ExportSpec(heightPx = 240, frameRate = 30))
    }

  @Test
  fun aSourceFasterThanTheOutputStillWritesOneFramePerSlot() =
    runTest(timeout = TIMEOUT) {
      assertEverySlot(listOf(Clip(probe.fixture(CLIP))), ExportSpec(heightPx = 240, frameRate = 15))
    }

  @Test
  fun aClipSpedUpStillWritesOneFramePerSlot() =
    runTest(timeout = TIMEOUT) {
      assertEverySlot(listOf(Clip(probe.fixture(CLIP), speed = 2.0)), ExportSpec(heightPx = 240, frameRate = 30))
    }

  @Test
  fun aSlowedTenBitClipWritesEverySlot() =
    runTest(timeout = TIMEOUT) {
      assumeDecodesTenBitHevc()
      probe.filmstrip.assumeEncodesHdr()
      val edit = Composition(listOf(Track(listOf(Clip(probe.fixture(HDR_CLIP), speed = 0.5)))))

      assertSampleOnEverySlot(edit, ExportSpec(heightPx = 240, frameRate = 30))
    }

  @Test
  fun aBlurredFillOverASlowedClipWritesEverySlot() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(probe.fixture(CLIP), speed = 0.5, effects = listOf(Scale(heightPx = 120)))
      val edit = Composition(listOf(Track(listOf(clip))), fill = Fill.Blurred(radius = 0.05f, dim = 0f))

      assertSampleOnEverySlot(edit, ExportSpec(heightPx = 240, frameRate = 30))
    }

  @Test
  fun aFadingCaptionOverASlowedClipWritesEverySlot() =
    runTest(timeout = TIMEOUT) {
      val caption = TextOverlay(text = "HELD", anchor = Anchor.Center, animation = fade(inDuration = 1.seconds))
      val edit = Composition(listOf(Track(listOf(Clip(probe.fixture(CLIP), speed = 0.25)))), effects = listOf(caption))

      assertSampleOnEverySlot(edit, ExportSpec(heightPx = 240, frameRate = 30))
    }

  // One sample a second at 0.1x holds each for 10 s, longer than a float speed can land a stretch of exactly, so
  // the pace writes the next sample a step off its key.
  @Test
  fun aSampleHeldForSecondsWritesEverySlotOfItsHold() =
    runTest(timeout = TIMEOUT) {
      val slow = retimeSamples(probe.fixtureFile(CLIP), File(probe.context.cacheDir, "one_a_second.mp4"), 1.seconds)
      val clip = Clip(MediaSource.of(slow.path), TimeRange.of(Duration.ZERO, 2.seconds), speed = 0.1)

      assertEverySlot(listOf(clip), ExportSpec(heightPx = 240, frameRate = 30))
    }

  @Test
  fun aSixtyFramesASecondSourceWritesOneFramePerSlot() =
    runTest(timeout = TIMEOUT) {
      val fast = retimeSamples(probe.fixtureFile(CLIP), File(probe.context.cacheDir, "sixty.mp4"), 1.seconds / 60)

      assertEverySlot(listOf(Clip(MediaSource.of(fast.path))), ExportSpec(heightPx = 240, frameRate = 30))
    }

  // At 1.5 times speed every slot skips one frame and a half, so slots alternate between skipping one and two.
  @Test
  fun aSixtyFramesASecondSourceSpedUpWritesOneFramePerSlotWithMixedDrops() =
    runTest(timeout = TIMEOUT) {
      val fast = retimeSamples(probe.fixtureFile(CLIP), File(probe.context.cacheDir, "sixty.mp4"), 1.seconds / 60)

      assertEverySlot(listOf(Clip(MediaSource.of(fast.path), speed = 1.5)), ExportSpec(heightPx = 240, frameRate = 30))
    }

  /**
   * Exports [clips] as one track and holds the file to one sample on each slot the plan covers, each showing the sample
   * the shared rule names for that slot.
   */
  private suspend fun assertEverySlot(
    clips: List<Clip>,
    spec: ExportSpec,
  ) {
    val rate = checkNotNull(spec.frameRate)
    val edit = Composition(listOf(Track(clips)))
    val laid = lowered(edit, spec).single().clips
    val file = assertSampleOnEverySlot(edit, spec)

    val bands = mutableMapOf<Pair<File, Duration>, List<Triple<Int, Int, Int>>>()

    fun bandOf(
      video: File,
      at: Duration,
    ): List<Triple<Int, Int, Int>> = bands.getOrPut(video to at) { eightBitFrameAt(video, at).band() }

    laid.forEach { clip ->
      val source = File((clip.source as MediaSource.Path).path)
      val samples = videoSampleTimes(source)
      outputSlotsOf(clip.span, rate).forEach { slot ->
        val time = sourceTimeOfSlot(clip.start, clip.span.start, clip.speed, slot, rate, clip.runStart)
        val shown = checkNotNull(nearestSourceFrame(samples, time))
        val written = eightBitFrameAt(file, outputSlotTime(slot, rate)).band()
        val expected = spread(written, bandOf(source, samples[shown]))
        val others = listOfNotNull(samples.getOrNull(shown - 1), samples.getOrNull(shown + 1))
        withClue("slot $slot does not show the sample at ${samples[shown]}") {
          others.forEach { other ->
            val apart = spread(written, bandOf(source, other))
            withClue("it reads $expected from it and $apart from the sample at $other") {
              expected shouldBeLessThan apart
            }
          }
        }
      }
    }
  }

  /**
   * Exports [edit] and holds the file to one sample on each slot the plan covers, on the grid, returning the file.
   */
  private suspend fun assertSampleOnEverySlot(
    edit: Composition,
    spec: ExportSpec,
  ): File {
    val rate = checkNotNull(spec.frameRate)
    val resolved = lowered(edit, spec)
    val slots = resolved.single().clips.flatMap { outputSlotsOf(it.span, rate).toList() }
    val file = probe.export(probe.plan(edit, spec))

    val times = videoSampleTimes(file)
    withClue("the file carries ${times.size} samples for ${slots.size} slots: $times") {
      times shouldBe slots.map { outputSlotTime(it, rate) }
    }
    return file
  }

  private suspend fun lowered(
    composition: Composition,
    spec: ExportSpec,
  ): List<ResolvedTrack> =
    when (val result = engine.resolve(composition, spec)) {
      is ResolveResult.Resolved -> {
        result.composition.tracks
      }
      is ResolveResult.Refused -> {
        throw AssertionError("the edit would not lower: ${result.error.message}")
      }
    }

  private fun Bitmap.band(): List<Triple<Int, Int, Int>> = FRAME_ACROSS.map { averageAt(it, MOVING_BAND) }

  private fun spread(
    a: List<Triple<Int, Int, Int>>,
    b: List<Triple<Int, Int, Int>>,
  ): Int = a.zip(b).sumOf { (one, other) -> distance(one, other) }

  private companion object {
    const val CLIP_24FPS = "android_export_24fps.mp4"
    const val OFF_GRID_RATE = 25
    const val MOVING_BAND = 0.8f
    val FRAME_ACROSS = (0..15).map { it / 15f }
  }
}
