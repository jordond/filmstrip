package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.drawnRangeWithin
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.animated.ANIMATED_AVIF_REFUSED
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.picture.animated.atlasGrid
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.SpriteSheets
import dev.jordond.filmstrip.transform.internal.LoweredExport
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.webcodecs.internal.BrowserPlanner
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.browserMaxTextureSide
import dev.jordond.filmstrip.webcodecs.internal.browserParityOf
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The lowering helpers and fixtures the browser planner tests share.
 */
internal object BrowserPlanSupport {
  suspend fun preparedResolver(overlay: ImageOverlay): BuiltInEffectResolver {
    val resolver = BuiltInEffectResolver()
    resolver.prepare(compositionOf(effects = listOf(overlay)))
    return resolver
  }

  fun webGl(maxTextureSize: Int): RenderCapabilities =
    RenderCapabilities(
      api = RenderApi.WebGl,
      supportsFragmentShader = true,
      supportsComputeShader = false,
      supportsHdr = false,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = maxTextureSize,
      features = emptySet(),
    )

  suspend fun lower(
    composition: Composition,
    infos: Map<MediaSource, MediaInfo> = infosOf(composition),
    spec: ExportSpec = SPEC,
    device: DeviceCapabilities = DEVICE,
  ): LoweredExport<BrowserRender> {
    val resolver = BuiltInEffectResolver()
    resolver.prepare(composition)
    return BrowserPlanner(listOf(resolver)).lower(composition, Ask(spec, device, infos))
  }

  fun stripOf(size: Size): ImageSource = ImageSource.ofBytes(OverlayPixels.pngOf(size, AuthoredColors.MID_GREEN))

  fun compositionOf(
    clipEffects: List<EffectSpec> = emptyList(),
    effects: List<EffectSpec> = emptyList(),
  ): Composition =
    Composition(
      tracks = listOf(Track(listOf(Clip(SOURCE, effects = clipEffects)))),
      effects = effects,
      audio = AudioSpec.Remove,
    )

  fun infosOf(composition: Composition): Map<MediaSource, MediaInfo> =
    composition.clips.associate { it.source to infoOf(OUTPUT_FRAME) }

  fun infoOf(
    size: Size,
    transfer: HdrTransfer? = null,
  ): MediaInfo =
    MediaInfo(
      duration = CLIP_LENGTH,
      video =
        VideoTrackInfo(
          codedSize = size,
          displaySize = size,
          rotationDegrees = 0,
          pixelAspectRatio = 1f,
          frameRate = 30f,
          codec = trackCodecOf(if (transfer == null) "avc1" else "vp09"),
          bitDepth = if (transfer == null) 8 else 10,
          colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
          hdrTransfer = transfer,
          bitrate = null,
          hasAlpha = false,
        ),
      audioTracks = emptyList(),
      isExportable = true,
    )

  val SOURCE: MediaSource = MediaSource.Bytes("a".encodeToByteArray())
  val FILLER: MediaSource = MediaSource.Bytes("filler".encodeToByteArray())
  val PNG = Size(8, 4)
  val IMAGE: ImageSource = ImageSource.ofBytes(OverlayPixels.pngOf(PNG, AuthoredColors.MID_GREEN))
  val OUTPUT_FRAME = Size(1280, 720)
  val CLIP_FRAME = Size(640, 720)
  val CLIP_LENGTH = 4.seconds
  val RAMP = 2.seconds
  const val HALF = 0.5f

  const val FRAME_RATE = 25
  const val MILLIS = 1000
  const val MAX_SIDE = 4096
  val RATED_SPEC = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None, frameRate = FRAME_RATE)
  val ATTRIBUTES =
    Attributes(
      inputSize = OUTPUT_FRAME,
      outputSize = OUTPUT_FRAME,
      layoutSize = OUTPUT_FRAME,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = FRAME_RATE.toFloat(),
      span = TimeRange(Duration.ZERO, CLIP_LENGTH),
    )

  val SPEC = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None)
  val DEVICE =
    DeviceCapabilities(
      video = listOf(VideoEncoderCapability(VideoCodec.H264, null, Size(3840, 2160), Size(2, 2), null, null, false, 2)),
      audio = emptyList(),
      supportsHdrEncoding = false,
      concurrentSessionBudget = null,
    )

  val HDR_SPEC = ExportSpec(audioCodec = AudioCodec.None, hdr = HdrMode.KeepHdr)
  val HDR_DEVICE =
    DeviceCapabilities(
      video =
        listOf(VideoCodec.H264, VideoCodec.Vp9).map {
          VideoEncoderCapability(it, null, Size(3840, 2160), Size(2, 2), null, null, false, 2)
        },
      audio = emptyList(),
      supportsHdrEncoding = true,
      concurrentSessionBudget = null,
    )
}
