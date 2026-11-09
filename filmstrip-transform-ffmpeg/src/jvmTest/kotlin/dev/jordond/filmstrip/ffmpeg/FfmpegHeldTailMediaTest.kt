package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.AUDIO_SAMPLE_RATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.FIRST_BLUE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.GRID_RATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.PRIMARY_HZ
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.SECOND_BLUE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.ExportedOverlay
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.ffmpeg.support.Shown
import dev.jordond.filmstrip.ffmpeg.support.WrittenFrame
import dev.jordond.filmstrip.ffmpeg.support.assertOnTheGrid
import dev.jordond.filmstrip.ffmpeg.support.centreOf
import dev.jordond.filmstrip.ffmpeg.support.decodedAudio
import dev.jordond.filmstrip.ffmpeg.support.drawnAt
import dev.jordond.filmstrip.ffmpeg.support.indexedClip
import dev.jordond.filmstrip.ffmpeg.support.onlyClip
import dev.jordond.filmstrip.ffmpeg.support.opensOn
import dev.jordond.filmstrip.ffmpeg.support.pointIn
import dev.jordond.filmstrip.ffmpeg.support.regionAverage
import dev.jordond.filmstrip.ffmpeg.support.toneAt
import dev.jordond.filmstrip.ffmpeg.support.writtenFrames
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.ToneAnalysis
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The held tail, where the picture holds its last frame under audio that outlasts it and overlays freeze on that
 * frame.
 */
class FfmpegHeldTailMediaTest {
  private val fixture = FfmpegExportFixture()

  /**
   * A clip whose picture ends 30 ms before its sound does, handed to a second clip.
   *
   * At 25 fps the first clip's span covers 26 slots and its picture only 25 frames, so the last of those slots holds
   * the picture's last frame rather than running a frame short, and the second clip still opens on its own slot.
   */
  @Test
  fun `holds the last frame of a picture that ends before its clip does`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val short = indexedClip(GRID_RATE, SHORT_PICTURE, blue = FIRST_BLUE, sound = SHORT_PICTURE + SOUND_OVERHANG)
      val next = indexedClip(GRID_RATE, 2.seconds, blue = SECOND_BLUE)
      val sources = listOf(short, next).map { MediaSource.of(it.absolutePath) }
      val composition =
        compositionOf {
          clip(sources[0])
          clip(sources[1]) { trim(TimeRange.of(Duration.ZERO, 1.seconds)) }
        }
      val spec = ExportSpec(frameRate = GRID_RATE)
      val laid = fixture.plannedTracks(composition, spec, sources).single().clips
      val held = outputSlotsOf(laid[0].span, GRID_RATE)
      // The picture is written at the output's own rate, so its frames are the slots its length covers from zero.
      val pictureFrames = outputSlotOf(SHORT_PICTURE, GRID_RATE)
      withClue("the clip covers ${held.count()} slots, which its frames fill") {
        (held.count() > pictureFrames).shouldBeTrue()
      }

      val output = File.createTempFile("filmstrip-grid-held", ".mp4").also { it.delete() }
      fixture.exported(fixture.plannedRun(composition, spec), output)

      val frames = writtenFrames(output.absolutePath)
      val end =
        laid
          .last()
          .span.endExclusive
          .shouldNotBeNull()
      assertOnTheGrid(frames, outputSlotOf(end, GRID_RATE), GRID_RATE, "a picture shorter than its clip")
      (pictureFrames - 1..held.last).forEach { slot ->
        frames[slot.toInt()].shown shouldBe Shown(FIRST_BLUE, pictureFrames.toInt() - 1)
      }
      frames[held.last.toInt() + 1].shown shouldBe Shown(SECOND_BLUE, 0)

      output.delete()
      short.delete()
      next.delete()
    }

  /**
   * A second of picture under a tone that runs on for four more.
   *
   * The video runs to the composition's end on the picture's last frame, so the slot before the held tail, the one it
   * opens on, the one in its middle and its last all read that frame. The tone carries on under it at the level it
   * held beside the picture, and the audio runs as long as the bed does.
   */
  @Test
  fun `holds the primary's last frame under an audio track that outlasts it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val picture = indexedClip(GRID_RATE, TAIL_PICTURE, blue = FIRST_BLUE)
      val sources = listOf(MediaSource.of(picture.absolutePath), MediaSource.of(fixture.long.absolutePath))
      val composition = underBed(sources[0], sources[1])
      val spec = ExportSpec(frameRate = GRID_RATE)
      val negotiated =
        fixture
          .plannedLowering(composition, spec, sources)
          .export.composition
          .shouldNotBeNull()
      val tail = negotiated.heldTail
      withClue("the bed does not outlast the picture") { (!tail.isEmpty()).shouldBeTrue() }

      val output = File.createTempFile("filmstrip-tail-held", ".mp4").also { it.delete() }
      fixture.exported(fixture.plannedRun(composition, spec), output)

      val frames = writtenFrames(output.absolutePath)
      assertOnTheGrid(frames, negotiated.videoSlots, GRID_RATE, "a picture held under a longer bed")
      val held = lastShown(negotiated)
      tail.readings().forEach { slot -> withClue("slot $slot") { frames[slot.toInt()].shown shouldBe held } }

      val samples = decodedAudio(output.absolutePath)
      val bedSamples = (negotiated.duration.toDouble(DurationUnit.SECONDS) * AUDIO_SAMPLE_RATE).roundToInt()
      withClue("the audio decodes to ${samples.size} samples where the bed runs $bedSamples") {
        (abs(samples.size - bedSamples) <= AAC_UNIT_SAMPLES).shouldBeTrue()
      }
      val middle = outputSlotTime(tail.middle(), GRID_RATE)
      val level = samples.toneAt(middle, PRIMARY_HZ) / samples.toneAt(BESIDE_PICTURE, PRIMARY_HZ)
      withClue("at $middle the bed read $level of the level it held beside the picture") {
        (abs(level - 1f) <= ToneAnalysis.MEASURED_GAIN_TOLERANCE).shouldBeTrue()
      }

      output.delete()
      picture.delete()
    }

  // The overlay's window closes with the clip, so a tail drawn at its own time would have shut it. The tail clones the
  // finished frame instead, so the overlay is still there wherever the tail is read.
  @Test
  fun `freezes a clip's overlay on the frame the tail holds`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertOverlayHeld(onClip = true, what = "clip")
    }

  // A composition overlay runs once after the join, and the clones come after it. The frame count and the overlay both
  // read off the file, so the clones following that overlay is measured here rather than assumed.
  @Test
  fun `freezes a composition overlay on the frame the tail holds`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertOverlayHeld(onClip = false, what = "composition")
    }

  // With no window of its own the overlay is drawn on every slot the clip covers, so the tail freezes it over the
  // clip's last frame rather than over the one before it.
  @Test
  fun `freezes an always visible clip overlay on the clip's last frame`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertOverlayHeld(onClip = true, what = "always visible clip", window = null)
    }

  // The overlay merges onto the first clip's own branch ahead of the cut to its slots, so that branch ends on the
  // merge rather than on the source.
  @Test
  fun `ends a clip carrying an overlay on its own last frame`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertEachClipEndsOnItsLastFrame(
        what = "a clip overlay",
        reaching = "[v0m0a0]overlay",
      ) { first, second ->
        compositionOf {
          clip(first) { effects { add(fixture.watermark()) } }
          clip(second)
        }
      }
    }

  // Every clip ends on the merge of its sharp copy over its blurred one.
  @Test
  fun `ends every clip under a blurred fill on its own last frame`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertEachClipEndsOnItsLastFrame(
        what = "a blurred fill",
        reaching = "[v0blur][v0sharp]overlay",
      ) { first, second ->
        compositionOf {
          clip(first)
          clip(second)
          effects { crop(AspectRatio.Portrait, fit = Fit.Contain) }
          fill(Fill.Blur)
        }
      }
    }

  /**
   * Exports two indexed clips of [TAIL_PICTURE] back to back as [composed] lays them, and reads every slot each one
   * covers off the middle of the file, clear of any corner an overlay is drawn in and of the bars a fill paints.
   *
   * Each slot shows its own clip's frame of that index, so a clip's last slot shows its last frame rather than a clone
   * of the one before it. [reaching] is a filter the graph has to carry, so the shape under test is the one exported.
   */
  private suspend fun assertEachClipEndsOnItsLastFrame(
    what: String,
    reaching: String,
    composed: (MediaSource, MediaSource) -> Composition,
  ) {
    val blues = listOf(FIRST_BLUE, SECOND_BLUE)
    val pictures = blues.map { indexedClip(GRID_RATE, TAIL_PICTURE, blue = it) }
    val sources = pictures.map { MediaSource.of(it.absolutePath) }
    val composition = composed(sources[0], sources[1])
    val spec = ExportSpec(frameRate = GRID_RATE)
    val lowering = fixture.plannedLowering(composition, spec, sources)
    val graph = lowering.graph.shouldNotBeNull().filterGraph
    withClue("the export of $what carries no $reaching: $graph") { (reaching in graph).shouldBeTrue() }
    val laid =
      lowering.export.composition
        .shouldNotBeNull()
        .tracks
        .single()
        .clips

    val output = File.createTempFile("filmstrip-last-frame", ".mp4").also { it.delete() }
    fixture.exported(fixture.plannedRun(composition, spec), output)

    val frames = writtenFrames(output.absolutePath, middleOnly = true)
    val end =
      laid
        .last()
        .span.endExclusive
        .shouldNotBeNull()
    assertOnTheGrid(frames, outputSlotOf(end, GRID_RATE), GRID_RATE, what)
    laid.forEachIndexed { index, clip ->
      val covered = outputSlotsOf(clip.span, GRID_RATE)
      covered.forEach { slot ->
        withClue("$what, clip $index on slot $slot") {
          frames[slot.toInt()].shown shouldBe Shown(blues[index], (slot - covered.first).toInt())
        }
      }
    }

    output.delete()
    pictures.forEach { it.delete() }
  }

  // The bed opens after the picture has ended, so the composition runs to the bed's own end and the picture holds
  // across the silence and under the bed.
  @Test
  fun `holds the primary's last frame to a late audio track's end`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val picture = indexedClip(GRID_RATE, TAIL_PICTURE, blue = FIRST_BLUE)
      val sources = listOf(MediaSource.of(picture.absolutePath), MediaSource.of(fixture.long.absolutePath))
      val composition = underBed(sources[0], sources[1], bedStart = LATE_BED_START, bedLength = LATE_BED)
      val spec = ExportSpec(frameRate = GRID_RATE)
      val negotiated =
        fixture
          .plannedLowering(composition, spec, sources)
          .export.composition
          .shouldNotBeNull()
      negotiated.duration shouldBe LATE_BED_START + LATE_BED

      val output = File.createTempFile("filmstrip-tail-late", ".mp4").also { it.delete() }
      fixture.exported(fixture.plannedRun(composition, spec), output)

      val frames = writtenFrames(output.absolutePath)
      assertOnTheGrid(frames, negotiated.videoSlots, GRID_RATE, "a picture held to a late bed's end")
      val held = lastShown(negotiated)
      negotiated.heldTail.readings().forEach { slot ->
        withClue("slot $slot") { frames[slot.toInt()].shown shouldBe held }
      }

      output.delete()
      picture.delete()
    }

  /**
   * Exports the indexed picture under the bed with an overlay shown over [window], once bare and once with the overlay
   * on the clip or on the composition, and reads both across the held tail.
   *
   * The overlay has to be drawn on the last covered slot and on every tail reading, and the picture under it has to be
   * the primary's own last frame. A reading ahead of a window pins that the window is live at all, and an overlay with
   * no window is drawn there too.
   */
  private suspend fun assertOverlayHeld(
    onClip: Boolean,
    what: String,
    window: TimeRange? = CLOSES_WITH_PICTURE,
  ) {
    val picture = indexedClip(GRID_RATE, TAIL_PICTURE, blue = FIRST_BLUE)
    val sources = listOf(MediaSource.of(picture.absolutePath), MediaSource.of(fixture.long.absolutePath))
    val overlay = fixture.watermark().let { mark -> ImageOverlay(mark.image, mark.corner, visibleDuring = window) }
    val spec = ExportSpec(frameRate = GRID_RATE)
    val composition = underBed(sources[0], sources[1], overlay = overlay, onClip = onClip)
    val lowering = fixture.plannedLowering(composition, spec, sources)
    val negotiated = lowering.export.composition.shouldNotBeNull()
    // A composition overlay runs after the join, and the clones have to follow it to freeze it.
    if (!onClip) {
      val graph = lowering.graph.shouldNotBeNull().filterGraph
      val clones = "[vfx0]tpad=stop_mode=clone:stop=${negotiated.heldTail.count()}"
      withClue("the tail is cloned ahead of the composition overlay: $graph") { (clones in graph).shouldBeTrue() }
    }

    val bare = exportedAs("filmstrip-tail-$what-bare", underBed(sources[0], sources[1]), spec)
    val marked = exportedAs("filmstrip-tail-$what", composition, spec)
    val size = marked.plan.output.size
    val probe = centreOf(overlay.placedOn(size, OVERLAY_SIZE).rectOn(size), size)
    val tail = negotiated.heldTail
    val held = lastShown(negotiated)

    assertOnTheGrid(writtenFrames(marked.file.absolutePath), negotiated.videoSlots, GRID_RATE, "a $what overlay")
    val drawnEarly = drawnAt(bare, marked, probe, BEFORE_CLOSING)
    if (window == null) {
      withClue("the $what overlay is missing ahead of the tail") { drawnEarly.shouldBeTrue() }
    } else {
      withClue("the $what overlay is drawn ahead of its window") { (!drawnEarly).shouldBeTrue() }
    }
    tail.readings().forEach { slot ->
      val at = opensOn(slot.toInt(), marked.plan)
      withClue("the $what overlay is gone from slot $slot") { drawnAt(bare, marked, probe, at).shouldBeTrue() }
      withClue("the $what overlay on slot $slot") { shownAt(marked.file.absolutePath, size, at) shouldBe held }
    }

    listOf(bare, marked).forEach { it.file.delete() }
    picture.delete()
  }

  /**
   * The indexed [picture] as the primary, under [TAIL_BED] of the long fixture's tone opening at [bedStart], with
   * [overlay] on the picture's clip or on the whole composition.
   */
  private fun underBed(
    picture: MediaSource,
    tone: MediaSource,
    bedStart: Duration = Duration.ZERO,
    bedLength: Duration = TAIL_BED,
    overlay: ImageOverlay? = null,
    onClip: Boolean = false,
  ): Composition =
    compositionOf {
      clip(picture) { if (onClip) overlay?.let { mark -> effects { add(mark) } } }
      track(TrackContent.Audio) {
        clip(tone) { trim(TimeRange.of(Duration.ZERO, bedLength)) }
        startAt(bedStart)
      }
      if (!onClip) overlay?.let { mark -> effects { add(mark) } }
    }

  private suspend fun exportedAs(
    name: String,
    composition: Composition,
    spec: ExportSpec,
  ): ExportedOverlay {
    val output = File.createTempFile(name, ".mp4").also { it.delete() }
    val plan = fixture.plannedRun(composition, spec)
    fixture.exported(plan, output)
    return ExportedOverlay(output, plan)
  }

  // The primary's own frame on the last slot it covers. The picture is written at the output's rate from its first
  // frame, so that is the frame whose index is one short of the slots its span covers.
  private fun lastShown(negotiated: ResolvedComposition): Shown {
    val primary = negotiated.tracks.first().onlyClip
    return Shown(FIRST_BLUE, outputSlotsOf(primary.span, GRID_RATE).count() - 1)
  }

  // The indexed frame the middle of [size] shows at [at], clear of any corner an overlay is drawn in.
  private fun shownAt(
    path: String,
    size: Size,
    at: Duration,
  ): Shown? {
    val (x, y) = pointIn(HALF, HALF, size)
    val (red, green, blue) = regionAverage(path, x, y, PROBE_SIDE, at)
    return WrittenFrame(at, red.roundToInt(), green.roundToInt(), blue.roundToInt()).shown
  }

  // The slot ahead of the tail, the one it opens on, the one in its middle and its last.
  private fun LongRange.readings(): List<Long> = listOf(first - 1, first, middle(), last)

  private fun LongRange.middle(): Long = first + (last - first) / 2

  private companion object {
    // A picture of a whole second under a sound that runs 30 ms longer, so the clip ends three quarters of a slot
    // past its last frame.
    val SHORT_PICTURE = 1.seconds
    val SOUND_OVERHANG = 30.milliseconds

    // A second of picture under a bed that runs on past it and ends 6 ms past a slot rather than on one. The tone is
    // read beside the picture before it is read in the tail.
    val TAIL_PICTURE = 1.seconds
    val TAIL_BED = 5_006.milliseconds
    val BESIDE_PICTURE = 500.milliseconds

    // A bed opening long after the picture has ended, and the composition running to its end.
    val LATE_BED_START = 4_500.milliseconds
    val LATE_BED = 1.seconds

    // An overlay window over the picture's second half that closes with it, and a reading ahead of it.
    val CLOSES_WITH_PICTURE = TimeRange.of(500.milliseconds, 1.seconds)
    val BEFORE_CLOSING = 200.milliseconds

    // AAC's own frame. The encoder pads the last one out, and whether a decode trims that padding back off depends on
    // the build, so the decoded bed may run up to one frame past its length.
    const val AAC_UNIT_SAMPLES = 1_024
  }
}
