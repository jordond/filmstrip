package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.ffmpeg.internal.lowering.FilterGraphBuilder
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ffmpegTag
import dev.jordond.filmstrip.ffmpeg.internal.lowering.flattenOntoFill
import dev.jordond.filmstrip.ffmpeg.internal.lowering.gradeNodes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.hdrFillCodes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.ffmpeg.internal.lowering.restateGrade
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class FillLoweringTest {
  @Test
  fun `an sdr flatten lays the frame over the fill's own color`() {
    val graph = flattened(Fill.Solid(MID_GREEN), hdrTransfer = null, pixelFormat = "yuv420p")

    graph.fillUnderRead("color=c=0x308020:s=1280x720:r=25") shouldContain
      "overlay=shortest=1:format=yuv420,format=pix_fmts=yuv420p"
  }

  @Test
  fun `a blurred fill has no color of its own and flattens onto black`() {
    val graph = flattened(Fill.Blurred(), hdrTransfer = null, pixelFormat = "yuv420p")

    graph.fillUnderRead("color=c=0x000000:s=1280x720:r=25") shouldContain
      "overlay=shortest=1:format=yuv420,format=pix_fmts=yuv420p"
  }

  // The codes come from the shared HDR light, so this reads them off it rather than off a number.
  @Test
  fun `a kept grade writes one frame of the fill's ten bit codes and loops it`() {
    for (transfer in HdrTransfer.entries) {
      val (luma, cb, cr) = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(MID_GREEN)))
      val graph = flattened(Fill.Solid(MID_GREEN), hdrTransfer = transfer, pixelFormat = "yuv420p10le")

      val fill =
        "color=s=1280x720:r=25,trim=end_frame=1,format=pix_fmts=yuv420p10le," +
          "lutyuv=y=$luma:u=$cb:v=$cr," +
          "setparams=color_primaries=bt2020:color_trc=${transfer.ffmpegTag}:colorspace=bt2020nc," +
          "loop=loop=-1:size=1"
      graph.fillUnderRead(fill) shouldContain "overlay=shortest=1:format=auto,format=pix_fmts=yuv420p10le"
    }
  }

  @Test
  fun `the fill's hdr codes are the shared light's codes for its color`() {
    for (transfer in HdrTransfer.entries) {
      hdrFillCodes(Fill.Solid(MID_GREEN), transfer).toList() shouldBe
        tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(MID_GREEN))).toList()
      hdrFillCodes(Fill.Blurred(), transfer).toList() shouldBe
        tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(BLACK))).toList()
    }
  }

  @Test
  fun `the two grades put the same color at different codes`() {
    hdrFillCodes(Fill.Solid(MID_GREEN), HdrTransfer.Pq).toList() shouldNotBe
      hdrFillCodes(Fill.Solid(MID_GREEN), HdrTransfer.Hlg).toList()
  }

  @Test
  fun `no grade restates nothing`() {
    gradeNodes(null).shouldBeEmpty()
  }

  @Test
  fun `a grade is restated as bt2020 with its own transfer`() {
    gradeNodes(HdrTransfer.Pq).render() shouldBe
      "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc"
    gradeNodes(HdrTransfer.Hlg).render() shouldBe
      "setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc"
  }

  @Test
  fun `restating no grade hands the read pad back and writes nothing`() {
    val graph = FilterGraphBuilder()
    graph.chain(listOf("0:v"), listOf(FilterNode("null")), "read")

    val out = graph.restateGrade("read", "graded", transfer = null)

    out shouldBe "read"
    graph.build(out) shouldBe "[0:v]null[read]"
  }

  @Test
  fun `restating a grade writes it onto the named pad`() {
    val graph = FilterGraphBuilder()
    graph.chain(listOf("0:v"), listOf(FilterNode("null")), "read")

    val out = graph.restateGrade("read", "graded", HdrTransfer.Hlg)

    out shouldBe "graded"
    graph.build(out) shouldBe
      "[0:v]null[read];[read]setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc[graded]"
  }

  /**
   * The chain that lays the frame read off `read` over the pad [fill] writes, failing when no chain reads them in that
   * order.
   *
   * Pad labels the flatten makes up for itself are matched rather than spelled out, so only `read`, the pad this test
   * hands in, is named.
   */
  private fun String.fillUnderRead(fill: String): String {
    val pad =
      withClue("no chain writes $fill") {
        Regex(Regex.escape(fill) + "\\[(\\w+)\\]").find(this).shouldNotBeNull()
      }.groupValues[1]
    return withClue("nothing lays read over $pad") {
      Regex(Regex.escape("[$pad][read]") + "([^;\\[]+)").find(this).shouldNotBeNull()
    }.groupValues[1]
  }

  private fun flattened(
    fill: Fill,
    hdrTransfer: HdrTransfer?,
    pixelFormat: String,
  ): String {
    val graph = FilterGraphBuilder()
    graph.chain(listOf("0:v"), listOf(FilterNode("null")), "read")
    val out = graph.flattenOntoFill("read", negotiated(fill, hdrTransfer), frameRate = 25, pixelFormat = pixelFormat)
    return graph.build(out)
  }

  // The flatten reads the output frame, the fill and the grade, so nothing else here is filled in.
  private fun negotiated(
    fill: Fill,
    hdrTransfer: HdrTransfer?,
  ): ResolvedComposition {
    val size = Size(1280, 720)
    return ResolvedComposition(
      tracks = emptyList(),
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = size,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.None,
          bitrate = null,
          frameRate = 25,
          audioFormat = null,
        ),
      layoutSize = size,
      fill = fill,
      duration = 2.seconds,
      hdr = if (hdrTransfer == null) ResolvedHdr.ToneMap else ResolvedHdr.Keep,
      hdrTransfer = hdrTransfer,
      path = ExportPath.Transcode,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      encoderName = "libx264",
    )
  }

  private companion object {
    // Halfway up the green channel and well off either end of the other two, so a code read off the
    // wrong channel or clamped to a range end would not land on the same answer.
    val MID_GREEN = 0xFF308020.toInt()
    val BLACK = 0xFF000000.toInt()
  }
}
