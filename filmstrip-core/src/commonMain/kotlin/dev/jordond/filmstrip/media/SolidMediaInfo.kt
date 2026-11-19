package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * What a [MediaSource.Solid] is, as a [MediaInfo] anything that reads a source can take.
 *
 * Every target answers a solid with this at 1x1, since there is nothing to read and a solid stretched over any frame
 * is the same picture. The planner lays it again at the frame it lands in, so a backend draws it at that size. It has
 * no frame rate, so the output takes its rate from the other clips and the export spec, and no audio, so its span is
 * silent. It carries alpha only when the colour does.
 *
 * @param source The solid to describe.
 * @param size The size of the one picture the solid holds.
 */
@InternalFilmstripApi
public fun solidMediaInfoOf(
  source: MediaSource.Solid,
  size: Size = SOLID_SIZE,
): MediaInfo =
  MediaInfo(
    duration = source.duration,
    video =
      VideoTrackInfo(
        codedSize = size,
        displaySize = size,
        rotationDegrees = 0,
        pixelAspectRatio = 1f,
        frameRate = null,
        codec = TrackCodec(name = SOLID_CODEC, kind = CodecKind.Other),
        bitDepth = null,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        bitrate = null,
        hasAlpha = source.color ushr ALPHA_SHIFT != OPAQUE,
      ),
    audioTracks = emptyList(),
    isExportable = true,
  )

internal fun probeSolid(source: MediaSource.Solid): ProbeResult = ProbeResult.Success(solidMediaInfoOf(source))

private val SOLID_SIZE = Size(1, 1)
private const val SOLID_CODEC = "solid"
private const val ALPHA_SHIFT = 24
private const val OPAQUE = 0xFF
