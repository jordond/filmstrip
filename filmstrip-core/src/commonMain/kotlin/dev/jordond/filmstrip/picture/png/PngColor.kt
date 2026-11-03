package dev.jordond.filmstrip.picture.png

import dev.jordond.filmstrip.color.Cicp
import dev.jordond.filmstrip.color.Icc
import dev.jordond.filmstrip.color.PngChromaticities
import dev.jordond.filmstrip.color.PngGamma
import dev.jordond.filmstrip.color.rgbToXyz
import dev.jordond.filmstrip.picture.EmbeddedColor
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MAX_PROFILE_SIZE
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.bytes.malformed
import dev.jordond.filmstrip.picture.iccDescription

/**
 * The `cICP` and `iCCP` descriptions of a PNG a decoder wrote, leaving out the chunks it guesses, or null for any
 * other format and a malformed PNG.
 */
internal fun decodedPngColor(bytes: ByteArray): EmbeddedColor? {
  val png = ByteView(bytes, bytes.size)
  if (!png.matches(0, PNG_SIGNATURE)) return null
  return deferring { pngColor(png, decoded = true) }
}

// A decoded PNG keeps only cICP and iCCP, since its decoder writes the other chunks from what it guessed.
internal fun pngColor(
  png: ByteView,
  decoded: Boolean,
): EmbeddedColor {
  var chunk = png.pngChunk(PNG_SIGNATURE.size)
  if (chunk.type != PNG_IHDR) malformed()
  val chunks = HashMap<Int, PngChunk>()
  while (chunk.type != PNG_IDAT && chunk.type != PNG_IEND) {
    if (chunk.type in PNG_COLOR_CHUNKS && chunk.type !in chunks && png.crcHolds(chunk)) chunks[chunk.type] = chunk
    chunk = png.pngChunk(chunk.next)
  }
  val header = chunks[PNG_IHDR]?.takeIf { it.size == PNG_IHDR_SIZE } ?: malformed()
  val channels = PNG_CHANNELS[png.u8(header.start + 9)] ?: malformed()
  val gamma = chunks[GAMA]?.takeIf { it.size == 4 }?.let { png.u32(it.start) }?.takeIf { it in 1..MAX_GAMMA }
  val chromaticities = chunks[CHRM]?.let { png.chromaticities(it) }
  val cicp = chunks[CICP]?.let { png.cicp(it) }
  val iccp = chunks[ICCP_PNG]?.let { deferring { png.iccp(it) } }
  if (decoded) return EmbeddedColor(listOfNotNull(cicp, iccp), channels)
  val descriptions =
    listOfNotNull(
      cicp,
      iccp,
      chunks[SRGB]?.takeIf { it.size == 1 && png.u8(it.start) in 0..3 }?.let { SRGB_DESCRIPTION },
      if (gamma != null || chromaticities != null) PngGamma(gamma?.toInt(), chromaticities) else null,
    )
  return EmbeddedColor(descriptions, channels)
}

// PNG carries RGB samples only, so a cICP whose matrix is not the identity is not understood. A range flag other than
// 1 is not understood either, so a narrow-range PNG defers to its next description. Reading one would mean expanding
// its samples to full range and running its curve over the negative values that gives, which core does not do.
private fun ByteView.cicp(chunk: PngChunk): Cicp? {
  if (chunk.size != 4 || u8(chunk.start + 2) != 0 || u8(chunk.start + 3) != 1) return null
  return Cicp(u8(chunk.start), u8(chunk.start + 1), 0, fullRange = true)
}

private fun ByteView.iccp(chunk: PngChunk): Icc? {
  val data = within(chunk.end)
  val nameEnd = (chunk.start until minOf(chunk.end, chunk.start + 80)).firstOrNull { data.u8(it) == 0 } ?: return null
  if (nameEnd == chunk.start || data.u8(nameEnd + 1) != 0) return null
  val profile = inflateZlib(bytes, nameEnd + 2, chunk.end, MAX_PROFILE_SIZE, sizePrefixed = true)
  return iccDescription(profile?.takeIf { it.size >= ICC_HEADER_SIZE })
}

private fun ByteView.chromaticities(chunk: PngChunk): PngChromaticities? {
  if (chunk.size != 32) return null
  val values = IntArray(8) { u32(chunk.start + it * 4).takeIf { value -> value <= Int.MAX_VALUE }?.toInt() ?: -1 }
  if (values.any { it < 0 }) return null
  return PngChromaticities(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
    .takeIf { rgbToXyz(it.values) != null }
}

internal val CICP = fourcc("cICP")
internal val ICCP_PNG = fourcc("iCCP")
private val SRGB = fourcc("sRGB")
internal val GAMA = fourcc("gAMA")
internal val CHRM = fourcc("cHRM")
private val PNG_COLOR_CHUNKS = setOf(PNG_IHDR, CICP, ICCP_PNG, SRGB, GAMA, CHRM)

// The color channels each PNG color type stores. A palette's entries are color.
private val PNG_CHANNELS = mapOf(0 to 1, 2 to 3, 3 to 3, 4 to 1, 6 to 3)

// An sRGB chunk stands for exactly what sRGB's own code points do.
private val SRGB_DESCRIPTION = Cicp(primaries = 1, transfer = 13, matrix = 0, fullRange = true)

private const val MAX_GAMMA = Int.MAX_VALUE.toLong()
private const val ICC_HEADER_SIZE = 128
