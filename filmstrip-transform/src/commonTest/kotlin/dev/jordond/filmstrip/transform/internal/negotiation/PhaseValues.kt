package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import kotlin.time.Duration

// The outcome of each negotiation phase built by hand, so a test hands one phase exactly what the phases ahead of it
// settled on. Rendered with Software, which no target's catalogue claims, so a lowering reads the same on every target.

internal fun loweringTarget(
  outputSize: Size = Size(1920, 1080),
  frameRate: Int = 30,
  hdrTransfer: HdrTransfer? = null,
) = LoweringTarget(
  outputSize = outputSize,
  capabilities =
    RenderCapabilities(
      api = RenderApi.Software,
      supportsFragmentShader = true,
      supportsComputeShader = false,
      supportsHdr = hdrTransfer != null,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = maxOf(outputSize.width, outputSize.height),
      features = emptySet(),
    ),
  frameRate = frameRate,
  hdrTransfer = hdrTransfer,
)

internal fun framingOf(
  clip: Clip,
  info: MediaInfo,
  requestedSize: Size = checkNotNull(info.video).displaySize,
) = Framing(
  firstClip = clip,
  firstVideo = checkNotNull(info.video),
  framedByStill = false,
  compositionGeometry = emptyList(),
  compositionInputSize = requestedSize,
  naturalSize = requestedSize,
  requestedSize = requestedSize,
)

internal fun gradeOf(
  path: ExportPath = ExportPath.Transcode,
  sourceIsHdr: Boolean = false,
  gradesAgree: Boolean = true,
  hdr: ResolvedHdr = ResolvedHdr.Keep,
) = Grade(
  sourceIsHdr = sourceIsHdr,
  gradesAgree = gradesAgree,
  hdr = hdr,
  hdrTransfer = null,
  encodesHdr = false,
  path = path,
  copyBlockedBy = emptyList(),
  copyOpensAt = if (path == ExportPath.Copy) Duration.ZERO else null,
)

internal fun encodingOf(
  outputSize: Size,
  video: CodecChoice<VideoCodec> = CodecChoice(VideoCodec.H264, null),
  ceiling: Size? = null,
  overCeiling: Boolean = false,
  frameRate: Int = 30,
) = Encoding(
  video = video,
  encoder = null,
  ceiling = ceiling,
  overCeiling = overCeiling,
  outputSize = outputSize,
  layoutFrame = outputSize,
  target = loweringTarget(outputSize, frameRate),
)
