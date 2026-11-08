package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.AvCompositor
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetImageGenerator
import platform.CoreGraphics.CGSizeMake
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A tone mapped plan read the way a preview and a thumbnail read it, off `AVAssetImageGenerator`.
 *
 * The generator takes the same video composition a player does, so what comes back here is what a preview shows. HLG's
 * fixture is the one whose patch sits in the middle of the range.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleToneMapRenderHardwareTest {
  @Test
  fun `a generator renders through the compositor`() =
    runTest(timeout = TIMEOUT) {
      val graph = graphOf(clipOf(hdrFixture(HdrTransfer.Hlg)))
      (generatorFor(graph).customVideoCompositor is AvCompositor).shouldBeTrue()
    }

  @Test
  fun `a plan on the handler has no compositor`() =
    runTest(timeout = TIMEOUT) {
      val graph = graphOf(clipOf(fixture(HD_SDR_FIXTURE)))
      (generatorFor(graph).customVideoCompositor is AvCompositor).shouldBeFalse()
    }

  @Test
  fun `frame 0 off a generator lands on the curve on every transfer`() =
    runTest(timeout = TIMEOUT) {
      HdrTransfer.entries.forEach { transfer ->
        val generator = generatorFor(graphOf(clipOf(hdrFixture(transfer))))
        val expected = curveOfFixture(transfer)
        assertCodes(generator.codesAt(Duration.ZERO), expected, "$transfer at frame 0")
        assertCodes(generator.codesAt(LATER), expected, "$transfer later on")
      }
    }

  @Test
  fun `a generator capped below the render size still draws the whole frame`() =
    runTest(timeout = TIMEOUT) {
      val graph = graphOf(clipOf(hdrFixture(HdrTransfer.Hlg)))
      val whole = generatedCodeFrame(generatorFor(graph), LATER)
      val capped =
        generatorFor(graphOf(clipOf(hdrFixture(HdrTransfer.Hlg)))).apply {
          maximumSize = CGSizeMake(whole.width / 2.0, whole.height / 2.0)
        }
      val small = generatedCodeFrame(capped, LATER)
      small.width shouldBe whole.width / 2
      assertCodes(small.at(CENTRE, CENTRE), whole.at(CENTRE, CENTRE), "the patch")
      EVERY_BAR.forEach { x -> assertCodes(small.at(x, QUARTER), whole.at(x, QUARTER), "the bar at $x") }
    }

  @Test
  fun `a parameter swap reaches the frames through the same compositor`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Hlg)
      val dim = resolved(brightened(source, DIM))
      val bright = resolved(brightened(source, BRIGHT))
      val graph = dim.toAvComposition()
      val generator = generatorFor(graph)
      val freshBright = generatorFor(bright.toAvComposition()).codesAt(LATER)
      val before = generator.codesAt(LATER)
      assertCodes(before, generatorFor(dim.toAvComposition()).codesAt(LATER), "the dim render")

      graph.updateParameters(bright)

      val after = generator.codesAt(LATER)
      withClue("the swap read $after against $before") { before.movedFrom(after).shouldBeTrue() }
      assertCodes(after, freshBright, "the swapped render against a fresh build of the same edit")
      (generator.customVideoCompositor is AvCompositor).shouldBeTrue()
    }

  @Test
  fun `a held tail reads the same on every repeat`() =
    runTest(timeout = TIMEOUT) {
      // The tail holds the SDR clip, whose buffer macOS retags in place, so the second read sees what the first left.
      // An HD clip ahead of it in the SDR edit scales it to the output size the tail draws at.
      val source = fixture(SDR_FIXTURE)
      val held =
        compositionOf {
          clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
          clip(MediaSource.of(source)) { trim(Duration.ZERO, CLIP) }
          track(TrackContent.Audio) {
            clip(MediaSource.of(fixture(BED_FIXTURE))) { trim(Duration.ZERO, TAIL_END) }
          }
        }
      val graph = graphOf(held)
      val firstHeld = renderedFrameOf(graph, FIRST_TAIL_FRAME)
      val lastHeld = renderedFrameOf(graph, LAST_TAIL_FRAME)
      val again = renderedFrameOf(graph, LAST_TAIL_FRAME)
      val alone =
        renderedFrameOf(
          graphOf(
            compositionOf {
              clip(MediaSource.of(fixture(HD_SDR_FIXTURE))) { trim(Duration.ZERO, CLIP) }
              clip(MediaSource.of(source)) { trim(Duration.ZERO, CLIP) }
            },
          ),
          LAST_CLIP_FRAME,
        )
      EVERY_BAR.forEach { x ->
        assertCodes(lastHeld.at(x, QUARTER), firstHeld.at(x, QUARTER), "the last held frame at $x", tolerance = 0.0)
        assertCodes(again.at(x, QUARTER), lastHeld.at(x, QUARTER), "a second read of it at $x", tolerance = 0.0)
        assertCodes(firstHeld.at(x, QUARTER), alone.at(x, QUARTER), "the tail against the clip alone at $x", 0.0)
      }
    }

  private fun clipOf(path: String): EditComposition = compositionOf { clip(MediaSource.of(path)) }

  private fun brightened(
    path: String,
    factor: Float,
  ): EditComposition = compositionOf { clip(MediaSource.of(path)) { addEffects(listOf(Brightness(factor))) } }

  private suspend fun graphOf(composition: EditComposition): AvComposition = resolved(composition).toAvComposition()

  private suspend fun resolved(composition: EditComposition): ResolvedComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, SPEC)) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  private fun generatorFor(graph: AvComposition): AVAssetImageGenerator =
    AVAssetImageGenerator(asset = graph.composition).apply {
      videoComposition = graph.videoComposition
      requestedTimeToleranceBefore = Duration.ZERO.toCMTime()
      requestedTimeToleranceAfter = Duration.ZERO.toCMTime()
    }

  private fun AVAssetImageGenerator.codesAt(time: Duration): Rgb = generatedCodeFrame(this, time).at(CENTRE, CENTRE)

  private fun Rgb.movedFrom(other: Rgb): Boolean = listOf(r - other.r, g - other.g, b - other.b).any { abs(it) > MOVED }

  private companion object {
    const val CENTRE = 0.5f
    const val QUARTER = 0.25f
    const val SDR_FIXTURE = "apple_export_a.mp4"
    const val HD_SDR_FIXTURE = "apple_export_long.mp4"
    const val BED_FIXTURE = "tone_bed_880.mp4"
    const val BARS = 8
    const val DIM = 0.7f
    const val BRIGHT = 1.3f
    const val MOVED = 0.05
    const val LAST_CLIP_FRAME = 29
    const val FIRST_TAIL_FRAME = 30
    const val LAST_TAIL_FRAME = 40
    val EVERY_BAR = (0 until BARS).map { (it + CENTRE) / BARS }
    val LATER = 200.milliseconds
    val CLIP = 500.milliseconds
    val TAIL_END = 1500.milliseconds
    val TIMEOUT = 3.minutes
    val SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}
