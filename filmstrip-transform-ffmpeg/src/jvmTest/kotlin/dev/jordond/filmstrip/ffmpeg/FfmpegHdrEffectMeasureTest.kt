package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where the wrong answers [FfmpegHdrEffectTest] names alike on a plane land on the HDR fixture's patch.
 *
 * Each wrong answer in [FLATTENING_ALIKE] lands within twice [CODE_TOLERANCE] of [FLATTENING]'s prediction on every
 * plane it names, which is why that case leaves those planes out of telling the two apart. This fails once the
 * fixture's patch moves a named plane further apart, which says it no longer needs naming alike.
 */
class FfmpegHdrEffectMeasureTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `each wrong answer named alike lands its planes alongside the prediction`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = fixture.authoredSignal()
      val expected = tenBitCodesFromSignal(pqGraded(lit, FLATTENING))
      val wrongs = flatteningWrongs(lit)

      val apart =
        FLATTENING_ALIKE.flatMap { (name, planes) ->
          val wrong = tenBitCodesFromSignal(wrongs.getValue(name))
          planes.mapNotNull { plane ->
            val miss = abs(wrong[plane.ordinal] - expected[plane.ordinal])
            "$plane: grading from $name lands $miss codes away, so it no longer needs naming alike".takeIf {
              2 * CODE_TOLERANCE < miss
            }
          }
        }

      assertTrue(apart.isEmpty(), apart.joinToString("\n"))
    }
}
