package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportEstimate
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.InertNotice
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.PlannedEffect
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Makes the [ExportPlan] of a ten second 1080p transcode to H.264 and AAC, with no effects.
 *
 * A plan otherwise comes only from a real `plan` call, which needs a backend. This one stands in for it wherever a
 * test hands a plan to its subject or to [FakeFilmstrip].
 *
 * @param composition The edit the plan was made for.
 * @param spec The output that was asked for.
 * @param path How the export would run.
 * @param output The format the export would write.
 * @param effectOrder The effects in the order they would run.
 * @param estimate What the export is expected to cost, which by default knows only whether [path] is a copy.
 * @param parity How closely a preview matches the export.
 * @param duration How long the output plays for.
 * @param copyBlockedBy What kept the export off the copy path.
 * @param inertNotices The effects that would change nothing.
 * @return The plan.
 */
public fun testExportPlan(
  composition: Composition = Composition(emptyList()),
  spec: ExportSpec = ExportSpec(),
  path: ExportPath = ExportPath.Transcode,
  output: OutputFormat = testOutputFormat(),
  effectOrder: List<PlannedEffect> = emptyList(),
  estimate: ExportEstimate = ExportEstimate(null, null, null, isCopy = path == ExportPath.Copy),
  parity: EffectParity = EffectParity.Exact,
  duration: Duration = 10.seconds,
  copyBlockedBy: List<CopyBlocker> = emptyList(),
  inertNotices: List<InertNotice> = emptyList(),
): ExportPlan =
  ExportPlan(
    path = path,
    output = output,
    effectOrder = effectOrder,
    estimate = estimate,
    parity = parity,
    duration = duration,
    copyBlockedBy = copyBlockedBy,
    inertNotices = inertNotices,
    composition = composition,
    spec = spec,
  )

/**
 * Makes a 1080p, 30 frames per second, H.264 and stereo AAC output format.
 *
 * @param size The size of the written frame.
 * @param videoCodec The video codec the export resolved to.
 * @param audioCodec The audio codec the export resolved to.
 * @param bitrate The video bitrate, or null when the encoder picks.
 * @param frameRate Frames per second.
 * @param audioFormat The written audio's rate and channels, or null when no audio is written.
 * @param keyFrameSpacing Frames between key frames, or null when the encoder picks.
 * @return The format.
 */
public fun testOutputFormat(
  size: Size = Size(1920, 1080),
  videoCodec: VideoCodec = VideoCodec.H264,
  audioCodec: AudioCodec = AudioCodec.Aac,
  bitrate: Bitrate? = Bitrate.mbps(8),
  frameRate: Int = 30,
  audioFormat: AudioFormat? = AudioFormat(sampleRate = 48_000, channelCount = 2),
  keyFrameSpacing: Int? = null,
): OutputFormat = OutputFormat(size, videoCodec, audioCodec, bitrate, frameRate, audioFormat, keyFrameSpacing)

/**
 * Makes the verdict of a device that runs [plan] as asked.
 *
 * @param plan The plan the verdict carries.
 * @return The verdict.
 */
public fun testCapableVerdict(plan: ExportPlan = testExportPlan()): Verdict.Capable = Verdict.Capable(plan)

/**
 * Makes the verdict of a device that runs [plan] after changing something, by default a 4K request clamped to 1080p.
 *
 * @param plan The plan the verdict carries.
 * @param adjustments What the device changed, which may not be empty.
 * @return The verdict.
 */
public fun testDegradedVerdict(
  plan: ExportPlan = testExportPlan(),
  adjustments: List<Adjustment> = listOf(testAdjustment()),
): Verdict.Degraded = Verdict.Degraded(plan, adjustments)

/**
 * Makes the verdict of a device that cannot run the edit, by default because 4K is past its encoder.
 *
 * @param reasons Why the device cannot run it.
 * @param withoutUnsupported The verdict with the unsupported effects left out, or null when leaving them out does not
 * help.
 * @return The verdict.
 */
public fun testIncapableVerdict(
  reasons: List<FilmstripError> =
    listOf(
      FilmstripError.UnsupportedResolution(
        requested = Size(3840, 2160),
        max = Size(1920, 1080),
        message = "3840x2160 is larger than the 1920x1080 this encoder accepts.",
      ),
    ),
  withoutUnsupported: Verdict.Degraded? = null,
): Verdict.Incapable = Verdict.Incapable(reasons, withoutUnsupported)

/**
 * Makes an [Adjustment], by default a 4K request clamped to 1080p.
 *
 * @param kind What was changed.
 * @param requested What was asked for.
 * @param resolved What the device does instead.
 * @param message A description of the change for a person to read.
 * @return The adjustment.
 */
public fun testAdjustment(
  kind: AdjustmentKind = AdjustmentKind.ResolutionClamped,
  requested: String = "3840x2160",
  resolved: String = "1920x1080",
  message: String = "The output was clamped to 1920x1080, the largest size this encoder accepts.",
): Adjustment = Adjustment(kind, requested, resolved, message)

/**
 * Makes the [DeviceCapabilities] of a device with one H.264 encoder and one AAC encoder, and no HDR encoding.
 *
 * @param video The video encoders the device has.
 * @param audio The audio encoders the device has.
 * @param supportsHdrEncoding Whether the device can encode HDR.
 * @param concurrentSessionBudget How many encode sessions run at once, or null when the device does not say.
 * @return The capabilities.
 */
public fun testDeviceCapabilities(
  video: List<VideoEncoderCapability> = listOf(testVideoEncoder()),
  audio: List<AudioEncoderCapability> =
    listOf(AudioEncoderCapability(AudioCodec.Aac, sampleRates = listOf(44_100, 48_000), maxChannelCount = 2)),
  supportsHdrEncoding: Boolean = false,
  concurrentSessionBudget: Int? = null,
): DeviceCapabilities = DeviceCapabilities(video, audio, supportsHdrEncoding, concurrentSessionBudget)

/**
 * Makes a hardware H.264 encoder that accepts up to 4K at 60 frames per second.
 *
 * @param codec The codec the encoder writes.
 * @param encoderName The platform's name for the encoder, or null when it has none.
 * @param maxSize The largest frame the encoder accepts.
 * @param maxFrameRate The highest frame rate the encoder accepts, or null when it does not say.
 * @param maxBitrate The highest bitrate the encoder accepts, or null when it does not say.
 * @param isHardwareAccelerated Whether the encoder runs on hardware, or null when the platform does not say.
 * @param sizeAlignment What a frame's width and height must be a multiple of.
 * @return The encoder.
 */
public fun testVideoEncoder(
  codec: VideoCodec = VideoCodec.H264,
  encoderName: String? = null,
  maxSize: Size = Size(3840, 2160),
  maxFrameRate: Int? = 60,
  maxBitrate: Bitrate? = null,
  isHardwareAccelerated: Boolean? = true,
  sizeAlignment: Int = 2,
): VideoEncoderCapability =
  VideoEncoderCapability(codec, encoderName, maxSize, maxFrameRate, maxBitrate, isHardwareAccelerated, sizeAlignment)
