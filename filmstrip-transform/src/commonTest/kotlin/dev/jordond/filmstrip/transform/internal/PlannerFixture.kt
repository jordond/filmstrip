package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.audio.AudioAttributes
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The planner, device and fabricated sources the planner tests build their edits from.
 *
 * Each clip made here registers its own source and info, and [infos] hands back the map the planner reads for an
 * edit made of them. A test holds its own instance, so every test gets a fresh set of sources.
 */
internal class PlannerFixture {
  fun planner(
    resolvers: List<EffectResolver> = listOf(FakeResolver()),
    canToneMap: Boolean = true,
    muxerAccepts: (MediaInfo) -> Boolean = { true },
    renderApi: RenderApi = RenderApi.OpenGlEs,
    ladder: List<VideoCodec> = listOf(VideoCodec.H264, VideoCodec.Hevc),
    toneMapLimit: (List<MediaSource>) -> String? = { null },
    fillLimit: (Fill) -> String? = { null },
    copyLimit: (MediaInfo) -> CopyBlocker? = { null },
    containerCarries: (VideoCodec?, AudioCodec, Boolean) -> Boolean = { _, _, _ -> true },
    containerName: (VideoCodec?, Boolean) -> String? = { _, _ -> null },
    audioResolvers: List<AudioEffectResolver> = listOf(FakeAudioResolver()),
  ) = ExportPlanner(
    resolvers = resolvers,
    audioResolvers = audioResolvers,
    renderCapabilities = { size, hdr ->
      RenderCapabilities(
        api = renderApi,
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
    supportsCopy = true,
    canToneMap = canToneMap,
    muxerAccepts = muxerAccepts,
    toneMapLimit = toneMapLimit,
    fillLimit = fillLimit,
    copyLimit = copyLimit,
    containerCarries = containerCarries,
    containerName = containerName,
  )

  // Every fabricated clip carries its own source, so the map is keyed the way the planner reads it.
  fun infos(composition: Composition): Map<MediaSource, MediaInfo> =
    composition.tracks
      .flatMap { it.clips }
      .associate { it.source to registry.getValue(it.source) }

  /**
   * Negotiates [composition] against [spec] and [device] on a fresh planner and hands back the verdict.
   */
  fun plan(
    composition: Composition,
    spec: ExportSpec = ExportSpec(),
    device: DeviceCapabilities = this.device(),
    resolvers: List<EffectResolver> = listOf(FakeResolver()),
    canToneMap: Boolean = true,
    muxerAccepts: (MediaInfo) -> Boolean = { true },
    openings: Map<MediaSource, Duration> = emptyMap(),
    layoutSize: Size? = null,
    copyLimit: (MediaInfo) -> CopyBlocker? = { null },
    audioResolvers: List<AudioEffectResolver> = listOf(FakeAudioResolver()),
  ) = planner(
    resolvers,
    canToneMap = canToneMap,
    muxerAccepts = muxerAccepts,
    copyLimit = copyLimit,
    audioResolvers = audioResolvers,
  ).negotiate(composition, Ask(spec, device, infos(composition), openings, layoutSize))
    .verdict

  /**
   * Negotiates [composition] the way [plan] does and hands back the negotiated composition, failing when there is none.
   */
  fun resolve(
    composition: Composition,
    spec: ExportSpec = ExportSpec(),
    device: DeviceCapabilities = this.device(),
    resolvers: List<EffectResolver> = listOf(FakeResolver()),
    canToneMap: Boolean = true,
    muxerAccepts: (MediaInfo) -> Boolean = { true },
    openings: Map<MediaSource, Duration> = emptyMap(),
    renderApi: RenderApi = RenderApi.OpenGlEs,
    audioResolvers: List<AudioEffectResolver> = listOf(FakeAudioResolver()),
  ) = planner(
    resolvers,
    canToneMap = canToneMap,
    muxerAccepts = muxerAccepts,
    renderApi = renderApi,
    audioResolvers = audioResolvers,
  ).negotiate(composition, Ask(spec, device, infos(composition), openings))
    .composition
    .shouldNotBeNull()

  fun clip(
    size: Size = Size(1920, 1080),
    duration: Duration = 6_000.milliseconds,
    trim: TimeRange? = null,
    effects: List<EffectSpec> = emptyList(),
    hdr: HdrTransfer? = null,
    exportable: Boolean = true,
    audioRate: Int? = null,
    codec: String? = null,
    frameRate: Float = 30f,
    snapWithin: Duration = Duration.ZERO,
    fadeIn: Duration = Duration.ZERO,
    fadeOut: Duration = Duration.ZERO,
    bitrate: Bitrate? = Bitrate.mbps(12),
    hasAlpha: Boolean = false,
    audioEffects: List<AudioEffectSpec> = emptyList(),
  ): Clip {
    val source = MediaSource.of("/fixtures/clip-${registry.size}.mp4")
    registry[source] =
      MediaInfo(
        duration = duration,
        video =
          VideoTrackInfo(
            codedSize = size,
            displaySize = size,
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            frameRate = frameRate,
            codec = trackCodecOf(codec ?: if (hdr == null) "avc1" else "hvc1"),
            bitDepth = if (hdr == null) 8 else 10,
            colorSpace = ColorSpace.Bt709,
            hdrTransfer = hdr,
            bitrate = bitrate,
            hasAlpha = hasAlpha,
          ),
        audioTracks =
          listOfNotNull(
            audioRate?.let {
              AudioTrackInfo(AudioTrackId(1), trackCodecOf("mp4a"), it, 2, null, language = null, isDefault = true)
            },
          ),
        isExportable = exportable,
      )
    return Clip(
      source,
      trim,
      effects,
      snapWithin = snapWithin,
      fadeIn = fadeIn,
      fadeOut = fadeOut,
      audioEffects = audioEffects,
    )
  }

  /**
   * A video source carrying [defaults].size audio tracks, numbered from two after the video track the way an MP4 muxer
   * numbers them, each flagged the container default where [defaults] says so.
   *
   * Each track is AAC at 48 kHz unless [codecs] or [rates] say otherwise for it.
   */
  fun multiAudioClip(
    defaults: List<Boolean> = listOf(false, true, false),
    codecs: List<String> = defaults.map { "mp4a" },
    rates: List<Int> = defaults.map { 48_000 },
  ): Clip {
    val plain = clip()
    val info = registry.getValue(plain.source)
    registry[plain.source] =
      MediaInfo(
        duration = info.duration,
        video = info.video,
        audioTracks =
          defaults.mapIndexed { index, isDefault ->
            AudioTrackInfo(
              id = AudioTrackId(index + 2L),
              codec = trackCodecOf(codecs[index]),
              sampleRate = rates[index],
              channelCount = 2,
              bitrate = null,
              language = null,
              isDefault = isDefault,
            )
          },
        isExportable = true,
      )
    return plain
  }

  /**
   * A source with an audio track and no video track, the way a music file reads.
   */
  fun audioOnlyClip(duration: Duration = 6_000.milliseconds): Clip {
    val source = MediaSource.of("/fixtures/audio-${registry.size}.m4a")
    registry[source] =
      MediaInfo(
        duration = duration,
        video = null,
        audioTracks =
          listOf(
            AudioTrackInfo(AudioTrackId(1), trackCodecOf("mp4a"), 44_100, 2, null, language = null, isDefault = true),
          ),
        isExportable = true,
      )
    return Clip(source)
  }

  /**
   * A still of [size] held for [duration], reported the way core's probe reports one.
   */
  fun imageClip(
    size: Size = Size(1920, 1080),
    duration: Duration = 6_000.milliseconds,
    trim: TimeRange? = null,
  ): Clip {
    val source = MediaSource.Image(ImageSource.of("/fixtures/still-${registry.size}.jpg"), duration)
    registry[source] = imageMediaInfoOf(size, EXIF_ORIENTATION_NORMAL, "jpeg", duration)
    return Clip(source, trim)
  }

  fun device(
    video: List<VideoEncoderCapability> = listOf(encoder(VideoCodec.H264), encoder(VideoCodec.Hevc)),
    audio: List<AudioEncoderCapability> = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2)),
    hdr: Boolean = false,
  ) = DeviceCapabilities(video, audio, hdr, concurrentSessionBudget = null)

  fun encoder(
    codec: VideoCodec,
    maxSize: Size = Size(3840, 2160),
    maxFrameRate: Int? = null,
    maxBitrate: Bitrate? = null,
  ) = VideoEncoderCapability(
    codec = codec,
    encoderName = null,
    maxSize = maxSize,
    maxFrameRate = maxFrameRate,
    maxBitrate = maxBitrate,
    isHardwareAccelerated = true,
    sizeAlignment = 2,
  )

  private val registry = mutableMapOf<MediaSource, MediaInfo>()
}

/**
 * An edit of one video track holding [clips] in order.
 */
internal fun composition(vararg clips: Clip) = Composition(listOf(Track(clips.toList())))

/**
 * The first clip on the first track of [resolved].
 */
internal fun firstClipOf(resolved: ResolvedComposition) =
  resolved.tracks
    .first()
    .clips
    .first()

/**
 * Hands back opaque handles, so the lowering can be asserted on without media3.
 *
 * An id in [degrade] resolves degraded for every cause in [causes], in order.
 */
internal class FakeResolver(
  private val refuse: Set<EffectId> = emptySet(),
  private val degrade: Set<EffectId> = emptySet(),
  private val declines: Set<EffectId> = emptySet(),
  private val causes: List<Degradation> = listOf(ROUNDS_IT),
) : EffectResolver {
  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? =
    when (spec.id) {
      in declines -> {
        null
      }
      in refuse -> {
        EffectResolution.Unsupported(spec.id, "this resolver owns it and cannot render it")
      }
      in degrade -> {
        EffectResolution.Degraded(fakePlatformEffect(), causes)
      }
      else -> {
        EffectResolution.Resolved(fakePlatformEffect())
      }
    }
}

/**
 * Hands back opaque audio handles and records the span each effect was asked over.
 *
 * An id in [degrade] resolves degraded for [ROUNDS_IT], and one in [fail] fails with [AUDIO_READ_FAILED].
 */
internal class FakeAudioResolver(
  private val refuse: Set<EffectId> = emptySet(),
  private val degrade: Set<EffectId> = emptySet(),
  private val declines: Set<EffectId> = emptySet(),
  private val fail: Set<EffectId> = emptySet(),
) : AudioEffectResolver {
  val asked = mutableListOf<Pair<EffectId, TimeRange>>()

  override fun resolve(
    spec: AudioEffectSpec,
    attributes: AudioAttributes,
  ): AudioEffectResolution? {
    asked += spec.id to attributes.span
    return when (spec.id) {
      in declines -> null
      in refuse -> AudioEffectResolution.Unsupported(spec.id, "this resolver owns it and cannot run it")
      in degrade -> AudioEffectResolution.Degraded(fakePlatformAudioEffect(), listOf(ROUNDS_IT))
      in fail -> AudioEffectResolution.Failed(AUDIO_READ_FAILED)
      else -> AudioEffectResolution.Resolved(fakePlatformAudioEffect())
    }
  }
}

/**
 * The error [FakeAudioResolver] fails an effect with.
 */
internal val AUDIO_READ_FAILED: FilmstripError = FilmstripError.InvalidComposition("the impulse response did not load")

/**
 * The cause [FakeResolver] gives for an effect it degrades, unless a test hands it others.
 */
internal val ROUNDS_IT = Degradation(DegradationReason.ApproximateAlgorithm, "this resolver rounds it")

/**
 * A second cause a test can hand [FakeResolver], so the order of several causes can be checked.
 */
internal val CONVERTS_IT = Degradation(DegradationReason.ColorSpaceConverted, "this resolver converts its color")

/**
 * A third cause a test can hand [FakeResolver], so a cause between the first and last can be checked.
 */
internal val CLAMPS_IT = Degradation(DegradationReason.ResolutionClamped, "this resolver clamps its size")
