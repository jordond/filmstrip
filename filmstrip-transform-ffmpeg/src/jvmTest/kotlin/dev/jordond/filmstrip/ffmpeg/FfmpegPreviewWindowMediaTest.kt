package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewLanding
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Which graphs a preview windows with an input seek, held against the clock an animated overlay's own branch counts on.
 *
 * A windowed preview moves an overlay's branch to the composition time the scrub lands on, while the branch counts
 * its frames from the slot the overlay's span opens on. The two agree only for a span opening at zero, so every graph
 * the preview windows has to open its overlay there, and every other one is read from the head on the export's clock.
 */
class FfmpegPreviewWindowMediaTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")

  private val engine =
    FfmpegExportEngine(
      ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      FfmpegRuntime.of(FfmpegConfig()),
    )

  @Test
  fun `a windowed preview moves an overlay's branch onto the frame its index counts from the span`() =
    runTest(timeout = TIMEOUT) {
      withClue("the fixture ${landscape.absolutePath} was not downloaded") { landscape.isFile.shouldBeTrue() }
      val source = MediaSource.of(landscape.absolutePath)
      val edits =
        mapOf(
          "a clip opening the composition" to Composition(listOf(Track(listOf(marked(source))))),
          "a clip after a leading gap" to Composition(listOf(Track(listOf(marked(source)), start = LEAD))),
          "the second of two clips" to
            Composition(listOf(Track(listOf(Clip(source, CLIP_TRIM), marked(source))))),
        )

      val windowed =
        edits.filter { (name, edit) ->
          withClue(name) { assertBranchAgreesWithIndex(edit) }
        }
      withClue("no graph was windowed, so the agreement went unchecked") {
        windowed.keys shouldBe setOf("a clip opening the composition")
      }
    }

  // Whether the preview of [edit] is windowed. A windowed one moves every rebased branch onto the index its span counts
  // to the slot the preview opens on. One read from the head moves and seeks nothing.
  private suspend fun assertBranchAgreesWithIndex(edit: Composition): Boolean {
    val (toolchain, invocation) =
      engine.runs
        .lowered(edit, ExportSpec(), null)
        .graph
        .shouldNotBeNull()
    val resolved = resolved(edit)
    val rate = resolved.output.frameRate
    val span =
      resolved.tracks
        .first()
        .clips
        .single { it.effects.isNotEmpty() }
        .span
    val at = span.start + INTO_SPAN
    val arguments =
      invocation.previewArguments(
        toolchain,
        engine.runtime.config,
        invocation.inputs.indices.map { "/inputs/$it" },
        invocation.sidecars.indices.map { "/sidecars/$it" },
        at,
      )

    if (invocation.seekBase == null) {
      arguments shouldNotContain "-ss"
      arguments shouldNotContain "-itsoffset"
      return false
    }

    val offsets = invocation.rebasedOffsets(arguments)
    withClue("the windowed graph carries no rebased branch to check") { offsets.isNotEmpty().shouldBeTrue() }
    val index = outputSlotOf(invocation.previewLanding(at), rate) - outputSlotOf(span.start, rate)
    offsets.forEach { offset ->
      withClue("the branch was moved to $offset, which its clock reads as another frame than index $index") {
        outputSlotOf(offset.shouldNotBeNull(), rate) shouldBe index
      }
    }
    return true
  }

  // The -itsoffset each rebased input is opened with, null where it has none.
  private fun Invocation.rebasedOffsets(arguments: List<String>): List<Duration?> {
    val opened = mutableListOf<Duration?>()
    var pending: Duration? = null
    arguments.forEachIndexed { index, argument ->
      when (argument) {
        "-itsoffset" -> pending = (arguments[index + 1].toDouble() * MICROS_PER_SECOND).toLong().microseconds
        "-i" -> opened += pending.also { pending = null }
      }
    }
    return inputs.indices.filter { inputs[it].rebased }.map { opened[it] }
  }

  private suspend fun resolved(composition: Composition): ResolvedComposition =
    engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>().composition

  private fun marked(source: MediaSource): Clip =
    Clip(
      source,
      CLIP_TRIM,
      effects =
        listOf(
          ImageOverlay(
            image = ImageSource.ofBytes(WHITE_SQUARE),
            corner = Corner.TopStart,
            animation = fade(inDuration = CLIP_TRIM.duration.shouldNotBeNull()),
          ),
        ),
    )

  private companion object {
    val TIMEOUT = 2.minutes
    val CLIP_TRIM = TimeRange.of(Duration.ZERO, 1.seconds)
    val LEAD = 500.milliseconds

    // Half the clip and a third of a frame period on, so the scrub lands inside the fade and off the grid.
    val INTO_SPAN = 510.milliseconds

    const val MICROS_PER_SECOND = 1_000_000

    val WHITE_SQUARE: ByteArray = pngOf(Color.WHITE.rgb, OVERLAY_SIZE)
  }
}
