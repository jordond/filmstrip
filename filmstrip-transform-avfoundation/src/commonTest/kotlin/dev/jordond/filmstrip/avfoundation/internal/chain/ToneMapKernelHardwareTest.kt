package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.avfoundation.TONE_MAP_TOLERANCE_CODES
import dev.jordond.filmstrip.avfoundation.avFoundationExportEngine
import dev.jordond.filmstrip.avfoundation.hdrFixture
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.internal.chainedProber
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.hdr.toneMappedSdr
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import kotlin.math.abs
import kotlin.test.Test

/**
 * The Metal tone map against the shared curve, patch for patch, and the plan refusal on a device without kernels.
 *
 * Each patch goes in as raw signal codes with no colour space, the way a compositor reads its source. The patches sit
 * on every piece of the PQ knee and either side of the HLG crossover, and each keeps a channel off both ends so a
 * saturated read cannot pass by accident.
 */
@OptIn(ExperimentalForeignApi::class)
class ToneMapKernelHardwareTest {
  @Test
  fun `the pq kernel lands on the shared curve`() {
    assertOnTheCurve(
      HdrTransfer.Pq,
      listOf(
        nits(10, 10, 10),
        nits(50, 50, 50),
        nits(100, 100, 100),
        nits(200, 200, 200),
        nits(300, 300, 300),
        nits(500, 500, 500),
        nits(800, 800, 800),
        nits(300, 500, 100),
        nits(150, 400, 700),
        nits(0, 90, 140),
      ),
    )
  }

  // Green is a zero signal, since the signal for 0 nits still decodes to a sliver of light above zero. That takes the
  // slope in place of the knee, and each patch still reads a channel in the middle. Green's decode no longer cancels
  // red's and blue's here, so this holds to the tone map's own tolerance, which the simulator's read needs.
  @Test
  fun `the pq kernel holds the zero green slope`() {
    assertOnTheCurve(
      HdrTransfer.Pq,
      listOf(nits(300, 0, 100).copy(g = 0.0), nits(0, 0, 400).copy(g = 0.0)),
      tolerance = TONE_MAP_TOLERANCE_CODES / MAX_CODE,
    )
  }

  // A grey past the mastering peak saturates with or without the knee's clamp. Red and blue far above green bring green
  // back to the middle through the matrix, which is where the clamp shows.
  @Test
  fun `the pq kernel clamps green at the mastering peak`() {
    assertOnTheCurve(HdrTransfer.Pq, listOf(nits(1500, 1500, 1500)), midRange = false)
    assertOnTheCurve(HdrTransfer.Pq, listOf(nits(5000, 1500, 5000)))
  }

  // Green stays in the middle while the matrix pushes red past one and blue below zero. The last clamp catches both.
  @Test
  fun `the pq kernel clamps a colour outside bt709 on both edges`() {
    assertOnTheCurve(HdrTransfer.Pq, listOf(nits(800, 300, 0), nits(0, 100, 0)))
  }

  @Test
  fun `the hlg kernel lands on the shared curve`() {
    assertOnTheCurve(
      HdrTransfer.Hlg,
      listOf(
        Rgb(0.1, 0.1, 0.1),
        Rgb(0.25, 0.25, 0.25),
        Rgb(0.5, 0.5, 0.5),
        Rgb(0.75, 0.75, 0.75),
        Rgb(0.9, 0.9, 0.9),
        Rgb(0.6, 0.3, 0.1),
        Rgb(0.4, 0.7, 0.2),
        Rgb(0.2, 0.3, 0.8),
      ),
    )
  }

  @Test
  fun `a device without the compile call or a kernel has a limit`() {
    toneMapKernelLimit(kernelsExist = false) { fail("nothing compiles on an OS without the call") }
      .shouldBe(TONE_MAP_KERNEL_LIMIT)
    toneMapKernelLimit(kernelsExist = true) { false } shouldBe TONE_MAP_KERNEL_LIMIT
    toneMapKernelLimit(kernelsExist = true) { true }.shouldBeNull()
  }

  @Test
  fun `this device has no limit`() {
    toneMapKernelLimit().shouldBeNull()
  }

  @Test
  fun `a device without kernels refuses the tone map by name and keeps the grade`() =
    runTest {
      val clip = compositionOf { clip(MediaSource.of(hdrFixture(HdrTransfer.Pq))) }
      val components = ComponentRegistry.Builder().build()
      val prober = chainedProber(components)
      val engine =
        avFoundationExportEngine(
          components,
          prober,
          AvDriver(prober, kernelLimit = { TONE_MAP_KERNEL_LIMIT }),
        )

      engine
        .resolve(clip, ExportSpec(heightPx = HEIGHT, hdr = HdrMode.ToneMapToSdr))
        .shouldBeInstanceOf<ResolveResult.Refused>()
        .error.message shouldBe TONE_MAP_KERNEL_LIMIT
      engine
        .resolve(clip, ExportSpec(heightPx = HEIGHT, hdr = HdrMode.KeepHdr))
        .shouldBeInstanceOf<ResolveResult.Resolved>()
      engine
        .resolve(clip, ExportSpec(heightPx = HEIGHT, hdr = HdrMode.Auto))
        .shouldBeInstanceOf<ResolveResult.Resolved>()
    }

  private fun assertOnTheCurve(
    transfer: HdrTransfer,
    patches: List<Rgb>,
    midRange: Boolean = true,
    tolerance: Double = TOLERANCE,
  ) {
    val read = strip(patches).toneMapped(transfer).linearPixels(patches.size, height = 1)
    patches.forEachIndexed { index, patch ->
      val expected = toneMappedSdr(transfer, patch)
      val actual = read.at(index, 0)
      withClue("$transfer patch $patch read $actual against $expected") {
        listOf(expected.r, expected.g, expected.b)
          .zip(actual)
          .all { (want, got) -> abs(want - got) <= tolerance }
          .shouldBeTrue()
      }
      if (midRange) {
        withClue("$transfer patch $patch saturates at $expected, so it cannot tell a curve from another") {
          listOf(expected.r, expected.g, expected.b).any { it in MID_RANGE }.shouldBeTrue()
        }
      }
    }
  }

  /**
   * [patches] as one row of raw codes, carrying no colour space so nothing moves them on the way to the kernel.
   */
  private fun strip(patches: List<Rgb>): CIImage {
    val texels =
      FloatArray(patches.size * LinearPixels.CHANNELS).also { texels ->
        patches.forEachIndexed { index, patch ->
          val at = index * LinearPixels.CHANNELS
          texels[at] = patch.r.toFloat()
          texels[at + 1] = patch.g.toFloat()
          texels[at + 2] = patch.b.toFloat()
          texels[at + 3] = 1f
        }
      }
    val rowBytes = (texels.size * Float.SIZE_BYTES).toULong()
    val data = texels.usePinned { NSData.dataWithBytes(it.addressOf(0), rowBytes) }
    return CIImage.imageWithBitmapData(
      data,
      bytesPerRow = rowBytes,
      size = CGSizeMake(patches.size.toDouble(), 1.0),
      format = kCIFormatRGBAf,
      colorSpace = null,
    )
  }

  private fun nits(
    red: Int,
    green: Int,
    blue: Int,
  ) = Rgb(
    pqSignalFromNits(red.toFloat()).toDouble(),
    pqSignalFromNits(green.toFloat()).toDouble(),
    pqSignalFromNits(blue.toFloat()).toDouble(),
  )

  private fun fail(message: String): Boolean = kotlin.test.fail(message)

  private companion object {
    const val TOLERANCE = 1e-3
    const val MAX_CODE = 255.0
    const val HEIGHT = 720
    val MID_RANGE = 0.05..0.99
  }
}
