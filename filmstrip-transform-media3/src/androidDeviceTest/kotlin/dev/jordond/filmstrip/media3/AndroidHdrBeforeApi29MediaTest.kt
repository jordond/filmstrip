package dev.jordond.filmstrip.media3

import android.os.Build
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.media3.internal.hdrDecodeLimit
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * Media3's own refusal to decode an HDR source below API 29, which the limits page names.
 *
 * The plan refuses every way of handling an HDR clip that decodes it there, with [hdrDecodeLimit] as its reason, so
 * the export never starts and fails part way through.
 */
class AndroidHdrBeforeApi29MediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun anHdrClipThatMustBeDecodedIsRefusedAtThePlan() =
    runTest(timeout = TIMEOUT) {
      assumeTrue("This test pins media3 below API 29", Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
      val composition = Composition(listOf(Track(listOf(Clip(probe.fixture(HDR_CLIP))))))

      listOf(HdrMode.ToneMapToSdr, HdrMode.Auto).forEach { mode ->
        val verdict = probe.filmstrip.plan(composition, ExportSpec(heightPx = HEIGHT, hdr = mode))

        verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.map { it.message } shouldContain
          hdrDecodeLimit(Build.VERSION.SDK_INT)
      }
    }

  private companion object {
    // Below the fixture's own 720, so the export re-encodes rather than copies.
    const val HEIGHT = 360
  }
}
