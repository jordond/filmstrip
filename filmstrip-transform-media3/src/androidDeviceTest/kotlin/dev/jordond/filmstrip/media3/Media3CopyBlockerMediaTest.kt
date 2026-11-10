package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Which sources media3's planner keeps off the copy path for reasons of media3's own, read against a real fixture whose
 * probe is changed in the one field under test.
 */
class Media3CopyBlockerMediaTest {
  private val probe = Media3ExportProbe()
  private val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()

  // media3's Transformer re-encodes a source with non-square pixels on its own when a copy lowers to one sequence, and
  // copies it with no check when the copy lowers to two, so the planner sends it down the transcode and says why.
  @Test
  fun aSourceWithNonSquarePixelsTranscodesAndNamesItsPixels() =
    runTest(timeout = TIMEOUT) {
      val plan = planOf(pixelAspectRatio = ANAMORPHIC)

      plan.path shouldBe ExportPath.Transcode
      plan.copyBlockedBy shouldContain CopyBlocker.NonSquarePixels
    }

  @Test
  fun theSameSourceWithSquarePixelsCopies() =
    runTest(timeout = TIMEOUT) {
      val plan = planOf(pixelAspectRatio = 1f)

      withClue("the untouched fixture was meant to copy, blocked by ${plan.copyBlockedBy}") {
        plan.path shouldBe ExportPath.Copy
        plan.copyBlockedBy shouldNotContain CopyBlocker.NonSquarePixels
      }
    }

  private suspend fun planOf(pixelAspectRatio: Float): ExportPlan {
    val real = chainedProber(components)
    val prober =
      MediaProber { source ->
        when (val read = real.probe(source)) {
          is ProbeResult.Success -> ProbeResult.Success(read.info.withPixelAspectRatio(pixelAspectRatio))
          else -> read
        }
      }
    val engine = media3ExportEngine(components, prober)
    val composition = Composition(listOf(Track(listOf(Clip(probe.fixture(CLIP))))))
    return when (val verdict = engine.plan(composition, ExportSpec())) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> throw AssertionError("refused: ${verdict.reasons.map { it.message }}")
    }
  }

  private fun MediaInfo.withPixelAspectRatio(ratio: Float): MediaInfo {
    val video = video ?: return this
    val shown = Size((video.codedSize.width * ratio).roundToInt(), video.codedSize.height)
    return MediaInfo(
      duration = duration,
      video =
        VideoTrackInfo(
          codedSize = video.codedSize,
          displaySize = shown,
          rotationDegrees = video.rotationDegrees,
          pixelAspectRatio = ratio,
          frameRate = video.frameRate,
          codec = video.codec,
          bitDepth = video.bitDepth,
          colorSpace = video.colorSpace,
          hdrTransfer = video.hdrTransfer,
          bitrate = video.bitrate,
          hasAlpha = false,
        ),
      audioTracks = listOfNotNull(audio),
      isExportable = isExportable,
    )
  }

  private companion object {
    const val CLIP = "android_export_a.mp4"

    // The four by three pixels of an anamorphic 1440 wide HD frame.
    const val ANAMORPHIC = 4f / 3f
  }
}
