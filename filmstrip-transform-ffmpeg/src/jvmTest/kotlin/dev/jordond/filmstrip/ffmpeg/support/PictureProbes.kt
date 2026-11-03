package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.PictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.picture.UNLISTED_ALPHA
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRoute
import dev.jordond.filmstrip.ffmpeg.internal.process.parseVersion
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.heif.heifPrimaryGrid
import dev.jordond.filmstrip.test.AlphaPlanes
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifAlphaPlane
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.HeifThumbnail
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.Assume.assumeTrue
import java.io.File

// What this ffmpeg can open, for the picture tests that skip as an assumption on a build without it. ffmpeg 6.1 has no
// HEIF demuxer, reads a grid item's data as a coded picture and fails on it, and lists no stream for an AVIF's alpha
// item. Those are the build's own limits. ffmpeg 9 does all three, so on 9 and later a HEIC or a tile grid that does
// not decode, or an alpha item with no stream, fails the test rather than skipping it. A build in between, or one whose
// banner gives no release number, such as an N- snapshot or a dated git build, skips.

private val probe = FfmpegPictureDecoder(FFMPEG)

private val heif: Boolean by lazy { decodes(ColorPictures.HEICS.first().bytes()) }

private val alphaStreams: Boolean by lazy {
  val still = alphaStill(ColorPictures.NCLX_UNSPECIFIED.thumbnail(), AlphaPlanes.avif())
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
  withClue("ffmpeg $major has a HEIF demuxer and did not decode a HEIC") { (heif || !verified).shouldBeTrue() }
  assumeTrue("this ffmpeg has no HEIF demuxer, as 6.1 does not", heif)
}

internal fun assumeAlphaStreams() {
  withClue("ffmpeg $major lists no stream for an AVIF's alpha item") { (alphaStreams || !verified).shouldBeTrue() }
  assumeTrue("this ffmpeg lists no stream for an alpha item, as 6.1 does not", alphaStreams)
}

internal fun assumeTiles(grid: ByteArray) {
  val tiles = listsTileStreams(grid)
  withClue("ffmpeg $major lists no stream for a grid's tile") { (tiles || !verified).shouldBeTrue() }
  assumeTrue("this ffmpeg lists no stream for a grid's tiles, as 6.1 does not", tiles)
}

// Whether this ffmpeg lists a stream for the first tile of [grid], a HEIF file whose primary item is a grid, by the
// tile's item id. ffmpeg 7 and later do, and 6.1 lists none.
private fun listsTileStreams(grid: ByteArray): Boolean {
  val tile = checkNotNull(heifPrimaryGrid(grid)) { "the picture's primary item is not a grid" }.tiles.first()
  val file = File.createTempFile("filmstrip-tile-probe", ".heic")
  return try {
    file.writeBytes(grid)
    val command =
      listOf(FFMPEG, "-nostdin", "-hide_banner", "-loglevel", "error", "-i", file.absolutePath, "-map", "0:#$tile") +
        listOf("-frames:v", "1", "-f", "null", "-")
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    process.inputStream.readAllBytes()
    process.waitFor() == 0
  } finally {
    file.delete()
  }
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
