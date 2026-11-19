package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A solid, a clip and a solid, seeked to on every backend.
 *
 * Inside each solid the preview reads the solid's colour at the centre and at a corner, and at every read, the two
 * seams included, the preview matches the export at the same slot.
 *
 * Camel case test names, for the reason [PlayerEngineContractTest] gives.
 */
abstract class SolidParityContractTest(
  backend: ClipParityBackend,
) : ClipParityBackend by backend {
  @Test
  fun aSolidEitherSideOfAClipDrawsItsColourAndMatchesTheExport() =
    contractTest { scope ->
      val edit = edit()
      val clipOpens = outputSlotOf(SPAN, frameRate)
      val clipCloses = outputSlotOf(SPAN * 2, frameRate)
      val inFirst = slotTime(outputSlotOf(SPAN / 2, frameRate))
      val inLast = slotTime(outputSlotOf(SPAN * 5 / 2, frameRate))
      val seams = listOf(clipOpens - 1, clipOpens, clipCloses - 1, clipCloses).map(::slotTime)

      val engine = createEngine(scope)
      withEngine(engine) { recorder ->
        engine.setQualityPolicy(PreviewQualityPolicy.Full)
        engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the solids to be presentable") { recorder.lastState.hasComposition }

        val first = engine.readback.awaitFrame(inFirst)
        val last = engine.readback.awaitFrame(inLast)
        assertSolid(first.asTestFrame(), FIRST, "the leading solid at $inFirst")
        assertSolid(last.asTestFrame(), LAST, "the trailing solid at $inLast")

        listOf(
          first,
          last,
        ).forEach { assertMatchesExport(edit, it, minSsimAt(outputSlotOf(it.presentationTime, frameRate))) }
        seams.forEachIndexed { index, at ->
          val slot = outputSlotOf(at, frameRate)
          // The middle two are the clip's own first and last frames, after a cut from a solid.
          val floor = if (index == 1 || index == 2) minOf(minSsimAt(slot), minSsimAfterSolid) else minSsimAt(slot)
          assertMatchesExport(edit, engine.readback.awaitFrame(at), floor)
        }
      }
    }

  private fun slotTime(slot: Long): Duration = outputSlotTime(slot, frameRate)

  private fun assertSolid(
    frame: TestFrame,
    color: Int,
    what: String,
  ) {
    val width = frame.size.width
    val height = frame.size.height
    listOf(width / 2 to height / 2, CORNER to CORNER, width - 1 - CORNER to height - 1 - CORNER).forEach { (x, y) ->
      val at = (y * width + x) * BYTES_PER_PIXEL
      val read = List(RGB) { frame.pixels[at + it].toInt() and CHANNEL_MASK }
      val expected = AuthoredColors.channelsOf(color)
      withClue("$what read $read at $x,$y, expected $expected") {
        read.zip(expected).all { (got, want) -> abs(got - want) <= TOLERANCE }.shouldBeTrue()
      }
    }
  }

  private suspend fun assertMatchesExport(
    edit: Composition,
    shown: ReadbackFrame,
    floor: Double,
  ) {
    assertFramesSimilar(
      expected = comparedPart(exportFrame(edit, shown.presentationTime)),
      actual = comparedPart(shown.asTestFrame()),
      minPsnrDb = minPsnrDb,
      minSsim = floor,
      message = "the preview and the export disagree at ${shown.presentationTime}",
    )
  }

  private fun edit(): Composition =
    Composition(
      listOf(
        Track(
          listOf(
            Clip(MediaSource.Solid(FIRST, SPAN)),
            Clip(fixtureClip, TimeRange.of(Duration.ZERO, SPAN)),
            Clip(MediaSource.Solid(LAST, SPAN)),
          ),
        ),
      ),
    )

  private companion object {
    val SPAN = 400.milliseconds
    const val FIRST = AuthoredColors.MID_GREY
    const val LAST = AuthoredColors.MID_GREEN

    // Two pixels in from the edge, clear of any one-pixel scaling seam.
    const val CORNER = 2
    const val BYTES_PER_PIXEL = 4
    const val RGB = 3
    const val CHANNEL_MASK = 0xFF
    val TOLERANCE = AuthoredColors.TOLERANCE.toInt()
  }
}
