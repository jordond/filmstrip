package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.png.PNG_IHDR
import dev.jordond.filmstrip.picture.png.PNG_SIGNATURE
import dev.jordond.filmstrip.picture.png.pngChunk

/**
 * How many leading bytes of a still [imageHasAlphaOf] needs to answer.
 */
internal const val ALPHA_HEADER_BYTES: Int = 64 * 1024

/**
 * Whether a still can carry transparent pixels, read from its leading bytes alone.
 *
 * A PNG answers from its colour type or a `tRNS` chunk ahead of the pixels, and a WebP from the alpha flag of its
 * `VP8X` or `VP8L` header. Every other format reads as opaque, as does a header that stops before it says. [header]
 * only needs the first [ALPHA_HEADER_BYTES] bytes of the file.
 */
internal fun imageHasAlphaOf(header: ByteArray): Boolean {
  val view = ByteView(header, header.size)
  return try {
    when (view.container()) {
      PictureContainer.Png -> view.pngHasAlpha()
      PictureContainer.Webp -> view.webpHasAlpha()
      else -> false
    }
  } catch (_: MalformedPicture) {
    false
  }
}

private fun ByteView.pngHasAlpha(): Boolean {
  var pos = PNG_SIGNATURE.size
  while (pos < end) {
    val chunk = pngChunk(pos)
    when (chunk.type) {
      PNG_IHDR -> if (u8(chunk.start + COLOUR_TYPE_AT) in ALPHA_COLOUR_TYPES) return true
      PNG_TRNS -> return true
      PNG_IDAT -> return false
    }
    pos = chunk.next
  }

  return false
}

private fun ByteView.webpHasAlpha(): Boolean =
  when (u32(WEBP_CHUNK_AT).toInt()) {
    WEBP_VP8X -> u8(WEBP_DATA_AT) and VP8X_ALPHA_FLAG != 0
    WEBP_VP8L -> u8(WEBP_DATA_AT + VP8L_ALPHA_BYTE) and VP8L_ALPHA_BIT != 0
    else -> false
  }

private const val COLOUR_TYPE_AT = 9
private val ALPHA_COLOUR_TYPES = setOf(4, 6)
private val PNG_TRNS = fourcc("tRNS")
private val PNG_IDAT = fourcc("IDAT")

private const val WEBP_CHUNK_AT = 12
private const val WEBP_DATA_AT = 20
private val WEBP_VP8L = fourcc("VP8L")
private const val VP8X_ALPHA_FLAG = 0x10

// VP8L spends a signature byte, then 14 bits of width and 14 of height, then the alpha bit.
private const val VP8L_ALPHA_BYTE = 4
private const val VP8L_ALPHA_BIT = 0x10
