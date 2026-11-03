package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.FIRST_BLUE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.GRID_RATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.QUIET_READING
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.SEAM_RATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.SECOND_BLUE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.THIRD_BLUE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.Shown
import dev.jordond.filmstrip.ffmpeg.support.assertOnTheGrid
import dev.jordond.filmstrip.ffmpeg.support.decodedAudio
import dev.jordond.filmstrip.ffmpeg.support.indexedClip
import dev.jordond.filmstrip.ffmpeg.support.levelAt
import dev.jordond.filmstrip.ffmpeg.support.writtenFrames
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.LoopingCases
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Clip boundaries on the output frame grid, read back through indexed clips that name their own frames.
 */
class FfmpegFrameGridTest {
  private val fixture = FfmpegExportFixture()

  /**
   * A clip trimmed off the frame grid, handed to the next one on it.
   *
   * 1.010 s is 30.3 slots at 30 fps and rounds down, and 1.020 s is 30.6 and rounds up, so the seam moves to either
   * side of where the trim put it. The first clip holds every slot up to the seam and the second opens on it with its
   * own first frame.
   */
  @Test
  fun `hands a clip trimmed off the frame grid to the next one on it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val first = indexedClip(SEAM_RATE, 2.seconds, blue = FIRST_BLUE)
      val second = indexedClip(SEAM_RATE, 2.seconds, blue = SECOND_BLUE)

      listOf(1_010.milliseconds, 1_020.milliseconds).forEach { length ->
        val composition =
          compositionOf {
            clip(MediaSource.of(first.absolutePath)) { trim(TimeRange.of(Duration.ZERO, length)) }
            clip(MediaSource.of(second.absolutePath)) { trim(TimeRange.of(Duration.ZERO, 1.seconds)) }
          }
        val output = File.createTempFile("filmstrip-grid-seam", ".mp4").also { it.delete() }
        fixture.exported(fixture.plannedRun(composition, ExportSpec(frameRate = SEAM_RATE)), output)

        val frames = writtenFrames(output.absolutePath)
        assertOnTheGrid(frames, outputSlotOf(length + 1.seconds, SEAM_RATE), SEAM_RATE, "a first clip of $length")
        val seam = outputSlotOf(length, SEAM_RATE).toInt()
        frames[seam - 1].shown shouldBe Shown(FIRST_BLUE, seam - 1)
        frames[seam].shown shouldBe Shown(SECOND_BLUE, 0)

        output.delete()
      }

      first.delete()
      second.delete()
    }

  /**
   * Three clips of 1.010 s meet at 1.010 s and 2.020 s, which land on slots 25 and 51 at 25 fps, so the middle clip
   * covers 26 slots and the other two 25. Rounding each clip's own length instead gives 25 three times and leaves
   * the file a frame short of the timeline.
   */
  @Test
  fun `lays back to back clips off the frame grid without drifting`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val blues = listOf(FIRST_BLUE, SECOND_BLUE, THIRD_BLUE)
      val sources = blues.map { indexedClip(GRID_RATE, 2.seconds, blue = it) }
      val composition =
        compositionOf {
          sources.forEach { clip(MediaSource.of(it.absolutePath)) { trim(TimeRange.of(Duration.ZERO, OFF_GRID_TRIM)) } }
        }
      val spec = ExportSpec(frameRate = GRID_RATE)
      val laid =
        fixture.plannedTracks(composition, spec, sources.map { MediaSource.of(it.absolutePath) }).single().clips

      val output = File.createTempFile("filmstrip-grid-three", ".mp4").also { it.delete() }
      fixture.exported(fixture.plannedRun(composition, spec), output)

      val frames = writtenFrames(output.absolutePath)
      assertOnTheGrid(frames, outputSlotOf(OFF_GRID_TRIM * 3, GRID_RATE), GRID_RATE, "three clips of $OFF_GRID_TRIM")
      laid.forEachIndexed { index, clip ->
        val opens = outputSlotOf(clip.span.start, GRID_RATE).toInt()
        frames[opens].shown shouldBe Shown(blues[index], 0)
        if (index > 0) frames[opens - 1].shown?.blue shouldBe blues[index - 1]
      }

      output.delete()
      sources.forEach { it.delete() }
    }

  /**
   * A looping primary whose 1.7 s pass is 42.5 slots at 25 fps, laid until an audio track 7.3 s long ends.
   *
   * Every pass opens on the slot its own start lands on, which moves between 42 and 43 slots a pass, and the last one
   * is cut to what is left of the run. A pass cut to its own rounded length instead drifts a slot every other pass.
   */
  @Test
  fun `opens every looping pass on the slot its start lands on`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val picture = indexedClip(GRID_RATE, 2.seconds, blue = FIRST_BLUE)
      val pictureSource = MediaSource.of(picture.absolutePath)
      val toneSource = MediaSource.of(fixture.long.absolutePath)
      val composition =
        compositionOf {
          track {
            clip(pictureSource) { trim(LoopingCases.BED_TRIM) }
            looping()
          }
          track(TrackContent.Audio) {
            clip(toneSource) { trim(TimeRange.of(Duration.ZERO, LoopingCases.PRIMARY_RUN)) }
          }
        }
      val spec = ExportSpec(frameRate = GRID_RATE)
      val laid = fixture.plannedTracks(composition, spec, listOf(pictureSource, toneSource)).first().clips
      withClue("the run laid ${laid.size} passes, too few for a pass to drift") { (laid.size > 3).shouldBeTrue() }

      val output = File.createTempFile("filmstrip-grid-loop", ".mp4").also { it.delete() }
      fixture.exported(fixture.plannedRun(composition, spec), output)

      val frames = writtenFrames(output.absolutePath)
      assertOnTheGrid(frames, outputSlotOf(LoopingCases.PRIMARY_RUN, GRID_RATE), GRID_RATE, "a looping primary")
      // The picture is written at the output's own rate, so the frame the trim opens on is the slot its start lands on.
      val trimOpens = outputSlotOf(LoopingCases.BED_TRIM.start, GRID_RATE).toInt()
      laid.forEachIndexed { pass, clip ->
        val opens = outputSlotOf(clip.span.start, GRID_RATE).toInt()
        withClue("pass ${pass + 1} opening on slot $opens") {
          frames[opens].shown shouldBe Shown(FIRST_BLUE, trimOpens)
          if (pass > 0) frames[opens - 1].shown?.index shouldBe trimOpens + (opens - 1 - previousOpening(laid, pass))
        }
      }

      output.delete()
      picture.delete()
    }

  /**
   * A primary that starts half a slot in, which rounds up onto the first slot, so the fill holds slot zero and the
   * clip opens on the slot after it.
   */
  @Test
  fun `opens a primary that starts half a slot in on the slot after it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val picture = indexedClip(GRID_RATE, 2.seconds, blue = FIRST_BLUE)
      val composition =
        compositionOf {
          track {
            clip(MediaSource.of(picture.absolutePath))
            startAt(HALF_SLOT_START)
          }
        }

      val output = File.createTempFile("filmstrip-grid-lead", ".mp4").also { it.delete() }
      fixture.exported(fixture.plannedRun(composition, ExportSpec(frameRate = GRID_RATE)), output)

      val frames = writtenFrames(output.absolutePath)
      assertOnTheGrid(frames, outputSlotOf(HALF_SLOT_START + 2.seconds, GRID_RATE), GRID_RATE, "a half slot start")
      val opens = outputSlotOf(HALF_SLOT_START, GRID_RATE).toInt()
      (0 until opens).forEach { slot -> frames[slot].shown shouldBe null }
      frames[opens].shown shouldBe Shown(FIRST_BLUE, 0)

      output.delete()
      picture.delete()
    }

  /**
   * A clip of 10 ms between two others reaches no point half way between two slots at 25 fps, so it covers none and
   * lays no picture. Its sound is exact, so the tone only it carries still plays across its own 10 ms.
   */
  @Test
  fun `lays no picture for a clip that covers no slot and keeps its sound`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val before = indexedClip(GRID_RATE, 2.seconds, blue = FIRST_BLUE)
      val between = indexedClip(GRID_RATE, 2.seconds, blue = SECOND_BLUE, sound = 2.seconds)
      val after = indexedClip(GRID_RATE, 2.seconds, blue = THIRD_BLUE)
      val composition =
        compositionOf {
          clip(MediaSource.of(before.absolutePath)) { trim(TimeRange.of(Duration.ZERO, 1.seconds)) }
          clip(MediaSource.of(between.absolutePath)) { trim(TimeRange.of(Duration.ZERO, UNDER_HALF_SLOT)) }
          clip(MediaSource.of(after.absolutePath)) { trim(TimeRange.of(Duration.ZERO, 1.seconds)) }
        }
      val spec = ExportSpec(frameRate = GRID_RATE)
      val sources = listOf(before, between, after).map { MediaSource.of(it.absolutePath) }
      val laid = fixture.plannedTracks(composition, spec, sources).single().clips
      withClue("the middle clip covers a slot") { outputSlotsOf(laid[1].span, GRID_RATE).isEmpty().shouldBeTrue() }

      val output = File.createTempFile("filmstrip-grid-zero", ".mp4").also { it.delete() }
      fixture.exported(fixture.plannedRun(composition, spec), output)

      val frames = writtenFrames(output.absolutePath)
      val end =
        laid
          .last()
          .span.endExclusive
          .shouldNotBeNull()
      assertOnTheGrid(frames, outputSlotOf(end, GRID_RATE), GRID_RATE, "a clip covering no slot")
      frames.forEach { frame -> frame.shown?.blue shouldNotBe SECOND_BLUE }
      frames[outputSlotOf(laid[2].span.start, GRID_RATE).toInt()].shown shouldBe Shown(THIRD_BLUE, 0)

      // Read against the same tone in the clip it came from, so how loud it was written divides out.
      val tone = decodedAudio(between.absolutePath).levelAt(1.seconds)
      val samples = decodedAudio(output.absolutePath)
      val inside = laid[1].span.start + UNDER_HALF_SLOT / 2
      withClue("the clip's own tone is missing at $inside") {
        (samples.levelAt(inside) >= tone * CLIPPED_TONE_FLOOR).shouldBeTrue()
      }
      withClue("the silent clip carried sound") {
        (samples.levelAt(QUIET_READING) <= tone * SILENCE_CEILING).shouldBeTrue()
      }

      output.delete()
      listOf(before, between, after).forEach { it.delete() }
    }

  // The first slot of the pass before [pass], which the frame ahead of this pass's first slot is counted from.
  private fun previousOpening(
    laid: List<ResolvedClip>,
    pass: Int,
  ): Int = outputSlotOf(laid[pass - 1].span.start, GRID_RATE).toInt()

  private companion object {
    // A trim a quarter of a slot past a whole number of them at GRID_RATE, a clip that reaches no half way point,
    // and a start exactly half a slot in.
    val OFF_GRID_TRIM = 1_010.milliseconds
    val UNDER_HALF_SLOT = 10.milliseconds
    val HALF_SLOT_START = 20.milliseconds

    // A 20 ms level window centred on a 10 ms clip holds its tone for half the window, which reads about 0.7 of the
    // tone's own level. A quarter separates that from a clip whose sound never reached the file.
    const val CLIPPED_TONE_FLOOR = 0.25f
    const val SILENCE_CEILING = 0.05f
  }
}
