package dev.jordond.filmstrip.avfoundation

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
import dev.jordond.filmstrip.transform.internal.ExportPlanner
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Asserts against this backend's own real registration of [AVFOUNDATION_LADDER], not a copy of it,
 * as wired up in [avFoundationBackend].
 */
class AvFoundationLadderTest {
  private val source = MediaSource.of("/fixtures/ladder-clip.mov")

  @Test
  fun `auto walks this backend's own ladder to the encoder the device actually has`() {
    val device = device(VideoCodec.Hevc)
    val composition = compositionOf(source)

    // Forced off the copy path: the source is H264 and would otherwise transmux untouched, which
    // is not what this test is asking about.
    val export =
      planner(ladder = AVFOUNDATION_LADDER, canCopy = { false })
        .negotiate(composition, ExportSpec(), device, infos(source, Size(1920, 1080)))

    assertIs<Verdict.Capable>(export.verdict).plan.output.videoCodec shouldBe VideoCodec.Hevc
  }

  // AVAssetWriterInput's transform is never set, so it defaults to identity: every clip bakes its
  // own rotation into the pixels, so a portrait output is encoded at its own portrait size.
  @Test
  fun `a portrait output is encoded at its own portrait size`() {
    val device = device(VideoCodec.H264)
    val composition = compositionOf(source)

    val export =
      planner()
        .negotiate(composition, ExportSpec(), device, infos(source, Size(1080, 1920)))

    assertIs<Verdict.Capable>(export.verdict).plan.output.size shouldBe Size(1080, 1920)
  }

  private fun planner(
    ladder: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Hevc),
    canCopy: (MediaInfo) -> Boolean = { true },
  ) = ExportPlanner(
    resolvers = emptyList<EffectResolver>(),
    renderCapabilities = { size, hdr ->
      RenderCapabilities(
        api = RenderApi.Metal,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = hdr,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = maxOf(size.width, size.height),
        features = emptySet(),
      )
    },
    parityOf = { EffectParity.Exact },
    unclaimedMessage = { "nothing claimed $it" },
    ladder = ladder,
    supportsPassthrough = true,
    canCopy = canCopy,
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

  private fun compositionOf(source: MediaSource) =
    EditComposition(listOf(Track(listOf(Clip(source, trim = null, effects = emptyList())))))

  private fun infos(
    source: MediaSource,
    size: Size,
  ): Map<MediaSource, MediaInfo> =
    mapOf(
      source to
        MediaInfo(
          duration = 4_000.milliseconds,
          video =
            VideoTrackInfo(
              codedSize = size,
              displaySize = size,
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
