package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Negotiation is shared, but the ladder each engine tries `Auto` against is not. This asserts that
 * a device offering more than one codec resolves `Auto` to what *that engine's* ladder finds first,
 * not to some order every engine agrees on, because they do not.
 *
 * The ladders below are local copies, not read from the backends: this module cannot depend on
 * `filmstrip-transform-media3`, `-avfoundation`, `-ffmpeg` or `-webcodecs`, they depend on it. So
 * this test locks negotiation's behaviour given a ladder, and records what each engine's ladder is
 * expected to be. It cannot catch one of those backends changing its own ladder; `-avfoundation` and
 * `-ffmpeg` carry their own test against their real, registered ladder for that, and `-media3` would
 * too if it had a host test source set to put one in.
 */
class CrossEngineLadderTest {
  private val media3Ladder = listOf(VideoCodec.H264, VideoCodec.Hevc)
  private val avFoundationLadder = listOf(VideoCodec.H264, VideoCodec.Hevc)
  private val ffmpegLadder = listOf(VideoCodec.H264, VideoCodec.Hevc)
  private val webCodecsLadder = listOf(VideoCodec.H264, VideoCodec.Vp9, VideoCodec.Hevc)

  @Test
  fun `auto resolves to h264 when every ladder offers it`() {
    val device = device(VideoCodec.H264, VideoCodec.Hevc, VideoCodec.Vp9)

    resolvedCodec(media3Ladder, device) shouldBe VideoCodec.H264
    resolvedCodec(avFoundationLadder, device) shouldBe VideoCodec.H264
    resolvedCodec(ffmpegLadder, device) shouldBe VideoCodec.H264
    resolvedCodec(webCodecsLadder, device) shouldBe VideoCodec.H264
  }

  @Test
  fun `without h264 each ladder falls back to what it tries next`() {
    // media3, AVFoundation and ffmpeg agree here by coincidence, not because the ladder is shared
    // code: none of them ever offers Vp9. WebCodecs' ladder tries Vp9 before Hevc, so the one
    // engine whose ladder actually differs is the one that resolves differently.
    val device = device(VideoCodec.Hevc, VideoCodec.Vp9)

    resolvedCodec(media3Ladder, device) shouldBe VideoCodec.Hevc
    resolvedCodec(avFoundationLadder, device) shouldBe VideoCodec.Hevc
    resolvedCodec(ffmpegLadder, device) shouldBe VideoCodec.Hevc
    resolvedCodec(webCodecsLadder, device) shouldBe VideoCodec.Vp9
  }

  private fun resolvedCodec(
    ladder: List<VideoCodec>,
    device: DeviceCapabilities,
  ): VideoCodec {
    val export = planner(ladder).negotiate(composition(), ExportSpec(), device, infos)
    return assertIs<Verdict.Capable>(export.verdict).plan.output.videoCodec
  }

  private fun planner(ladder: List<VideoCodec>) =
    ExportPlanner(
      resolvers = emptyList<EffectResolver>(),
      renderCapabilities = { size, hdr ->
        RenderCapabilities(
          api = RenderApi.OpenGlEs,
          supportsFragmentShader = true,
          supportsComputeShader = false,
          supportsHdr = hdr,
          colorSpaces = setOf(ColorSpace.Bt709),
          maxTextureSize = maxOf(size.width, size.height),
          realtimeBudgetNanos = null,
          features = emptySet(),
        )
      },
      parityOf = { EffectParity.Exact },
      unclaimedMessage = { "nothing claimed $it" },
      ladder = ladder,
      supportsPassthrough = true,
      // This test is about what the ladder resolves an encode to, not what a copy would carry
      // across untouched, so the composition is forced onto the transcode path.
      canCopy = { false },
    )

  private fun device(vararg codecs: VideoCodec): DeviceCapabilities =
    DeviceCapabilities(
      video = codecs.map { encoder(it) },
      audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2)),
      supportsHdrEncoding = false,
      concurrentSessionBudget = null,
    )

  private fun encoder(codec: VideoCodec) =
    VideoEncoderCapability(
      codec = codec,
      encoderName = null,
      maxSize = Size(3840, 2160),
      maxFrameRate = null,
      maxBitrate = null,
      isHardwareAccelerated = true,
      sizeAlignment = 2,
    )

  private fun composition() = EditComposition(listOf(Track(listOf(Clip(source, trim = null, effects = emptyList())))))

  private val source = MediaSource.of("/fixtures/ladder-clip.mp4")

  private val infos: Map<MediaSource, MediaInfo> =
    mapOf(
      source to
        MediaInfo(
          duration = 4_000.milliseconds,
          video =
            VideoTrackInfo(
              codedSize = Size(1920, 1080),
              displaySize = Size(1920, 1080),
              rotationDegrees = 0,
              pixelAspectRatio = 1f,
              frameRate = 30f,
              codec = trackCodecOf("avc1"),
              bitDepth = 8,
              colorSpace = ColorSpace.Bt709,
              hdrTransfer = null,
              bitrate = null,
            ),
          audio = null,
          isExportable = true,
        ),
    )
}
