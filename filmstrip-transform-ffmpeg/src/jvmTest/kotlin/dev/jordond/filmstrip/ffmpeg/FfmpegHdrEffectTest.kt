package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transform
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ExportConstants.BRIGHTER
import dev.jordond.filmstrip.ffmpeg.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.brightnessSceneGain
import dev.jordond.filmstrip.media.hlgDisplayNitsFromSignal
import dev.jordond.filmstrip.media.hlgSignalFromDisplayNits
import dev.jordond.filmstrip.media.hlgSignalFromScene
import dev.jordond.filmstrip.media.nitsFromPqSignal
import dev.jordond.filmstrip.media.pqSignalFromNits
import dev.jordond.filmstrip.media.sceneFromHlgSignal
import dev.jordond.filmstrip.media.signalFromTenBitCodes
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Colour effects on a kept HDR grade, read back as the ten-bit luma, Cb and Cr codes the export stores and compared
 * against the codes the shared transfer functions predict.
 *
 * Every prediction starts from the fixture's own patch, read off the source file's planes and decoded to its R'G'B'
 * signal without rounding. The graded signal goes through [tenBitCodesFromSignal], so the export's encoder is the only
 * thing between the prediction and the read, and each plane is held to [CODE_TOLERANCE]. Every wrong answer is turned
 * into codes the same way and has to land more than twice that bound away on each plane the grade does not name as
 * alike, so a grade run in the wrong domain cannot pass. The same matrix run on the HDR signal itself is the wrong
 * answer every grade is checked against.
 */
class FfmpegHdrEffectTest {
  private val fixture = FfmpegExportFixture()

  // What a kept grade turns the same factor into. PQ is absolute, so the factor is read as the
  // display light ratio an SDR display would have produced and applied to the light the code value
  // decodes to.
  @Test
  fun `halving brightness on a kept grade halves the light an SDR display would have shown`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = authoredSignal()
      val dimmed = exportedHdrGrade(Brightness(HALF))

      val expected = lit.pqScaledBy(brightnessDisplayGain(HALF))
      // Reading the factor as a bare multiply on light is the mistake this rules out. It lands
      // about twice as bright.
      val wrongs = mapOf("a linear gain" to lit.pqScaledBy(HALF), "the signal" to signalGraded(lit, Brightness(HALF)))

      assertGraded(dimmed, expected, lit, wrongs)
    }

  // Where a factor above 1f asks for more light than the format carries, the prediction clips at
  // PQ's peak, which is the format's own limit.
  @Test
  fun `a factor above one adds light on a kept grade until the format runs out`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = authoredSignal()
      val brightened = exportedHdrGrade(Brightness(BRIGHTER))

      val expected = lit.pqScaledBy(brightnessDisplayGain(BRIGHTER))
      val wrongs = mapOf("the signal" to signalGraded(lit, Brightness(BRIGHTER)))

      assertGraded(brightened, expected, lit, wrongs)
    }

  // The HLG arm, where the three backends disagree about what linear means. Only the inverse OETF
  // runs here, so what is scaled is scene light and the gain has to be the one that belongs to it.
  @Test
  fun `halving brightness on a kept HLG grade halves the light an SDR display would have shown`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = authoredSignal(fixture.hdrHlg)
      val dimmed = exportedHdrGrade(Brightness(HALF), fixture.hdrHlg)

      val expected = lit.hlgScaledBy(brightnessSceneGain(HALF))
      // Handing HLG the display gain is the mistake this rules out. On a pipeline holding scene
      // light that darkens by a further system gamma.
      val wrongs =
        mapOf(
          "the display gain" to lit.hlgScaledBy(brightnessDisplayGain(HALF)),
          "the signal" to signalGraded(lit, Brightness(HALF)),
        )

      assertGraded(dimmed, expected, lit, wrongs)
    }

  // A contrast on a kept grade runs on the SDR signal the light decodes to, so it pivots on the
  // light mid grey makes on an SDR display rather than on the middle of PQ's range. The centre of
  // the fixture is a saturated patch, so every channel sits somewhere different on the line.
  @Test
  fun `a contrast on a kept grade pivots on the light mid grey makes`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = authoredSignal()
      val flattened = exportedHdrGrade(Contrast(HALF))

      val expected = pqGraded(lit, Contrast(HALF))
      // Pivoting on the middle of the format's own range is the mistake this rules out. It lifts
      // every channel towards five thousand nits.
      val onTheFormat =
        lit.mapChannels { pqSignalFromNits(HALF * nitsFromPqSignal(it) + (1f - HALF) * PQ_PEAK_NITS / 2f) }
      val wrongs =
        mapOf("a pivot on the format's range" to onTheFormat, "the signal" to signalGraded(lit, Contrast(HALF)))

      // Flattening the patch's signal lands its luma a few codes from flattening its light, so luma cannot tell the
      // two apart and the chroma planes do.
      assertGraded(flattened, expected, lit, wrongs, alike = mapOf("the signal" to setOf(Plane.Luma)))
    }

  // A contrast above one stretches until the format runs out. The red of the fixture's centre
  // already sits near PQ's peak, so it asks for more light than the format carries and the
  // prediction clips it there, which is the format's own limit.
  @Test
  fun `a contrast above one on a kept grade stretches until the format runs out`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = authoredSignal()
      val stretched = exportedHdrGrade(Contrast(BRIGHTER))

      val expected = pqGraded(lit, Contrast(BRIGHTER))
      val wrongs = mapOf("the signal" to signalGraded(lit, Contrast(BRIGHTER)))

      assertGraded(stretched, expected, lit, wrongs)
    }

  // A saturation mixes the channels, so on a grade it runs on float planes. lut1d tables and mixer
  // rows decode the codes to the SDR signal, a colorchannelmixer mixes it, and tables and rows
  // write it back as whole codes, each table a sidecar beside the graph. The centre of the fixture
  // is a saturated patch, so pulling it towards grey moves every channel.
  @Test
  fun `a saturation on a kept grade mixes the SDR signal through the table`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = authoredSignal()
      val desaturated = exportedHdrGrade(Saturation(HALF))

      val expected = pqGraded(lit, Saturation(HALF))
      val wrongs = mapOf("the signal" to signalGraded(lit, Saturation(HALF)))

      assertGraded(desaturated, expected, lit, wrongs)
    }

  // The HLG arm. Only the inverse OETF runs here, so the light the line runs on comes through the
  // per-channel opto-optical transfer, and the reading goes back the same way.
  @Test
  fun `a contrast on a kept HLG grade pivots on the light mid grey makes`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = authoredSignal(fixture.hdrHlg)
      val flattened = exportedHdrGrade(Contrast(HALF), fixture.hdrHlg)

      val expected = hlgGraded(lit, Contrast(HALF))
      val wrongs = mapOf("the signal" to signalGraded(lit, Contrast(HALF)))

      assertGraded(flattened, expected, lit, wrongs)
    }

  // The luma, Cb and Cr codes at the centre of an export of [source] with [spec] on it.
  private suspend fun exportedHdrGrade(
    spec: EffectSpec,
    source: File = fixture.hdr,
  ): IntArray {
    val output = File.createTempFile("filmstrip-hdr-${spec.id}", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        clip(MediaSource.of(source.absolutePath))
        effects { add(spec) }
      }

    val plan = assertIs<Verdict.Capable>(fixture.filmstrip.plan(composition, ExportSpec(hdr = HdrMode.KeepHdr))).plan
    plan.path shouldBe ExportPath.Transcode
    val finished =
      fixture.filmstrip
        .export(plan, MediaSink.of(output.absolutePath))
        .toList()
        .last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    assertIs<ExportStatus.Success>(finished)

    val size = plan.output.size
    assertEquals(sourceSize(source), size, "the grade moved the frame, so its centre is not the patch's")
    return tenBitCodesAt(output, size.width / 2 to size.height / 2).also { output.delete() }
  }

  // The fixture's patch as the R'G'B' signal the export decodes it to, read off the centre of the source file itself
  // through the shared matrix and left unrounded.
  private suspend fun authoredSignal(source: File = fixture.hdr): FloatArray {
    val size = sourceSize(source)
    val codes = tenBitCodesAt(source, size.width / 2 to size.height / 2)

    return signalFromTenBitCodes(codes[0], codes[1], codes[2])
  }

  private suspend fun sourceSize(source: File): Size {
    val info = assertIs<ProbeResult.Success>(fixture.filmstrip.probe(MediaSource.of(source.absolutePath))).info
    return assertNotNull(info.video, "$source carries no video").displaySize
  }

  // Each plane of the read sits within CODE_TOLERANCE of the codes [expected] stores as, and each wrong answer's codes
  // land more than twice that from them on every plane but the ones [alike] names for it, so a grade that took a wrong
  // answer reads outside the bound. A named plane has to really sit that close, and one plane has to be left to tell
  // the wrong answer apart.
  private fun assertGraded(
    read: IntArray,
    expected: FloatArray,
    lit: FloatArray,
    wrongs: Map<String, FloatArray>,
    alike: Map<String, Set<Plane>> = emptyMap(),
  ) {
    val codes = tenBitCodesFromSignal(expected)
    Plane.entries.forEach { plane ->
      val code = codes[plane.ordinal]
      assertTrue(
        abs(read[plane.ordinal] - code) <= CODE_TOLERANCE,
        "$plane read ${read[plane.ordinal]}, expected $code from ${lit.toList()} in the source",
      )
    }
    wrongs.forEach { (name, wrong) ->
      val wrongCodes = tenBitCodesFromSignal(wrong)
      val named = alike[name].orEmpty()
      assertTrue(named.size < Plane.entries.size, "every plane is named alike for $name, so nothing tells it apart")
      Plane.entries.forEach { plane ->
        val miss = abs(wrongCodes[plane.ordinal] - codes[plane.ordinal])
        val apart = 2 * CODE_TOLERANCE < miss
        if (plane in named) {
          assertFalse(apart, "$plane: grading from $name lands $miss codes away, so it no longer needs naming alike")
        } else {
          assertTrue(
            apart,
            "$plane: grading from $name lands $miss codes from ${codes[plane.ordinal]}, " +
              "too close for a bound of $CODE_TOLERANCE to tell apart",
          )
        }
      }
    }
  }

  // The same grade run straight on the HDR signal rather than on the light it decodes to.
  private fun signalGraded(
    lit: FloatArray,
    spec: EffectSpec,
  ): FloatArray = checkNotNull(colorMatrixOf(spec)).transform(lit[0], lit[1], lit[2])

  // What the shared reading says the same pixel comes out as on a PQ grade, so a lowering that applied the matrix
  // somewhere else fails here rather than drifting.
  private fun pqGraded(
    lit: FloatArray,
    spec: EffectSpec,
  ): FloatArray {
    val nits = lit.mapChannels { nitsFromPqSignal(it) }
    val graded = checkNotNull(colorMatrixOf(spec)).transformNits(nits[0], nits[1], nits[2], HdrTransfer.Pq)

    return graded.mapChannels { pqSignalFromNits(it) }
  }

  private fun hlgGraded(
    lit: FloatArray,
    spec: EffectSpec,
  ): FloatArray {
    val nits = lit.mapChannels { hlgDisplayNitsFromSignal(it) }
    val graded = checkNotNull(colorMatrixOf(spec)).transformNits(nits[0], nits[1], nits[2], HdrTransfer.Hlg)

    return graded.mapChannels { hlgSignalFromDisplayNits(it) }
  }

  private fun FloatArray.pqScaledBy(gain: Float): FloatArray =
    mapChannels { pqSignalFromNits(nitsFromPqSignal(it) * gain) }

  private fun FloatArray.hlgScaledBy(gain: Float): FloatArray =
    mapChannels { hlgSignalFromScene(sceneFromHlgSignal(it) * gain) }

  private fun FloatArray.mapChannels(transform: (Float) -> Float): FloatArray = FloatArray(size) { transform(this[it]) }

  // The planes tenBitCodesFromSignal and tenBitCodesAt hand back, in their order.
  private enum class Plane { Luma, Cb, Cr }
}
