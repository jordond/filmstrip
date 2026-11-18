package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.animated.CompositeResult
import dev.jordond.filmstrip.picture.animated.FramePixels
import dev.jordond.filmstrip.picture.animated.FrameSamples
import dev.jordond.filmstrip.picture.animated.compositeFrames
import java.util.IdentityHashMap

/**
 * Composites every frame of [read] and lays them out as the cells of one atlas picture.
 *
 * The frames are composited by core, and a frame stored as a still WebP is decoded by [decoder], all of them in one
 * [OverlayPictureDecoder.decodeAll] call. Each decoded frame is let go as it is composited, so the decoded frames and
 * the atlas are never both held in full beyond the copies the atlas needs. The atlas is eight bits a channel unless the
 * file stores sixteen.
 *
 * @return The atlas as a [StoredRead.Read] of the size [AnimatedFlipbookRead.Drawn.grid] gives, with the color the file
 * describes. [StoredRead.Unread] when a frame needs a decoder and there is none, and the decoder's own answer when it
 * cannot read, is stopped at its deadline or does not draw a frame. [StoredRead.Undrawn] with core's sentence when
 * a frame cannot be composited.
 */
internal fun storedAtlas(
  read: AnimatedFlipbookRead.Drawn,
  decoder: OverlayPictureDecoder?,
): StoredRead {
  val picture = read.picture
  val grid = read.grid
  val stills = picture.frames.mapNotNull { (it.pixels as? FramePixels.WebpStill)?.bytes }
  val decoded = IdentityHashMap<ByteArray, DecodedPicture>()
  if (stills.isNotEmpty()) {
    if (decoder == null) return StoredRead.Unread
    decodedInto(decoded, stills, decoder)?.let { return it }
  }

  val deep = picture.frames.any { (it.pixels as? FramePixels.Samples)?.sixteenBit == true }
  val atlas = grid.size
  val argb = if (deep) null else IntArray(atlas.width * atlas.height)
  val rgba = if (deep) ShortArray(atlas.width * atlas.height * RGBA) else null
  val composited =
    compositeFrames(
      picture,
      decodeStill = { bytes -> decoded.remove(bytes)?.frameSamples() },
      each = { index, canvas ->
        val cell = grid.cells[index]
        for (row in 0 until canvas.height) {
          val to = (cell.top + row) * atlas.width + cell.left
          val from = row * canvas.width
          if (argb != null) {
            for (x in 0 until canvas.width) argb[to + x] = canvas.rgba.packedAt(from + x)
          } else {
            canvas.rgba.copyInto(checkNotNull(rgba), to * RGBA, from * RGBA, (from + canvas.width) * RGBA)
          }
        }
      },
    )
  if (composited is CompositeResult.Refused) return StoredRead.Undrawn(composited.message)
  val samples = argb?.let { StoredSamples.Argb(it) } ?: StoredSamples.Rgba16(checkNotNull(rgba))
  return StoredRead.Read(StoredPicture(samples, atlas, read.color))
}

// Decodes [stills] in one call and puts each picture in [decoded] under the bytes it came from, or answers the read
// that stopped it. The call's own list of pictures is out of reach once this returns, so only [decoded] holds them.
private fun decodedInto(
  decoded: IdentityHashMap<ByteArray, DecodedPicture>,
  stills: List<ByteArray>,
  decoder: OverlayPictureDecoder,
): StoredRead? {
  val results = decoder.decodeAll(stills.map { ImageSource.ofBytes(it) })
  stills.forEachIndexed { index, bytes ->
    when (val result = results[index]) {
      is OverlayPictureRead.Read -> decoded[bytes] = result.value
      is OverlayPictureRead.Refused -> return StoredRead.Undecoded(result.reason)
      is OverlayPictureRead.Expired -> return StoredRead.Expired(result.timedOut())
      is OverlayPictureDecode.Undrawn -> return StoredRead.Undrawn(result.reason)
    }
  }
  return null
}

// The samples of a decoded frame in the form core composites, which a code of eight bits is scaled to by 257.
private fun DecodedPicture.frameSamples(): FrameSamples =
  when (val stored = samples) {
    is StoredSamples.Argb -> {
      val wide = ShortArray(stored.argb.size * RGBA)
      stored.argb.forEachIndexed { index, texel ->
        val at = index * RGBA
        wide[at] = (((texel shr RED_SHIFT) and BYTE) * WIDEN).toShort()
        wide[at + 1] = (((texel shr GREEN_SHIFT) and BYTE) * WIDEN).toShort()
        wide[at + 2] = ((texel and BYTE) * WIDEN).toShort()
        wide[at + 3] = (((texel ushr ALPHA_SHIFT) and BYTE) * WIDEN).toShort()
      }
      FrameSamples(size.width, size.height, wide, sixteenBit = false)
    }
    is StoredSamples.Rgba16 -> {
      FrameSamples(size.width, size.height, stored.rgba, sixteenBit = true)
    }
  }

// The packed ARGB code of the texel at [index], each sample rounded to the nearest code of eight bits.
private fun ShortArray.packedAt(index: Int): Int {
  val at = index * RGBA
  val alpha = code(this[at + 3])
  val red = code(this[at])
  val green = code(this[at + 1])
  val blue = code(this[at + 2])
  return (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
}

private fun code(sample: Short): Int = ((sample.toInt() and WIDE) + WIDEN / 2) / WIDEN

/**
 * What an animated picture's atlas is once it is ready for a graph.
 */
internal sealed interface AnimatedGraph {
  /**
   * The graph opens [picture].
   */
  class Ready(
    val picture: GraphPicture,
    val limits: List<Degradation> = emptyList(),
  ) : AnimatedGraph

  /**
   * The atlas cannot be made, for the reason [message] gives.
   */
  class Refused(
    val message: String,
  ) : AnimatedGraph

  /**
   * The decode of the frames was stopped at its deadline.
   */
  class Failed(
    val timedOut: FilmstripError.TimedOut,
  ) : AnimatedGraph
}

/**
 * The atlas of [read] as the picture the graph of a grade opens, an SDR grade through [sdrAtlasImage] and an HDR one
 * through [hdrOverlayRaster].
 *
 * The composited atlas is held only for the length of this call, so a caller keeps the finished form and nothing else.
 */
internal fun animatedGraph(
  read: AnimatedFlipbookRead.Drawn,
  decoder: OverlayPictureDecoder?,
  transfer: HdrTransfer?,
): AnimatedGraph {
  val atlas =
    when (val stored = storedAtlas(read, decoder)) {
      is StoredRead.Read -> stored.picture
      is StoredRead.Undecoded -> return AnimatedGraph.Refused(UNDECODED_ANIMATED.saying(stored.reason))
      is StoredRead.Expired -> return AnimatedGraph.Failed(stored.timedOut)
      is StoredRead.Undrawn -> return AnimatedGraph.Refused(stored.reason)
      StoredRead.Unread -> return AnimatedGraph.Refused(UNREAD_ANIMATED)
      StoredRead.Unseen -> return AnimatedGraph.Refused(UNSEEN_COLOR)
    }
  if (transfer != null) return AnimatedGraph.Ready(GraphPicture.Graded(hdrOverlayRaster(atlas, transfer), transfer))
  val drawn = sdrAtlasImage(atlas)
  return AnimatedGraph.Ready(GraphPicture.Opened(drawn.image, drawn.orientation))
}

private const val UNDECODED_ANIMATED =
  "The animated overlay image could not be read. Its frames are WebP pictures that this backend decodes with its " +
    "toolchain, and this ffmpeg could not decode one. Check that this ffmpeg build decodes WebP."

private const val UNREAD_ANIMATED =
  "The animated overlay image could not be read. Its frames are WebP pictures that only this backend's toolchain " +
    "decodes, and this catalogue was built without a picture decoder. Build the catalogue with the backend's picture " +
    "decoder, or use a GIF or an APNG."

/**
 * Why an animated picture whose atlas is [size] cannot be opened by ffmpeg, which refuses a frame past its own limit.
 */
internal fun animatedPastFrameLimit(size: Size): String =
  "The ${size.width}x${size.height} atlas of this animated picture is past ffmpeg's frame size limit, so ffmpeg " +
    "cannot open it. Use fewer frames or a smaller picture."

/**
 * Why an animated picture whose atlas of [size] is held at [bytesPerTexel] bytes a texel is refused when a frame may
 * hold [ceiling] bytes at most.
 */
internal fun animatedAtlasTooLarge(
  size: Size,
  bytesPerTexel: Int,
  ceiling: Long,
): String =
  "The ${size.width}x${size.height} atlas of this animated picture is too large to draw. ffmpeg holds it as one " +
    "frame of $bytesPerTexel bytes a texel, and a frame may hold $ceiling bytes at most."

private const val RGBA = 4
private const val BYTE = 0xFF
private const val WIDE = 0xFFFF
private const val WIDEN = 257
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
