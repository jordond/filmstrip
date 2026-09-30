package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ExportConstants.BRIGHTER
import dev.jordond.filmstrip.ffmpeg.ExportConstants.CODE_DRIFT
import dev.jordond.filmstrip.ffmpeg.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.brightnessSceneGain
import dev.jordond.filmstrip.media.hlgDisplayNitsFromScene
import dev.jordond.filmstrip.media.hlgSceneFromDisplayNits
import dev.jordond.filmstrip.media.hlgSignalFromScene
import dev.jordond.filmstrip.media.nitsFromPqSignal
import dev.jordond.filmstrip.media.pqSignalFromNits
import dev.jordond.filmstrip.media.sceneFromHlgSignal
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Colour effects on a kept HDR grade, read back as ten-bit codes against the light the shared transfer functions
 * predict.
 */
class FfmpegHdrEffectTest {
  private val fixture = FfmpegExportFixture()

  // What a kept grade turns the same factor into. PQ is absolute, so the factor is read as the
  // display light ratio an SDR display would have produced and applied to the light the code value
  // decodes to. Every figure comes from the shared transfer functions, so this moves with them
  // rather than pinning a measurement taken once.
  @Test
  fun `halving brightness on a kept grade halves the light an SDR display would have shown`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = exportedHdrBrightness(1f)
      val dimmed = exportedHdrBrightness(HALF)

      val expected = lit.map { code -> pqCodeScaledBy(code, brightnessDisplayGain(HALF)) }
      // Reading the factor as a bare multiply on light is the mistake this rules out. It lands
      // about twice as bright, far outside the drift a real encode leaves.
      val linear = lit.map { code -> pqCodeScaledBy(code, HALF) }

      lit.litChannels().forEach { channel ->
        assertTrue(
          abs(dimmed[channel] - expected[channel]) <= CODE_DRIFT,
          "channel $channel read ${dimmed[channel]}, expected ${expected[channel]} from $lit at full",
        )
        assertTrue(
          abs(dimmed[channel] - linear[channel]) > abs(dimmed[channel] - expected[channel]),
          "channel $channel read ${dimmed[channel]}, ${expected[channel]} from the display gain " +
            "and ${linear[channel]} from a linear one",
        )
      }
    }

  // Two dimmed exports rather than one against an untouched reference, so a channel already near
  // PQ's peak has somewhere to go. Where a factor above 1f asks for more light than the format
  // carries it clips, which is PQ's own limit and asserted as one.
  @Test
  fun `a factor above one adds light on a kept grade until the format runs out`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = exportedHdrBrightness(1f)
      val brightened = exportedHdrBrightness(BRIGHTER)
      val gain = brightnessDisplayGain(BRIGHTER)

      lit.litChannels().forEach { channel ->
        val light = nitsFromPqSignal(lit[channel].toFloat() / TEN_BIT_MAX) * gain
        if (light <= PQ_PEAK_NITS * HEADROOM) {
          val expected = pqCodeScaledBy(lit[channel], gain)
          assertTrue(
            abs(brightened[channel] - expected) <= CODE_DRIFT,
            "channel $channel read ${brightened[channel]}, expected $expected from $lit at full",
          )
        } else {
          assertTrue(
            brightened[channel] >= TEN_BIT_MAX - CODE_DRIFT,
            "channel $channel asked for $light nits and read ${brightened[channel]}, short of the format's ceiling",
          )
        }
      }
    }

  // The HLG arm, where the three backends disagree about what linear means. Only the inverse OETF
  // runs here, so what is scaled is scene light and the gain has to be the one that belongs to it.
  @Test
  fun `halving brightness on a kept HLG grade halves the light an SDR display would have shown`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = exportedHdrBrightness(1f, fixture.hdrHlg)
      val dimmed = exportedHdrBrightness(HALF, fixture.hdrHlg)

      val expected = lit.map { code -> hlgCodeScaledBy(code, brightnessSceneGain(HALF)) }
      // Handing HLG the display gain is the mistake this rules out. On a pipeline holding scene
      // light that darkens by a further system gamma.
      val overDimmed = lit.map { code -> hlgCodeScaledBy(code, brightnessDisplayGain(HALF)) }

      lit.litChannels().forEach { channel ->
        assertTrue(
          abs(dimmed[channel] - expected[channel]) <= CODE_DRIFT,
          "channel $channel read ${dimmed[channel]}, expected ${expected[channel]} from $lit at full",
        )
        assertTrue(
          abs(dimmed[channel] - overDimmed[channel]) > abs(dimmed[channel] - expected[channel]),
          "channel $channel read ${dimmed[channel]}, ${expected[channel]} from the scene gain " +
            "and ${overDimmed[channel]} from the display one",
        )
      }
    }

  // A contrast on a kept grade runs on the SDR signal the light decodes to, so it pivots on the
  // light mid grey makes on an SDR display rather than on the middle of PQ's range. The centre of
  // the fixture is a saturated patch, so every channel sits somewhere different on the line.
  @Test
  fun `a contrast on a kept grade pivots on the light mid grey makes`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = exportedHdrGrade(Brightness(1f))
      val flattened = exportedHdrGrade(Contrast(HALF))

      val expected = pqCodesGraded(lit, Contrast(HALF))
      // Pivoting on the middle of the format's own range is the mistake this rules out. It lifts
      // every channel towards five thousand nits, far outside the drift a real encode leaves.
      val onTheFormat = lit.map { code -> pqCodeOf(HALF * pqNitsOf(code) + (1f - HALF) * PQ_PEAK_NITS / 2f) }

      lit.litChannels().forEach { channel ->
        assertGraded(flattened, expected, channel, lit)
        assertTrue(
          abs(flattened[channel] - onTheFormat[channel]) > abs(flattened[channel] - expected[channel]),
          "channel $channel read ${flattened[channel]}, ${expected[channel]} from the shared reading " +
            "and ${onTheFormat[channel]} from a pivot on the format's range",
        )
      }
    }

  // A contrast above one stretches until the format runs out. The red of the fixture's centre
  // already sits near PQ's peak, so it asks for more light than the format carries and clips
  // there, which is PQ's own limit and asserted as one. The other channels land on the reading.
  @Test
  fun `a contrast above one on a kept grade stretches until the format runs out`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = exportedHdrGrade(Brightness(1f))
      val stretched = exportedHdrGrade(Contrast(BRIGHTER))
      val expected = pqCodesGraded(lit, Contrast(BRIGHTER))
      // A contrast is the same line on every channel, so the shared reference reads one channel at
      // a time with that channel's own light in all three.
      val matrix = checkNotNull(colorMatrixOf(Contrast(BRIGHTER)))

      lit.litChannels().forEach { channel ->
        // The shared reference stops at the format's peak, so a channel that landed there asked for
        // at least that much light and one below it did not.
        val nits = pqNitsOf(lit[channel])
        val asked = matrix.transformNits(nits, nits, nits, HdrTransfer.Pq)[0]
        if (asked <= PQ_PEAK_NITS * HEADROOM) {
          assertGraded(stretched, expected, channel, lit)
        } else {
          assertTrue(
            stretched[channel] >= TEN_BIT_MAX - CODE_DRIFT,
            "channel $channel asked for $asked nits and read ${stretched[channel]}, short of the format's ceiling",
          )
        }
      }
    }

  // A saturation mixes the channels, so on a grade it reaches the file through the table written
  // beside the graph, run between a decode and an encode of the transfer at sixteen bits. The
  // centre of the fixture is a saturated patch, so pulling it towards grey moves every channel.
  @Test
  fun `a saturation on a kept grade mixes the SDR signal through the table`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = exportedHdrGrade(Brightness(1f))
      val desaturated = exportedHdrGrade(Saturation(HALF))
      val expected = pqCodesGraded(lit, Saturation(HALF))

      lit.litChannels().forEach { channel -> assertGraded(desaturated, expected, channel, lit) }
    }

  // The HLG arm. Only the inverse OETF runs here, so the light the line runs on comes through the
  // per-channel opto-optical transfer, and the reading goes back the same way. The table alone
  // lands within a code value of the reading on this transfer as on PQ, so the band is the
  // encoder's and the same one.
  @Test
  fun `a contrast on a kept HLG grade pivots on the light mid grey makes`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.encodesHdr()) return@runTest

      val lit = exportedHdrGrade(Brightness(1f), fixture.hdrHlg)
      val flattened = exportedHdrGrade(Contrast(HALF), fixture.hdrHlg)
      val expected = hlgCodesGraded(lit, Contrast(HALF))

      lit.litChannels().forEach { channel -> assertGraded(flattened, expected, channel, lit) }
    }

  private suspend fun exportedHdrBrightness(
    factor: Float,
    source: File = fixture.hdr,
  ): List<Int> = exportedHdrGrade(Brightness(factor), source)

  private suspend fun exportedHdrGrade(
    spec: EffectSpec,
    source: File = fixture.hdr,
  ): List<Int> {
    val output = File.createTempFile("filmstrip-hdr-${spec.id}", ".mp4").also { it.delete() }
    // Carrying an effect even where it lowers to nothing is what keeps the reference off the copy
    // path, so both runs came out of the encoder at the same geometry and the same pixel is the
    // same picture.
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
    return tenBitPixelAt(output.absolutePath, size.width / 2, size.height / 2).also { output.delete() }
  }

  private fun pqCodeScaledBy(
    code: Int,
    gain: Float,
  ): Int {
    val light = nitsFromPqSignal(code.toFloat() / TEN_BIT_MAX)

    return (pqSignalFromNits(light * gain) * TEN_BIT_MAX).roundToInt()
  }

  private fun hlgCodeScaledBy(
    code: Int,
    gain: Float,
  ): Int {
    val scene = sceneFromHlgSignal(code.toFloat() / TEN_BIT_MAX)

    return (hlgSignalFromScene(scene * gain) * TEN_BIT_MAX).roundToInt()
  }

  private fun assertGraded(
    measured: List<Int>,
    expected: List<Int>,
    channel: Int,
    lit: List<Int>,
  ) {
    assertTrue(
      abs(measured[channel] - expected[channel]) <= CODE_DRIFT,
      "channel $channel read ${measured[channel]}, expected ${expected[channel]} from $lit at full",
    )
  }

  // What the shared reading says the same pixel comes out as, in the ten-bit code values the
  // readback reports, so a lowering that applied the matrix somewhere else fails here rather than
  // drifting.
  private fun pqCodesGraded(
    lit: List<Int>,
    spec: EffectSpec,
  ): List<Int> {
    val matrix = checkNotNull(colorMatrixOf(spec))
    val graded = matrix.transformNits(pqNitsOf(lit[0]), pqNitsOf(lit[1]), pqNitsOf(lit[2]), HdrTransfer.Pq)

    return graded.map { pqCodeOf(it) }
  }

  private fun hlgCodesGraded(
    lit: List<Int>,
    spec: EffectSpec,
  ): List<Int> {
    val matrix = checkNotNull(colorMatrixOf(spec))
    val nits = lit.map { code -> hlgDisplayNitsFromScene(sceneFromHlgSignal(code / TEN_BIT_MAX)) }
    val graded = matrix.transformNits(nits[0], nits[1], nits[2], HdrTransfer.Hlg)

    return graded.map { (hlgSignalFromScene(hlgSceneFromDisplayNits(it)) * TEN_BIT_MAX).roundToInt() }
  }

  private fun pqNitsOf(code: Int): Float = nitsFromPqSignal(code / TEN_BIT_MAX)

  private fun pqCodeOf(nits: Float): Int = (pqSignalFromNits(nits) * TEN_BIT_MAX).roundToInt()

  /**
   * The channels of a reference read that carry enough signal to say anything.
   *
   * PQ is at its steepest near black, and the graded path costs a 4:2:0 chroma round trip that a
   * reference export with no effect never pays. On a channel sitting at black that round trip moves
   * the code value further than the effect does, so such a channel is read past rather than
   * asserted on.
   */
  private fun List<Int>.litChannels(): List<Int> =
    indices.filter { this[it] >= SIGNAL_FLOOR }.also {
      assertTrue(it.isNotEmpty(), "every channel of the reference frame read as black, so nothing was measured")
    }

  /**
   * The ten-bit red, green and blue of one pixel, read by asking ffmpeg for the planes untouched.
   *
   * An `rgb24` read would tone-map the grade away before the value could be checked, which is the
   * whole of what is under test here. gbrp10le writes green, blue and red planes in that order.
   */
  private fun tenBitPixelAt(
    path: String,
    x: Int,
    y: Int,
  ): List<Int> {
    val process =
      ProcessBuilder(
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        path,
        "-vf",
        "crop=2:2:$x:$y,format=gbrp10le",
        "-vframes",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "gbrp10le",
        "-",
      ).start()
    val planes = process.inputStream.readNBytes(PLANE_BYTES * 3)
    process.waitFor()

    fun plane(index: Int): Int {
      val offset = index * PLANE_BYTES
      return (planes[offset].toInt() and 0xFF) or ((planes[offset + 1].toInt() and 0xFF) shl 8)
    }

    return listOf(plane(2), plane(0), plane(1))
  }

  private companion object {
    // A 2x2 crop of one plane at two bytes a sample.
    const val PLANE_BYTES = 8
    const val TEN_BIT_MAX = 1023f

    // Where a ten-bit channel carries more than the fixture's own noise floor and the slop a
    // chroma round trip leaves.
    const val SIGNAL_FLOOR = 128

    // How close to PQ's peak a reading has to get to count as having hit the format's ceiling
    // rather than the effect's answer.
    const val HEADROOM = 0.9f
  }
}
