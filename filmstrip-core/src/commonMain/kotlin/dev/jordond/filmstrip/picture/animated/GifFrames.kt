package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.bytes.malformed
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads a GIF into its frames, decoding every frame in core.
 *
 * The blocks are walked once for the frames' headers, which settles whether the file animates and whether it fits
 * [ceiling], and then each frame's LZW stream is decoded. A frame that is not written whole, an index past its colour
 * table and a frame past the canvas are not guessed at. An index past its table reads as transparent.
 */
internal fun ByteView.readGif(ceiling: Long): AnimatedRead {
  val frames = ArrayList<GifFrame>()
  var started = 0
  var loops: Int? = null
  var screen = Size(0, 0)
  try {
    screen = Size(u16(SCREEN_WIDTH_AT, little = true), u16(SCREEN_HEIGHT_AT, little = true))
    val flags = u8(SCREEN_FLAGS_AT)
    var at = HEADER_SIZE
    var global: GifTable? = null
    if (flags and TABLE_FLAG != 0) {
      global = GifTable(at, 2 shl (flags and TABLE_SIZE_BITS))
      at += global.byteCount
    }
    var control = GifControl()
    while (at < end) {
      when (u8(at)) {
        EXTENSION -> {
          when (u8(at + 1)) {
            GRAPHIC_CONTROL -> control = graphicControl(at)
            APPLICATION -> if (loops == null) loops = netscapeLoops(at)
          }
          at = skipBlocks(at + 2)
        }
        IMAGE -> {
          started++
          val frame = imageHeader(at, global, control)
          frames += frame
          at = frame.end
          control = GifControl()
        }
        TRAILER -> {
          break
        }
        else -> {
          malformed()
        }
      }
    }
  } catch (_: MalformedPicture) {
    return if (started >= 2) AnimatedRead.Refused(ANIMATED_UNREADABLE) else AnimatedRead.Still
  }
  if (frames.size < 2) return AnimatedRead.Still
  if (screen.width == 0 || screen.height == 0) return AnimatedRead.Refused(animatedCanvasEmpty())
  frames.forEachIndexed { index, frame ->
    if (!frame.region.liesWithin(screen)) return AnimatedRead.Refused(animatedFrameOutside(index))
  }
  val held =
    frames.sumOf {
      it.region.size.width
        .toLong() * it.region.size.height * TEXEL_BYTES
    }
  if (held > ceiling) return AnimatedRead.Refused(animatedPictureTooLarge(held, ceiling))
  return try {
    val lzw = GifLzw()
    val stored = frames.map { it.decoded(this, lzw) }
    val delays =
      frames.map {
        shownFrameDelay(PictureContainer.Gif, (it.control.centiseconds * MILLIS_PER_CENTISECOND).milliseconds)
      }
    AnimatedRead.Animated(
      AnimatedPicture(screen, stored, FrameTimeline(delays, framePlays(PictureContainer.Gif, loops))),
    )
  } catch (_: MalformedPicture) {
    AnimatedRead.Refused(ANIMATED_UNREADABLE)
  }
}

private class GifTable(
  val at: Int,
  val count: Int,
) {
  val byteCount: Int get() = count * COLOR_BYTES
}

private class GifControl(
  val disposal: FrameDisposal = FrameDisposal.None,
  val transparent: Int = -1,
  val centiseconds: Int = 0,
)

private class GifFrame(
  val region: PictureCrop,
  val interlaced: Boolean,
  val table: GifTable?,
  val control: GifControl,
  val codeSizeAt: Int,
  val end: Int,
) {
  fun decoded(
    file: ByteView,
    lzw: GifLzw,
  ): StoredFrame {
    val width = region.size.width
    val height = region.size.height
    val minimumCodeSize = file.u8(codeSizeAt)
    if (minimumCodeSize !in 1..MAX_CODE_SIZE) malformed()
    val stream = file.gatherBlocks(codeSizeAt + 1)
    val indices = ByteArray(width * height)
    if (!lzw.decode(stream, stream.size, minimumCodeSize, indices)) malformed()
    return StoredFrame(
      region,
      FrameBlend.Over,
      control.disposal,
      FramePixels.Texels(expand(file, indices, width, height)),
    )
  }

  private fun expand(
    file: ByteView,
    indices: ByteArray,
    width: Int,
    height: Int,
  ): IntArray {
    val lookup = IntArray(PALETTE_SIZE)
    if (table != null) {
      for (index in 0 until minOf(table.count, PALETTE_SIZE)) {
        if (index == control.transparent) continue
        val at = table.at + index * COLOR_BYTES
        lookup[index] = OPAQUE or (file.u8(at) shl RED_SHIFT) or (file.u8(at + 1) shl GREEN_SHIFT) or file.u8(at + 2)
      }
    }
    val argb = IntArray(width * height)
    for (stored in 0 until height) {
      val row = if (interlaced) interlacedRow(stored, height) else stored
      var from = stored * width
      var to = row * width
      val stop = from + width
      while (from < stop) argb[to++] = lookup[indices[from++].toInt() and INDEX_MASK]
    }
    return argb
  }
}

private fun ByteView.graphicControl(at: Int): GifControl {
  val flags = u8(at + 3)
  val disposal =
    when ((flags shr DISPOSAL_SHIFT) and DISPOSAL_MASK) {
      DISPOSE_BACKGROUND -> FrameDisposal.Background
      DISPOSE_PREVIOUS -> FrameDisposal.Previous
      else -> FrameDisposal.None
    }
  val transparent = if (flags and TRANSPARENT_FLAG != 0) u8(at + 6) else -1
  return GifControl(disposal, transparent, u16(at + 4, little = true))
}

private fun ByteView.netscapeLoops(at: Int): Int? {
  if (u8(at + 2) != NETSCAPE.size || !matches(at + 3, NETSCAPE)) return null
  val block = at + 3 + NETSCAPE.size
  return if (u8(block) >= 3 && u8(block + 1) == 1) u16(block + 2, little = true) else null
}

private fun ByteView.imageHeader(
  at: Int,
  global: GifTable?,
  control: GifControl,
): GifFrame {
  val region =
    PictureCrop(
      u16(at + 1, little = true),
      u16(at + 3, little = true),
      Size(u16(at + 5, little = true), u16(at + 7, little = true)),
    )
  val flags = u8(at + 9)
  var next = at + IMAGE_HEADER_SIZE
  var table = global
  if (flags and TABLE_FLAG != 0) {
    table = GifTable(next, 2 shl (flags and TABLE_SIZE_BITS))
    next += table.byteCount
  }
  return GifFrame(region, flags and INTERLACE_FLAG != 0, table, control, next, skipBlocks(next + 1))
}

private fun ByteView.skipBlocks(from: Int): Int {
  var at = from
  while (true) {
    val size = u8(at)
    at += 1 + size
    if (size == 0) return at
  }
}

private fun ByteView.gatherBlocks(from: Int): ByteArray {
  var total = 0
  var at = from
  while (true) {
    val size = u8(at)
    if (size == 0) break
    total += size
    at += 1 + size
  }
  val out = ByteArray(total)
  var to = 0
  at = from
  while (to < total) {
    val size = u8(at)
    to = copyInto(out, to, at + 1, at + 1 + size)
    at += 1 + size
  }
  return out
}

// The row of the frame the [stored]th stored row of an interlaced image of [height] rows belongs to. The four passes
// store every eighth row from 0, every eighth from 4, every fourth from 2 and every other row from 1.
private fun interlacedRow(
  stored: Int,
  height: Int,
): Int {
  var left = stored
  val first = (height + 7) / 8
  if (left < first) return left * 8
  left -= first
  val second = (height + 3) / 8
  if (left < second) return 4 + left * 8
  left -= second
  val third = (height + 1) / 4
  if (left < third) return 2 + left * 4
  return 1 + (left - third) * 2
}

private val NETSCAPE = ascii("NETSCAPE2.0")

private const val SCREEN_WIDTH_AT = 6
private const val SCREEN_HEIGHT_AT = 8
private const val SCREEN_FLAGS_AT = 10
private const val HEADER_SIZE = 13
private const val IMAGE_HEADER_SIZE = 10
private const val TABLE_FLAG = 0x80
private const val TABLE_SIZE_BITS = 7
private const val INTERLACE_FLAG = 0x40
private const val TRANSPARENT_FLAG = 1
private const val DISPOSAL_SHIFT = 2
private const val DISPOSAL_MASK = 7
private const val DISPOSE_BACKGROUND = 2
private const val DISPOSE_PREVIOUS = 3
private const val EXTENSION = 0x21
private const val IMAGE = 0x2C
private const val TRAILER = 0x3B
private const val GRAPHIC_CONTROL = 0xF9
private const val APPLICATION = 0xFF
private const val COLOR_BYTES = 3
private const val PALETTE_SIZE = 256
private const val INDEX_MASK = 0xFF
private const val TEXEL_BYTES = 4L
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val OPAQUE = 0xFF000000.toInt()
private const val MAX_CODE_SIZE = 11
private const val MILLIS_PER_CENTISECOND = 10
