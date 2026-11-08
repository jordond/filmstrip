package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.AssetCache
import dev.jordond.filmstrip.avfoundation.internal.chain.SD_PRIMARIES_KERNEL_LIMIT
import dev.jordond.filmstrip.avfoundation.internal.chain.sdPrimariesKernelLimit
import dev.jordond.filmstrip.avfoundation.internal.chain.sdPrimariesMoved
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.SdSourceColor
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.laidPlan
import dev.jordond.filmstrip.avfoundation.internal.laidTrack
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdPrimariesToBt709
import dev.jordond.filmstrip.color.sdSourceDisplayed
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPrimaries_SMPTE_C
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_601_4
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.dataWithBytes
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * An SD clip through a composition naming BT.709, against the colour the shared function says it displays as.
 *
 * Every expected value starts from the file's own honest read, what a reader with no composition hands back, and goes
 * through `sdSourceDisplayed`.
 */
@OptIn(ExperimentalForeignApi::class)
class SdColorHeadTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a clip tagged smpte 170m renders the colour it displays as`() =
    runTest(timeout = TIMEOUT) { assertRendersDisplayed(smpteC("render"), SMPTE_C_PRIMARIES) }

  @Test
  fun `a clip tagged ebu renders the colour it displays as`() =
    runTest(timeout = TIMEOUT) {
      val path = temporaryPath("ebu")
      withContext(Dispatchers.Default) { writeEbuBars(path, FRAMES) }
      assertRendersDisplayed(path, EBU_PRIMARIES)
    }

  @Test
  fun `an untagged clip read as sd renders the colour it displays as`() =
    runTest(timeout = TIMEOUT) {
      val path = temporaryPath("untagged")
      withContext(Dispatchers.Default) { writeUntaggedBars(path, FRAMES, width = 640, height = 360) }
      assertRendersDisplayed(path, SMPTE_C_PRIMARIES)
    }

  @Test
  fun `an exported sd clip holds the colour it displays as`() =
    runTest(timeout = TIMEOUT) {
      val path = smpteC("export")
      // An effect forces the encode, and two of them cancel out, so the frame reads the source's own colour.
      val output = temporaryPath("export-out")
      withContext(Dispatchers.Default) {
        filmstrip
          .export(invertedTwice(path), SPEC, MediaSink.of(output))
          .toList()
          .last()
          .shouldBeInstanceOf<ExportStatus.Success>()
      }
      val written = codeFramesOf(output, 1).first()
      val honest = codeFramesOf(path, 1).first()
      EVERY_BAR.forEach { x ->
        assertCodes(
          written.at(x, MIDDLE),
          honest.at(x, MIDDLE).displayed(SMPTE_C_PRIMARIES),
          "written bar at $x",
          WRITTEN_COLOUR_CODES,
        )
      }
      remove(output)
      remove(path)
    }

  @Test
  fun `a step reads the colour the clip displays as`() =
    runTest(timeout = TIMEOUT) {
      val path = smpteC("step")
      val honest = codeFramesOf(path, 1).first()
      val rendered = renderedFrameOf(graphOf(inverted(path)), 0)
      EVERY_BAR.forEach { x ->
        val shown = honest.at(x, MIDDLE).displayed(SMPTE_C_PRIMARIES)
        assertCodes(rendered.at(x, MIDDLE), Rgb(1 - shown.r, 1 - shown.g, 1 - shown.b), "inverted bar at $x", SD_CODES)
      }
      remove(path)
    }

  @Test
  fun `a bt709 clip gets nothing added`() =
    runTest(timeout = TIMEOUT) {
      val path = temporaryPath("bt709")
      withContext(Dispatchers.Default) {
        writeTaggedBars(
          path,
          FRAMES,
          AVVideoColorPrimaries_ITU_R_709_2.orEmpty(),
          AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty(),
        )
      }
      val graph = graphOf(alone(path))
      graph.chain
        .shouldNotBeNull()
        .spans
        .single()
        .sdColor
        .shouldBeNull()
      val honest = codeFramesOf(path, 1).first()
      val rendered = renderedFrameOf(graph, 0)
      EVERY_BAR.forEach { x -> assertCodes(rendered.at(x, MIDDLE), honest.at(x, MIDDLE), "BT.709 bar at $x") }
      remove(path)
    }

  @Test
  fun `a colour holds the matrix for its primaries`() {
    SdSourceColor(SMPTE_C_PRIMARIES, BT709, BT601).primariesToBt709.shouldNotBeNull().toList() shouldBe
      sdPrimariesToBt709(SMPTE_C_PRIMARIES).toList()
    SdSourceColor(EBU_PRIMARIES, BT709, BT470BG).primariesToBt709.shouldNotBeNull().toList() shouldBe
      sdPrimariesToBt709(EBU_PRIMARIES).toList()
    SdSourceColor(BT709, BT709, BT601).primariesToBt709.shouldBeNull()
  }

  @Test
  fun `the kernel lands where the shared function does`() {
    KERNEL_COLOURS.forEach { colour ->
      val shown = sdSourceDisplayed(colour, SMPTE_C_PRIMARIES)
      val moved =
        flatCodes(
          colour,
        ).sdPrimariesMoved(SdSourceColor(SMPTE_C_PRIMARIES, BT709, BT601)).pixelAtCentre(space = null)
      assertCodes(moved, Rgb(shown[0], shown[1], shown[2]), "${colour.toList()} through the kernel", KERNEL_CODES)
    }
  }

  @Test
  fun `the kernel leaves a colour with no sd primaries alone`() {
    val frame = CIImage()
    frame.sdPrimariesMoved(SdSourceColor(BT709, BT709, BT601)) shouldBeSameInstanceAs frame
  }

  @Test
  fun `a device that cannot compile the kernel names the limit`() {
    sdPrimariesKernelLimit(kernelsExist = false) shouldBe SD_PRIMARIES_KERNEL_LIMIT
    sdPrimariesKernelLimit(kernelsExist = true, compiles = { false }) shouldBe SD_PRIMARIES_KERNEL_LIMIT
    sdPrimariesKernelLimit(kernelsExist = true, compiles = { true }).shouldBeNull()
    sdPrimariesKernelLimit().shouldBeNull()
  }

  @Test
  fun `an sd clip the kernel cannot draw is refused when it is laid`() =
    runTest(timeout = TIMEOUT) {
      val path = smpteC("limit")
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      val clip =
        (avFoundationExportEngine(components).resolve(alone(path), SPEC) as ResolveResult.Resolved)
          .composition.tracks
          .first()
          .clips
          .first()
      val refused = AssetCache(kernelLimit = { SD_PRIMARIES_KERNEL_LIMIT })
      assertFailsWith<AvLoweringFailure> { refused.sdColorOf(clip) }.reason shouldBe SD_PRIMARIES_KERNEL_LIMIT
      AssetCache(kernelLimit = { null }).sdColorOf(clip).shouldNotBeNull()
      remove(path)
    }

  @Test
  fun `a plan keeping a grade reads no sd colour and needs no kernel`() =
    runTest(timeout = TIMEOUT) {
      val path = smpteC("kept")
      val track = laidTrack(TrackContent.Video, Duration.ZERO, MediaSource.of(path) to CLIP)
      val graph =
        withContext(Dispatchers.Default) {
          laidPlan(listOf(track), CLIP, hdrTransfer = HdrTransfer.Hlg)
            .toAvComposition(sdKernelLimit = { SD_PRIMARIES_KERNEL_LIMIT })
        }
      graph.encodesHdr shouldBe true
      graph.drawnByCompositor shouldBe false
      graph.spans
        .single()
        .sdColor
        .shouldBeNull()
      remove(path)
    }

  @Test
  fun `a tone map beside an sd clip still needs the kernel`() =
    runTest(timeout = TIMEOUT) {
      val path = smpteC("tone-map")
      val plan = resolved(inOrder(hdrFixture(HdrTransfer.Hlg), path), TONE_MAP_SPEC)
      assertFailsWith<AvLoweringFailure> {
        plan.toAvComposition(sdKernelLimit = { SD_PRIMARIES_KERNEL_LIMIT })
      }.reason shouldBe SD_PRIMARIES_KERNEL_LIMIT
      remove(path)
    }

  private suspend fun resolved(
    composition: EditComposition,
    spec: ExportSpec,
  ): ResolvedComposition {
    val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
    return when (val result = avFoundationExportEngine(components).resolve(composition, spec)) {
      is ResolveResult.Refused -> error(result.error.message)
      is ResolveResult.Resolved -> result.composition
    }
  }

  private fun inOrder(
    first: String,
    second: String,
  ): EditComposition =
    compositionOf {
      clip(MediaSource.of(first)) { trim(Duration.ZERO, CLIP) }
      clip(MediaSource.of(second)) { trim(Duration.ZERO, CLIP) }
    }

  private fun flatCodes(colour: DoubleArray): CIImage {
    val texel = floatArrayOf(colour[0].toFloat(), colour[1].toFloat(), colour[2].toFloat(), 1f)
    val data = texel.usePinned { NSData.dataWithBytes(it.addressOf(0), (texel.size * Float.SIZE_BYTES).toULong()) }
    return CIImage.imageWithBitmapData(
      data,
      bytesPerRow = (texel.size * Float.SIZE_BYTES).toULong(),
      size = CGSizeMake(1.0, 1.0),
      format = kCIFormatRGBAf,
      colorSpace = null,
    )
  }

  private suspend fun assertRendersDisplayed(
    path: String,
    primaries: Int,
  ) {
    val honest = codeFramesOf(path, 1).first()
    val rendered = withContext(Dispatchers.Default) { renderedFrameOf(graphOf(alone(path)), 0) }
    EVERY_BAR.forEach { x ->
      assertCodes(rendered.at(x, MIDDLE), honest.at(x, MIDDLE).displayed(primaries), "bar at $x of $path", SD_CODES)
    }
    remove(path)
  }

  private fun Rgb.displayed(primaries: Int): Rgb {
    val shown = sdSourceDisplayed(doubleArrayOf(r, g, b), primaries)
    return Rgb(shown[0], shown[1], shown[2])
  }

  private suspend fun smpteC(name: String): String {
    val path = temporaryPath(name)
    val primaries = AVVideoColorPrimaries_SMPTE_C.orEmpty()
    val matrix = AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty()
    withContext(Dispatchers.Default) { writeTaggedBars(path, FRAMES, primaries, matrix) }
    return path
  }

  private suspend fun graphOf(composition: EditComposition): AvComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, SPEC)) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition.toAvComposition()
      }
    }

  private fun alone(path: String): EditComposition =
    compositionOf { clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) } }

  private fun inverted(path: String): EditComposition =
    compositionOf {
      clip(MediaSource.of(path)) {
        trim(Duration.ZERO, CLIP)
        addEffects(listOf(Invert()))
      }
    }

  private fun invertedTwice(path: String): EditComposition =
    compositionOf {
      clip(MediaSource.of(path)) {
        trim(Duration.ZERO, CLIP)
        addEffects(listOf(Invert(), Invert()))
      }
    }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-sd-head-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val FRAMES = 5
    const val BT709 = 1
    const val BT601 = 6
    const val BT470BG = 5
    const val SRGB = 13
    const val MIDDLE = 0.5f
    const val BARS = 8

    // What a render and the shared function differ by, one 8-bit chroma step of the clip's H.264.
    const val SD_CODES = 2.0

    // What the kernel's floats lose against the shared function's doubles, the simulator's being the coarser.
    const val KERNEL_CODES = 0.25

    // Mid range, the darks under the curve's knee, and colours that land on the clamp.
    val KERNEL_COLOURS =
      listOf(
        doubleArrayOf(0.7, 0.5, 0.4),
        doubleArrayOf(0.4, 0.6, 0.5),
        doubleArrayOf(0.45, 0.5, 0.75),
        doubleArrayOf(0.05, 0.06, 0.07),
        doubleArrayOf(0.0, 0.85, 0.0),
        doubleArrayOf(0.9, 0.1, 0.0),
      )
    val EVERY_BAR = (0 until BARS).map { (it + MIDDLE) / BARS }
    val CLIP = 100.milliseconds
    val TIMEOUT = 2.minutes
    val SPEC = ExportSpec(heightPx = 360)
    val TONE_MAP_SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}
