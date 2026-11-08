package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreImage.CIImage
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A tone mapped export read back out of the file it wrote, against the shared curve.
 *
 * The graded fixtures hold one flat patch at the centre of the frame, and what the curve puts it at is worked out from
 * what the fixture itself carries there. PQ's patch saturates its red and blacks its green and blue on the curve, so
 * HLG's is the one that reads the middle of the range here, and [AppleToneMapLadderHardwareTest] reads the middle on
 * PQ.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class AppleToneMapExportHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `every frame of a clip lands on the curve`() =
    runTest(timeout = TIMEOUT) {
      HdrTransfer.entries.forEach { transfer ->
        val path = exported(clipOf(hdrFixture(transfer)), "frames-$transfer")
        val expected = curveOfFixture(transfer)
        val frames = codeFramesOf(path, count = LATER_FRAME + 1)

        listOf(0, 1, LATER_FRAME).forEach { index ->
          assertWritten(frames[index].at(CENTRE, CENTRE), expected, "$transfer frame $index")
        }
        assertWritten(frames[0].at(CENTRE, CENTRE), frames[1].at(CENTRE, CENTRE), "$transfer frame 0 against 1")
        remove(path)
      }
    }

  @Test
  fun `a trimmed export opens on the curve`() =
    runTest(timeout = TIMEOUT) {
      val trimmed =
        compositionOf { clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(TRIM_START, TRIM_START + CLIP) } }
      val path = exported(trimmed, "trim")
      assertWritten(
        codeFramesOf(path, 1).single().at(CENTRE, CENTRE),
        curveOfFixture(HdrTransfer.Hlg),
        "trimmed frame 0",
      )
      remove(path)
    }

  @Test
  fun `a pq clip and an hlg clip each land on their own curve`() =
    runTest(timeout = TIMEOUT) {
      val mixed =
        compositionOf {
          clip(MediaSource.of(hdrFixture(HdrTransfer.Pq))) { trim(Duration.ZERO, CLIP) }
          clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
        }
      val path = exported(mixed, "pq-hlg")
      val frames = codeFramesOf(path, CLIP_FRAMES + 2)
      assertWritten(frames[0].at(CENTRE, CENTRE), curveOfFixture(HdrTransfer.Pq), "the PQ clip")
      assertWritten(frames[CLIP_FRAMES].at(CENTRE, CENTRE), curveOfFixture(HdrTransfer.Hlg), "the HLG clip")
      assertWritten(
        frames[CLIP_FRAMES + 1].at(CENTRE, CENTRE),
        curveOfFixture(HdrTransfer.Hlg),
        "the frame after the join",
      )
      remove(path)
    }

  @Test
  fun `a gap is black and the clip after it lands on the curve`() =
    runTest(timeout = TIMEOUT) {
      val gapped =
        compositionOf {
          track {
            clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
            startAt(GAP)
          }
        }
      val path = exported(gapped, "gap")
      val frames = codeFramesOf(path, GAP_FRAMES + 1)
      assertWritten(frames[0].at(CENTRE, CENTRE), Rgb(0.0, 0.0, 0.0), "the gap")
      assertWritten(frames[GAP_FRAMES].at(CENTRE, CENTRE), curveOfFixture(HdrTransfer.Hlg), "the clip after the gap")
      remove(path)
    }

  @Test
  fun `a held tail holds the curve to the end`() =
    runTest(timeout = TIMEOUT) {
      val held =
        compositionOf {
          clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { trim(Duration.ZERO, CLIP) }
          track(TrackContent.Audio) {
            clip(MediaSource.of(fixture(BED_FIXTURE))) { trim(Duration.ZERO, 1.seconds) }
          }
        }
      val path = exported(held, "tail")
      val frames = codeFramesOf(path, HELD_FRAMES + 1)
      frames.size shouldBe HELD_FRAMES
      assertWritten(frames.last().at(CENTRE, CENTRE), curveOfFixture(HdrTransfer.Hlg), "the held frame")
      remove(path)
    }

  @Test
  fun `a rotation turns the tone mapped picture`() =
    runTest(timeout = TIMEOUT) {
      val hlg = hdrFixture(HdrTransfer.Hlg)
      val plainPath = exported(clipOf(hlg), "rotate-plain")
      val turned = compositionOf { clip(MediaSource.of(hlg)) { addEffects(listOf(Rotate(HALF_TURN))) } }
      val turnedPath = exported(turned, "rotate")
      val plain = codeFramesOf(plainPath, 1).single()
      val rotated = codeFramesOf(turnedPath, 1).single()
      assertWritten(rotated.at(CENTRE, CENTRE), curveOfFixture(HdrTransfer.Hlg), "the turned patch")
      BAR_CENTRES.forEach { x ->
        assertWritten(rotated.at(1f - x, 1f - QUARTER), plain.at(x, QUARTER), "the bar at $x")
      }
      remove(plainPath)
      remove(turnedPath)
    }

  @Test
  fun `fill and canvas bars write their own codes around the curve`() =
    runTest(timeout = TIMEOUT) {
      val boxed =
        compositionOf {
          clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) {
            addEffects(listOf(Crop(AspectRatio.Portrait, Fit.Contain)))
          }
          fill(Fill.Solid(FILL.toArgb()))
        }
      val path = exported(boxed, "fill")
      val frame = codeFramesOf(path, 1).single()
      assertWritten(frame.at(CENTRE, EDGE), FILL.asCodes(), "the fill")
      assertWritten(frame.at(CENTRE, CENTRE), curveOfFixture(HdrTransfer.Hlg), "the patch")
      remove(path)
    }

  @Test
  fun `an overlay draws its own codes over the tone mapped picture`() =
    runTest(timeout = TIMEOUT) {
      val png = solidPng(BADGE_PIXELS, BADGE_PIXELS, OVERLAY)
      val badge = ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart)
      val badged = compositionOf { clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { addEffects(listOf(badge)) } }
      val path = exported(badged, "overlay")
      val frame = codeFramesOf(path, 1).single()
      val size = Size(frame.width, frame.height)
      val rect = badge.placedOn(size, Size(BADGE_PIXELS, BADGE_PIXELS)).rectOn(size)
      val middle = frame.at((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
      assertWritten(middle, OVERLAY.asCodes(), "the overlay")
      assertWritten(frame.at(CENTRE, CENTRE), curveOfFixture(HdrTransfer.Hlg), "the patch")
      remove(path)
    }

  @Test
  fun `a colour effect grades the sdr codes`() =
    runTest(timeout = TIMEOUT) {
      val inverted =
        compositionOf { clip(MediaSource.of(hdrFixture(HdrTransfer.Hlg))) { addEffects(listOf(Invert())) } }
      val path = exported(inverted, "invert")
      val expected = curveOfFixture(HdrTransfer.Hlg)
      val read = codeFramesOf(path, 1).single().at(CENTRE, CENTRE)
      assertWritten(read, Rgb(1 - expected.r, 1 - expected.g, 1 - expected.b), "the inverted patch")
      remove(path)
    }

  @Test
  fun `a third party step sees sdr codes on every transfer`() =
    runTest(timeout = TIMEOUT) {
      HdrTransfer.entries.forEach { transfer ->
        val seen = AtomicReference<Rgb?>(null)
        val probing =
          Filmstrip {
            avFoundationBackend()
            addEffectResolver(
              StepResolver(
                CoreImageEffect { image, _ ->
                  if (seen.load() == null) seen.store(image.centreInBt709Codes())
                  image
                },
              ),
            )
          }
        val probed = compositionOf { clip(MediaSource.of(hdrFixture(transfer))) { effects { add(Probe) } } }
        val path = exported(probed, "probe-$transfer", probing)
        assertCodes(seen.load().shouldNotBeNull(), curveOfFixture(transfer), "what the step saw on $transfer")
        remove(path)
      }
    }

  @Test
  fun `a step that throws latches and fails the export`() =
    runTest(timeout = TIMEOUT) {
      val throwing =
        Filmstrip {
          avFoundationBackend()
          addEffectResolver(StepResolver(CoreImageEffect { _, _ -> throw IllegalStateException(REASON) }))
        }
      val composition = compositionOf { clip(MediaSource.of(hdrFixture(HdrTransfer.Pq))) { effects { add(Probe) } } }
      val status =
        withContext(Dispatchers.Default) {
          throwing.export(composition, SPEC, MediaSink.of(temporaryPath("throws"))).toList().last()
        }

      val error = status.shouldBeInstanceOf<ExportStatus.Failure>().error
      error.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>().specId shouldBe Probe.id
      error.message shouldBe REASON
    }

  private suspend fun graphOf(composition: EditComposition): AvComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, SPEC)) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition.toAvComposition()
      }
    }

  private fun clipOf(path: String): EditComposition = compositionOf { clip(MediaSource.of(path)) }

  private suspend fun exported(
    composition: EditComposition,
    name: String,
    through: Filmstrip = filmstrip,
  ): String {
    val output = temporaryPath(name)
    val status =
      withContext(Dispatchers.Default) { through.export(composition, SPEC, MediaSink.of(output)).toList().last() }
    status
      .shouldBeInstanceOf<ExportStatus.Success>()
      .info.video
      .shouldNotBeNull()
      .hdrTransfer
      .shouldBeNull()
    return output
  }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-tone-map-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private object Probe : EffectSpec {
    override val id: String = "dev.jordond.filmstrip.test.tonemapprobe"
  }

  private class StepResolver(
    private val step: CoreImageEffect,
  ) : EffectResolver {
    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? = if (spec is Probe) EffectResolution.Resolved(PlatformEffect(step)) else null
  }

  private companion object {
    const val CENTRE = 0.5f
    const val QUARTER = 0.25f
    const val EDGE = 0.02f
    const val BADGE_PIXELS = 64
    const val BED_FIXTURE = "tone_bed_880.mp4"
    const val LATER_FRAME = 5
    const val CLIP_FRAMES = 15
    const val GAP_FRAMES = 9
    const val HELD_FRAMES = 30
    const val HALF_TURN = 180
    const val REASON = "The step refused the tone mapped frame."
    val TRIM_START = 200.milliseconds
    val CLIP = 500.milliseconds
    val GAP = 300.milliseconds
    val FILL = Triple(40, 160, 200)
    val OVERLAY = Triple(200, 60, 40)
    val TIMEOUT = 3.minutes
    val SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}

private fun assertWritten(
  actual: Rgb,
  expected: Rgb,
  label: String,
) = assertCodes(actual, expected, label, WRITTEN_COLOUR_CODES)

private fun Triple<Int, Int, Int>.asCodes(): Rgb = Rgb(first / 255.0, second / 255.0, third / 255.0)

/**
 * This image's centre pixel, as the BT.709 codes it would be written with.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.centreInBt709Codes(): Rgb {
  val space = ColorSpace.Bt709.createCodeSpace()
  try {
    return pixelAtCentre(space)
  } finally {
    CGColorSpaceRelease(space)
  }
}
