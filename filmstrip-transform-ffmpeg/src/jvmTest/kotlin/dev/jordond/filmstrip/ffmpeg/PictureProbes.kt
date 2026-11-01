package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.UNLISTED_ALPHA
import dev.jordond.filmstrip.ffmpeg.internal.parseVersion
import dev.jordond.filmstrip.ffmpeg.internal.pictureRoute
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.YcbcrCoding
import dev.jordond.filmstrip.test.AlphaPlanes
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeifAlphaPlane
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.HeifThumbnail
import org.junit.Assume.assumeTrue
import kotlin.test.assertTrue

// What this ffmpeg can open, for the picture tests that skip as an assumption on a build without it. ffmpeg 6.1 has no
// HEIF demuxer, reads a grid item's data as a coded picture and fails on it, and lists no stream for an AVIF's alpha
// item. Those are the build's own limits. ffmpeg 9 does all three, so on 9 and later a HEIC or a tile grid that does
// not decode, or an alpha item with no stream, fails the test rather than skipping it. A build in between, or one whose
// banner gives no release number, such as an N- snapshot or a dated git build, skips.

private val probe = FfmpegPictureDecoder(FFMPEG)

private val heif: Boolean by lazy { decodes(ColourPictures.HEICS.first().bytes()) }

private val alphaStreams: Boolean by lazy {
  val still = alphaStill(ColourPictures.NCLX_UNSPECIFIED.thumbnail(), AlphaPlanes.avif())
  val read = probe.decode(ImageSource.ofBytes(still))
  read is OverlayPictureRead.Read && UNLISTED_ALPHA !in read.value.limits
}

// Whether this ffmpeg decodes [bytes] at all.
internal fun decodes(bytes: ByteArray): Boolean = probe.decode(ImageSource.ofBytes(bytes)) is OverlayPictureRead.Read

// Whether this ffmpeg opens a HEIC.
internal fun decodesHeif(): Boolean = heif

// Whether this ffmpeg lists a stream for an AVIF's alpha item.
internal fun listsAlphaStreams(): Boolean = alphaStreams

// The major version of the ffmpeg on the path, or null when its banner gives no release number.
private val major: Int? by lazy {
  val version = ProcessBuilder(FFMPEG, "-version").redirectErrorStream(true).start()
  val banner = version.inputStream.readAllBytes().decodeToString()
  version.waitFor()
  parseVersion(banner).major
}

// Whether the ffmpeg on the path is a release these probes fail on rather than skip.
private val verified: Boolean
  get() = major?.let { it >= VERIFIED_MAJOR } == true

internal fun assumeHeif() {
  assertTrue(heif || !verified, "ffmpeg $major has a HEIF demuxer and did not decode a HEIC")
  assumeTrue("this ffmpeg has no HEIF demuxer, as 6.1 does not", heif)
}

internal fun assumeAlphaStreams() {
  assertTrue(alphaStreams || !verified, "ffmpeg $major lists no stream for an AVIF's alpha item")
  assumeTrue("this ffmpeg lists no stream for an alpha item, as 6.1 does not", alphaStreams)
}

internal fun assumeTiles(grid: ByteArray) {
  val tiles = decodes(grid)
  assertTrue(tiles || !verified, "ffmpeg $major reads tile grids and did not decode one")
  assumeTrue("this ffmpeg has no tile grids, as 6.1 does not", tiles)
}

// The still of [item] with [alpha] as its alpha item, branded for the item's codec.
internal fun alphaStill(
  item: HeifThumbnail,
  alpha: HeifAlphaPlane?,
): ByteArray {
  val brand = if (item.itemType == "av01") "avif" else "heic"
  return HeifStills.still(brand, item.itemType, item.configuration, item.codestream, item.size, alpha = alpha)
}

// The filter ffmpeg converts a picture whose coding is [coding] through, or null when it is decoded from its planes or
// refused.
internal fun convertedFilter(coding: YcbcrCoding?): String? = (pictureRoute(coding) as? PictureRoute.Converted)?.filter

private const val FFMPEG = "ffmpeg"

// The newest ffmpeg release these tests have run on, and the first they fail on rather than skip.
private const val VERIFIED_MAJOR = 9
