package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.HdrPreviewFrame
import dev.jordond.filmstrip.player.internal.HdrPreviewResult
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.HdrOverlayCells
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Solids on a kept HDR grade, read through each backend's HDR preview read and its export.
 *
 * An opaque solid ahead of an HDR clip lands on the shared codes of its authored light, a translucent one lands where
 * it blends over the fill in display light, and at every read the preview matches the export. A backend with no HDR
 * encoder has no export to compare with and skips.
 *
 * Camel case test names, for the reason [PlayerEngineContractTest] gives.
 */
internal abstract class HdrSolidParityContractTest(
  backend: HdrParityBackend,
) : HdrParityBackend by backend {
  @Test
  fun anOpaqueSolidAheadOfAnHdrClipLandsOnTheSharedCodes() =
    contractTest(HDR_BODY_TIMEOUT) { scope ->
      if (!encodesHdr()) return@contractTest

      val misses =
        HdrTransfer.entries.flatMap { transfer ->
          val clip = Clip(hdrFixture(transfer), TimeRange.of(Duration.ZERO, SPAN))
          val edit = edit(listOf(Clip(MediaSource.Solid(SOLID, SPAN)), clip), Fill.Solid(HdrOverlayCells.BLACK))
          val solid = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(SOLID))).toList()
          val inSolid = SPAN / 2
          val inClip = SPAN + SPAN / 2
          readings(scope, edit, listOf(inSolid, inClip)) { frames ->
            val (shownSolid, shownClip) = frames
            listOfNotNull(
              missOf("$transfer solid centre", shownSolid.codesNear(CENTRE, CENTRE), solid),
              missOf("$transfer solid corner", shownSolid.codesAt(CORNER, CORNER).toList(), solid),
              missOf(
                "$transfer solid against the export",
                shownSolid.codesNear(CENTRE, CENTRE),
                exportHdrCodes(edit, inSolid, CENTRE, CENTRE).toList(),
              ),
              missOf(
                "$transfer clip against the export",
                shownClip.codesNear(CENTRE, CENTRE),
                exportHdrCodes(edit, inClip, CENTRE, CENTRE).toList(),
              ),
              missOf(
                "$transfer clip off its middle against the export",
                shownClip.codesNear(OFF_X, OFF_Y),
                exportHdrCodes(edit, inClip, OFF_X, OFF_Y).toList(),
                ORIENTATION_TOLERANCE,
              ),
            )
          }
        }
      withClue(misses.joinToString("\n")) { misses.shouldBeEmpty() }
    }

  // Each cell is a solid of its colour with its opacity as the alpha, one span each ahead of the HDR clip.
  @Test
  fun aTranslucentSolidBlendsOverTheFillInLight() =
    contractTest(HDR_BODY_TIMEOUT) { scope ->
      if (!encodesHdr()) return@contractTest

      val misses =
        HdrTransfer.entries.flatMap { transfer ->
          HdrOverlayCells.BLENDS.groupBy { it.background }.flatMap { (background, cells) ->
            val alphas = cells.map { (it.opacity * OPAQUE_ALPHA).roundToInt() }
            val solids =
              cells.zip(alphas).map { (cell, alpha) ->
                Clip(MediaSource.Solid((alpha shl ALPHA_SHIFT) or (cell.overlay and RGB), SPAN))
              }
            val clip = Clip(hdrFixture(transfer), TimeRange.of(Duration.ZERO, SPAN))
            val edit = edit(solids + clip, Fill.Solid(background))
            val positions = cells.indices.map { SPAN * it + SPAN / 2 }
            readings(scope, edit, positions) { frames ->
              cells.zip(alphas).zip(frames).flatMapIndexed { index, (laid, shown) ->
                val (cell, alpha) = laid
                val drawn = cell.copy(opacity = alpha / OPAQUE_ALPHA.toFloat())
                val read = shown.codesNear(CENTRE, CENTRE)
                listOfNotNull(
                  missOf("$transfer $drawn", read, drawn.codes(transfer).toList()),
                  missOf(
                    "$transfer $drawn against the export",
                    read,
                    exportHdrCodes(edit, positions[index], CENTRE, CENTRE).toList(),
                  ),
                )
              }
            }
          }
        }
      withClue(misses.joinToString("\n")) { misses.shouldBeEmpty() }
    }

  private suspend fun readings(
    scope: CoroutineScope,
    edit: Composition,
    positions: List<Duration>,
    check: suspend (List<HdrPreviewFrame>) -> List<String>,
  ): List<String> {
    val engine = createEngine(scope)
    var misses = emptyList<String>()
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the HDR edit to be presentable") { recorder.lastState.hasComposition }
      val read = hdrReadback(engine)
      val frames =
        positions.map { at ->
          read.hdrFrameAt(at).shouldBeInstanceOf<HdrPreviewResult.Success>().frame
        }
      misses = check(frames)
    }
    return misses
  }

  private fun HdrPreviewFrame.codesNear(
    x: Double,
    y: Double,
  ): List<Int> = codesAt((size.width * x).toInt(), (size.height * y).toInt()).toList()

  private fun missOf(
    what: String,
    read: List<Int>,
    expected: List<Int>,
    tolerance: Int = hdrCodeTolerance,
  ): String? =
    "$what read $read, expected $expected".takeUnless {
      read.zip(expected).all { (got, want) -> abs(got - want) <= tolerance }
    }

  private fun edit(
    clips: List<Clip>,
    fill: Fill,
  ): Composition = Composition(tracks = listOf(Track(clips)), audio = AudioSpec.Remove, fill = fill)

  private companion object {
    val SPAN = 200.milliseconds
    const val SOLID = AuthoredColors.MID_GREEN
    const val CENTRE = 0.5

    // A point on the HDR fixture's lower strip whose row mirrored about the middle holds a bar about 200 luma codes
    // darker, so a frame read upside down fails there. The strip is camera footage with grain of a few codes a pixel,
    // which an encode moves by up to 12, so the read is held to ORIENTATION_TOLERANCE and the centre holds the codes.
    const val OFF_X = 0.328125
    const val OFF_Y = 0.796875
    const val ORIENTATION_TOLERANCE = 32

    // Two pixels in from the edge, clear of any one-pixel scaling seam.
    const val CORNER = 2
    const val OPAQUE_ALPHA = 255
    const val ALPHA_SHIFT = 24
    const val RGB = 0xFFFFFF

    // Each case exports once per transfer and fill, which a backend encoding on the host needs longer for.
    val HDR_BODY_TIMEOUT = 240_000.milliseconds
  }
}
