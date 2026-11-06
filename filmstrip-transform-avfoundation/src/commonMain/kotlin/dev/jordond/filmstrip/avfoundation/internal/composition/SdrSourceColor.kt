package dev.jordond.filmstrip.avfoundation.internal.composition

import dev.jordond.filmstrip.avfoundation.internal.Placement
import dev.jordond.filmstrip.avfoundation.internal.failure.AvLoweringFailure
import dev.jordond.filmstrip.avfoundation.internal.toNSURL
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVURLAsset

/**
 * The pixel format an SDR clip's frame is converted into on its way to BT.709.
 *
 * This is BGRA on macOS and eight-bit YCbCr on iOS, each the format whose read lands closest to what AVFoundation makes
 * of the same clip under a composition naming BT.709. The simulator reads the same codes and macOS reads within two,
 * until a BT.2020 colour falls far outside BT.709. There macOS's own conversion pulls the colour in where this one
 * clips it, up to 20 codes apart, and a compositor cannot reach that conversion.
 */
internal expect val sdrTransferFormat: UInt

/**
 * Whether this OS has VideoToolbox's pixel transfer session.
 */
internal expect val hasPixelTransferSession: Boolean

/**
 * Why this OS cannot draw an SDR clip of wider primaries than an SD clip's beside an HDR clip, or null when it can.
 *
 * @param available Whether the pixel transfer that draws it exists here.
 */
internal fun sdrTransferLimit(available: Boolean = hasPixelTransferSession): String? =
  SDR_TRANSFER_LIMIT.takeUnless { available }

/**
 * Why this OS cannot draw [sources] beside an HDR clip, or null when it can.
 *
 * A source is only opened on an OS without the pixel transfer. A BT.709 clip and an SD clip draw without it, and one
 * that cannot be opened here counts as needing it. A buffer that can't be written to a file is one that cannot be
 * opened.
 *
 * @param bytesRoot The directory an in-memory source lands under.
 * @param available Whether the pixel transfer that draws such a clip exists here.
 */
internal fun sdrTransferLimit(
  sources: List<MediaSource>,
  bytesRoot: String,
  available: Boolean = hasPixelTransferSession,
): String? =
  if (available || sources.none { it.needsPixelTransfer(bytesRoot) }) null else sdrTransferLimit(available = false)

/**
 * Why this OS cannot draw the clips laid beside an SD clip through the compositor, or null when it can.
 *
 * An SD clip draws from the shared matrices and a still from its photo, so only the rest are asked about.
 *
 * @param bytesRoot The directory an in-memory source lands under.
 * @param available Whether the pixel transfer that draws such a clip exists here.
 */
internal fun sdRouteTransferLimit(
  placements: List<Placement>,
  bytesRoot: String,
  available: Boolean = hasPixelTransferSession,
): String? =
  sdrTransferLimit(
    placements.filter { it.sdColor == null && it.clip.source !is MediaSource.Image }.map { it.clip.source }.distinct(),
    bytesRoot,
    available,
  )

@OptIn(ExperimentalForeignApi::class)
private fun MediaSource.needsPixelTransfer(bytesRoot: String): Boolean {
  val url =
    try {
      toNSURL(bytesRoot)
    } catch (_: AvLoweringFailure) {
      return true
    } catch (_: MediaBytesFileFailure) {
      return true
    }
  return AVURLAsset(uRL = url, options = null).needsPixelTransfer()
}

private const val SDR_TRANSFER_LIMIT =
  "Drawing an SDR clip on wider primaries than BT.709 in a tone mapped export takes VideoToolbox's pixel transfer " +
    "session, which takes iOS 16."
