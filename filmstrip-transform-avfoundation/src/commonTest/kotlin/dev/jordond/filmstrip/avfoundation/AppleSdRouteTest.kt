package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.avfoundation.internal.Placement
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.SdSourceColor
import dev.jordond.filmstrip.avfoundation.internal.composition.compositorClass
import dev.jordond.filmstrip.avfoundation.internal.composition.sdRouteTransferLimit
import dev.jordond.filmstrip.avfoundation.internal.composition.sdrTransferLimit
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.AppleScratch
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObject
import kotlinx.cinterop.objcPtr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPrimaries_P3_D65
import platform.AVFoundation.AVVideoColorPrimaries_SMPTE_C
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_601_4
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Which of the two routes a plan draws through once it holds an SD clip, and what it reads there.
 *
 * Every plan with an SD clip goes through the compositor, so a clip reads the same with or without HDR beside it.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSdRouteTest {
  @Test
  fun `a plan holding an sd clip draws through the compositor`() =
    runTest(timeout = TIMEOUT) {
      val sd = tagged("route-sd", AVVideoColorPrimaries_SMPTE_C, AVVideoYCbCrMatrix_ITU_R_601_4)
      val m601 = tagged("route-m601", AVVideoColorPrimaries_ITU_R_709_2, AVVideoYCbCrMatrix_ITU_R_601_4)
      val bt709 = tagged("route-709", AVVideoColorPrimaries_ITU_R_709_2, AVVideoYCbCrMatrix_ITU_R_709_2)
      val untagged = temporaryPath("route-untagged")
      withContext(Dispatchers.Default) { writeUntaggedBars(untagged, FRAMES, width = 640, height = 360) }

      listOf(alone(sd), alone(m601), alone(untagged), inOrder(bt709, sd), inOrder(sd, bt709)).forEach { plan ->
        withClue(plan.toString()) { graphOf(plan).drawsThroughCompositor() shouldBe true }
      }
      listOf(sd, m601, bt709, untagged).forEach(::remove)
    }

  @Test
  fun `a plan with no sd clip stays on the handler`() =
    runTest(timeout = TIMEOUT) {
      val bt709 = tagged("handler-709", AVVideoColorPrimaries_ITU_R_709_2, AVVideoYCbCrMatrix_ITU_R_709_2)
      val p3 = tagged("handler-p3", AVVideoColorPrimaries_P3_D65, AVVideoYCbCrMatrix_ITU_R_709_2)
      val hd = temporaryPath("handler-hd")
      withContext(Dispatchers.Default) { writeUntaggedBars(hd, FRAMES, width = 1280, height = 720) }
      val photo = ImageSource.of(photoFixture("filmstrip-sd-route-still", Size(640, 360), Triple(170, 120, 90)))

      val still = compositionOf { image(photo, CLIP) }
      listOf(alone(bt709), alone(p3), alone(hd), inOrder(bt709, p3), still).forEach { plan ->
        withClue(plan.toString()) { graphOf(plan).drawsThroughCompositor() shouldBe false }
      }
      listOf(bt709, p3, hd).forEach(::remove)
    }

  @Test
  fun `a tone mapped plan draws through the compositor with or without an sd clip`() =
    runTest(timeout = TIMEOUT) {
      val bt709 = tagged("tone-709", AVVideoColorPrimaries_ITU_R_709_2, AVVideoYCbCrMatrix_ITU_R_709_2)
      val hlg = hdrFixture(HdrTransfer.Hlg)
      graphOf(inOrder(hlg, bt709), TONE_MAP_SPEC).drawsThroughCompositor() shouldBe true
      remove(bt709)
    }

  @Test
  fun `an sd clip reads the same alone and beside an hdr clip`() =
    runTest(timeout = TIMEOUT) {
      listOf("mid" to null, "saturated" to OUT_OF_GAMUT_BARS).forEach { (name, bars) ->
        val sd = temporaryPath("same-$name")
        val primaries = AVVideoColorPrimaries_SMPTE_C.orEmpty()
        val matrix = AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty()
        withContext(Dispatchers.Default) {
          if (bars == null) {
            writeTaggedBars(sd, CLIP_FRAMES, primaries, matrix)
          } else {
            writeTaggedBars(sd, CLIP_FRAMES, primaries, matrix, bars)
          }
        }
        val alone = withContext(Dispatchers.Default) { renderedFrameOf(graphOf(alone(sd), TONE_MAP_SPEC), 0) }
        val beside =
          withContext(Dispatchers.Default) {
            renderedFrameOf(graphOf(inOrder(hdrFixture(HdrTransfer.Hlg), sd), TONE_MAP_SPEC), CLIP_FRAMES)
          }
        EVERY_BAR.forEach { x ->
          assertCodes(beside.at(x, MIDDLE), alone.at(x, MIDDLE), "$name bar at $x", tolerance = 0.0)
        }
        remove(sd)
      }
    }

  @Test
  fun `an sd clip and a bt709 clip in one plan both read true`() =
    runTest(timeout = TIMEOUT) {
      val sd = tagged("both-sd", AVVideoColorPrimaries_SMPTE_C, AVVideoYCbCrMatrix_ITU_R_601_4)
      val bt709 = tagged("both-709", AVVideoColorPrimaries_ITU_R_709_2, AVVideoYCbCrMatrix_ITU_R_709_2)
      val honestSd = codeFramesOf(sd, 1).first()
      val honest709 = codeFramesOf(bt709, 1).first()
      val sdFirst = graphOf(inOrder(sd, bt709))
      val bt709First = graphOf(inOrder(bt709, sd))
      val reads =
        withContext(Dispatchers.Default) {
          listOf(
            renderedFrameOf(sdFirst, 0),
            renderedFrameOf(sdFirst, CLIP_FRAMES),
            renderedFrameOf(bt709First, 0),
            renderedFrameOf(bt709First, CLIP_FRAMES),
          )
        }
      EVERY_BAR.forEach { x ->
        val shown = honestSd.at(x, MIDDLE).displayed()
        val plain = honest709.at(x, MIDDLE)
        assertCodes(reads[0].at(x, MIDDLE), shown, "the SD clip opening the plan at $x", SD_CODES)
        assertCodes(reads[1].at(x, MIDDLE), plain, "the BT.709 clip after it at $x", SD_CODES)
        assertCodes(reads[2].at(x, MIDDLE), plain, "the BT.709 clip opening the plan at $x", SD_CODES)
        assertCodes(reads[3].at(x, MIDDLE), shown, "the SD clip after it at $x", SD_CODES)
      }
      listOf(sd, bt709).forEach(::remove)
    }

  @Test
  fun `an sd clip as a buffer draws through the compositor and reads as its file displays`() =
    runTest(timeout = TIMEOUT) {
      val sd = tagged("buffer-sd", AVVideoColorPrimaries_SMPTE_C, AVVideoYCbCrMatrix_ITU_R_601_4)
      val honest = codeFramesOf(sd, 1).first()
      val buffer = MediaSource.Bytes(readBytes(sd))
      val graph = graphOf(compositionOf { clip(buffer) { trim(Duration.ZERO, CLIP) } })

      graph.drawsThroughCompositor() shouldBe true
      val read = withContext(Dispatchers.Default) { renderedFrameOf(graph, 0) }
      EVERY_BAR.forEach { x ->
        assertCodes(read.at(x, MIDDLE), honest.at(x, MIDDLE).displayed(), "the buffer at $x", SD_CODES)
      }
      remove(sd)
    }

  @Test
  fun `a clip beside an sd clip that needs the pixel transfer is refused where it does not exist`() =
    runTest(timeout = TIMEOUT) {
      val sd = tagged("limit-sd", AVVideoColorPrimaries_SMPTE_C, AVVideoYCbCrMatrix_ITU_R_601_4)
      val p3 = tagged("limit-p3", AVVideoColorPrimaries_P3_D65, AVVideoYCbCrMatrix_ITU_R_709_2)
      val bt709 = tagged("limit-709", AVVideoColorPrimaries_ITU_R_709_2, AVVideoYCbCrMatrix_ITU_R_709_2)
      val sdColor = SdSourceColor(SMPTE_C_PRIMARIES, BT709, BT601)

      val beside = placed(clipOf(sd), sdColor) + placed(clipOf(p3))
      sdRouteTransferLimit(beside, AppleScratch.defaultRoot, available = false) shouldBe
        sdrTransferLimit(available = false)
      sdRouteTransferLimit(beside, AppleScratch.defaultRoot, available = true).shouldBeNull()
      sdRouteTransferLimit(
        placed(clipOf(sd), sdColor) + placed(clipOf(bt709)),
        AppleScratch.defaultRoot,
        available = false,
      ).shouldBeNull()
      listOf(sd, p3, bt709).forEach(::remove)
    }

  private fun placed(
    clip: ResolvedClip,
    color: SdSourceColor? = null,
  ): List<Placement> = listOf(Placement(clip = clip, start = Duration.ZERO, end = CLIP, sdColor = color))

  private suspend fun clipOf(path: String): ResolvedClip =
    resolved(alone(path), SPEC)
      .composition.tracks
      .first()
      .clips
      .first()

  private fun AvComposition.drawsThroughCompositor(): Boolean {
    // The handler's composition carries a compositor class of its own, so being set says nothing.
    val drawnBy = videoComposition?.customVideoCompositorClass as? ObjCObject
    return drawnBy?.objcPtr() == (compositorClass() as ObjCObject).objcPtr()
  }

  private fun Rgb.displayed(): Rgb {
    val shown = sdSourceDisplayed(doubleArrayOf(r, g, b), SMPTE_C_PRIMARIES)
    return Rgb(shown[0], shown[1], shown[2])
  }

  private suspend fun tagged(
    name: String,
    primaries: String?,
    matrix: String?,
  ): String {
    val path = temporaryPath(name)
    withContext(Dispatchers.Default) { writeTaggedBars(path, CLIP_FRAMES, primaries.orEmpty(), matrix.orEmpty()) }
    return path
  }

  private fun alone(path: String): EditComposition =
    compositionOf { clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) } }

  private fun inOrder(
    first: String,
    second: String,
  ): EditComposition =
    compositionOf {
      clip(MediaSource.of(first)) { trim(Duration.ZERO, CLIP) }
      clip(MediaSource.of(second)) { trim(Duration.ZERO, CLIP) }
    }

  private suspend fun resolved(
    composition: EditComposition,
    spec: ExportSpec,
  ): ResolveResult.Resolved {
    val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
    return when (val result = avFoundationExportEngine(components).resolve(composition, spec)) {
      is ResolveResult.Refused -> error(result.error.message)
      is ResolveResult.Resolved -> result
    }
  }

  private suspend fun graphOf(
    composition: EditComposition,
    spec: ExportSpec = SPEC,
  ): AvComposition = withContext(Dispatchers.Default) { resolved(composition, spec).composition.toAvComposition() }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-sd-route-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val FRAMES = 5
    const val CLIP_FRAMES = 15
    const val BARS = 8
    const val MIDDLE = 0.5f
    const val BT709 = 1
    const val BT601 = 6

    // One 8-bit chroma step of the clip's H.264 against the same frame's honest read.
    const val SD_CODES = 2.0
    val EVERY_BAR = (0 until BARS).map { (it + MIDDLE) / BARS }
    val CLIP = 500.milliseconds
    val TIMEOUT = 3.minutes
    val SPEC = ExportSpec(heightPx = 360)
    val TONE_MAP_SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}
