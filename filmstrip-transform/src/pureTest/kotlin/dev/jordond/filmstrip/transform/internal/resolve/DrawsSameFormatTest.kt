package dev.jordond.filmstrip.transform.internal.resolve

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DrawsSameFormatTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a copy and a transcode of one frame compare as equal`() {
    val plain = fixture.clip(audioRate = AUDIO_RATE)
    val copied = fixture.resolve(composition(plain))
    val transcoded = fixture.resolve(composition(plain).withEffects(listOf(Brightness(BRIGHTNESS))))

    copied.path shouldBe ExportPath.Copy
    transcoded.path shouldBe ExportPath.Transcode
    (copied.output.bitrate == transcoded.output.bitrate) shouldBe false

    copied.drawsSameFormatAs(transcoded) shouldBe true
    transcoded.drawsSameFormatAs(copied) shouldBe true
  }

  @Test
  fun `nothing a frame is not drawn from moves the answer`() {
    val base = plan()
    val encoded = base.withOutput(videoCodec = VideoCodec.Hevc, audioCodec = AudioCodec.Opus, bitrate = Bitrate.mbps(3))

    base.drawsSameFormatAs(encoded) shouldBe true
  }

  @Test
  fun `a moved frame size is another format`() {
    plan().drawsSameFormatAs(plan().withOutput(size = Size(640, 360))) shouldBe false
  }

  @Test
  fun `a moved frame rate is another format`() {
    plan().drawsSameFormatAs(plan().withOutput(frameRate = 24)) shouldBe false
  }

  @Test
  fun `a moved hdr decision is another format`() {
    plan().drawsSameFormatAs(plan(hdr = ResolvedHdr.ToneMap)) shouldBe false
  }

  @Test
  fun `a moved transfer function is another format`() {
    plan(hdrTransfer = HdrTransfer.Hlg).drawsSameFormatAs(plan(hdrTransfer = HdrTransfer.Pq)) shouldBe false
  }

  @Test
  fun `dropping the audio is another format`() {
    plan().drawsSameFormatAs(plan().withOutput(audioCodec = AudioCodec.None)) shouldBe false
  }

  private fun plan(
    hdr: ResolvedHdr = ResolvedHdr.Keep,
    hdrTransfer: HdrTransfer? = null,
  ): ResolvedComposition =
    fixture
      .resolve(composition(fixture.clip(audioRate = AUDIO_RATE)))
      .copied(hdr = hdr, hdrTransfer = hdrTransfer)

  private fun ResolvedComposition.withOutput(
    size: Size = output.size,
    frameRate: Int = output.frameRate,
    videoCodec: VideoCodec = output.videoCodec,
    audioCodec: AudioCodec = output.audioCodec,
    bitrate: Bitrate? = output.bitrate,
  ): ResolvedComposition =
    copied(
      output =
        OutputFormat(
          size = size,
          videoCodec = videoCodec,
          audioCodec = audioCodec,
          bitrate = bitrate,
          frameRate = frameRate,
          audioFormat = output.audioFormat,
        ),
    )

  private fun ResolvedComposition.copied(
    output: OutputFormat = this.output,
    hdr: ResolvedHdr = this.hdr,
    hdrTransfer: HdrTransfer? = this.hdrTransfer,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks = tracks,
      compositionEffects = compositionEffects,
      output = output,
      layoutSize = layoutSize,
      fill = fill,
      duration = duration,
      hdr = hdr,
      hdrTransfer = hdrTransfer,
      path = path,
      audio = audio,
      adjustments = adjustments,
    )

  private companion object {
    const val AUDIO_RATE = 48_000
    const val BRIGHTNESS = 1.2f
  }
}
