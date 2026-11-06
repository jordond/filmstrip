package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The extension a buffer whose container nothing could name is written under.
 *
 * A buffer with no `ftyp` and no other magic [containerExtensionOf] knows is most often a QuickTime file, which
 * predates that box. AVFoundation reads the QuickTime and MPEG-4 family under any name in that family. The retriever,
 * media3 and ffmpeg all sniff, so the name costs them nothing whatever the buffer holds, though the Android retriever
 * refuses a QuickTime file with no `ftyp` under any name.
 */
@InternalFilmstripApi
public const val NEUTRAL_CONTAINER_EXTENSION: String = "mov"

/**
 * The file extension [bytes] are written under so a platform reader can open them.
 *
 * [hint] wins when there is one. Otherwise the leading `ftyp` box answers, major brand first and then the compatible
 * brands in order. The first of `qt  `, `M4A ` or a `3gp` brand decides, and an `ftyp` holding none of those is an mp4.
 * Without an `ftyp` box the leading bytes answer for WAV, AVI, CAF, AIFF, FLAC, Ogg, MP3, ADTS AAC and an MPEG
 * transport stream in 188 or 192 byte packets, since AVFoundation goes by the name and reads each of those only under
 * its own. Anything else is [NEUTRAL_CONTAINER_EXTENSION].
 *
 * The box's own length is only trusted up to the end of [bytes], and at most [MAX_FTYP_BRANDS] brands are read. Reading
 * the leading bytes looks at fixed offsets only, and a buffer too short to reach one does not match.
 *
 * @param bytes The encoded media, container and all.
 * @param hint What the caller said [bytes] holds, if it said.
 * @return The extension, without a leading dot.
 */
@InternalFilmstripApi
public fun containerExtensionOf(
  bytes: ByteArray,
  hint: FormatHint? = null,
): String =
  when (hint) {
    FormatHint.Mp4 -> EXTENSION_MP4
    FormatHint.Mov -> EXTENSION_MOV
    FormatHint.M4a -> EXTENSION_M4A
    FormatHint.ThreeGp -> EXTENSION_3GP
    null -> extensionFromFtyp(bytes) ?: extensionFromMagic(bytes) ?: NEUTRAL_CONTAINER_EXTENSION
  }

/**
 * How many brands of an `ftyp` box [containerExtensionOf] reads, the major brand included.
 *
 * Real files carry a handful, so this only stops a forged box making the read walk the whole buffer.
 */
internal const val MAX_FTYP_BRANDS = 64

private fun extensionFromFtyp(bytes: ByteArray): String? {
  val end = ftypEndOf(bytes) ?: return null

  brandOffsets(end).forEach { offset ->
    brandExtensionAt(bytes, offset)?.let { return it }
  }
  return EXTENSION_MP4
}

/**
 * Where the leading `ftyp` box of [bytes] ends, clamped to [bytes], or null when there is no such box holding a brand.
 *
 * A box length of zero runs to the end of the buffer. A length of one announces a 64-bit length that an `ftyp` never
 * needs, so that box is read as holding no brand.
 */
private fun ftypEndOf(bytes: ByteArray): Int? {
  if (bytes.size < MAJOR_BRAND_OFFSET + BRAND_BYTES) return null
  if (!FTYP.indices.all { bytes[FTYP_OFFSET + it] == FTYP[it] }) return null

  val declared = (0 until FTYP_OFFSET).fold(0L) { length, index -> (length shl 8) or (bytes[index].toLong() and 0xFF) }
  return when {
    declared == 0L -> bytes.size
    declared < MAJOR_BRAND_OFFSET + BRAND_BYTES -> null
    else -> minOf(declared, bytes.size.toLong()).toInt()
  }
}

/**
 * The offsets of the major brand and the compatible brands inside a box ending at [end].
 *
 * The minor version sits between the two, and it is not a brand.
 */
private fun brandOffsets(end: Int): Sequence<Int> =
  sequenceOf(MAJOR_BRAND_OFFSET) +
    (COMPATIBLE_BRANDS_OFFSET..end - BRAND_BYTES step BRAND_BYTES)
      .asSequence()
      .take(MAX_FTYP_BRANDS - 1)

private fun brandExtensionAt(
  bytes: ByteArray,
  offset: Int,
): String? =
  when {
    brandMatches(bytes, offset, BRAND_QT) -> EXTENSION_MOV
    brandMatches(bytes, offset, BRAND_M4A) -> EXTENSION_M4A
    brandMatches(bytes, offset, BRAND_3GP_PREFIX) -> EXTENSION_3GP
    else -> null
  }

private fun brandMatches(
  bytes: ByteArray,
  offset: Int,
  brand: ByteArray,
): Boolean = brand.indices.all { bytes[offset + it] == brand[it] }

private fun extensionFromMagic(bytes: ByteArray): String? =
  when {
    tagAt(bytes, 0, "RIFF") && tagAt(bytes, FORM_TYPE_OFFSET, "WAVE") -> "wav"
    tagAt(bytes, 0, "RIFF") && tagAt(bytes, FORM_TYPE_OFFSET, "AVI ") -> "avi"
    tagAt(bytes, 0, "FORM") && tagAt(bytes, FORM_TYPE_OFFSET, "AIFF") -> "aiff"
    tagAt(bytes, 0, "FORM") && tagAt(bytes, FORM_TYPE_OFFSET, "AIFC") -> "aiff"
    tagAt(bytes, 0, "caff") -> "caf"
    tagAt(bytes, 0, "fLaC") -> "flac"
    tagAt(bytes, 0, "OggS") -> "ogg"
    tagAt(bytes, 0, "ID3") -> "mp3"
    isAdtsHeader(bytes) -> "aac"
    isMpegAudioHeader(bytes) -> "mp3"
    isTransportStream(bytes) -> "ts"
    else -> null
  }

private fun tagAt(
  bytes: ByteArray,
  offset: Int,
  tag: String,
): Boolean = bytes.size >= offset + tag.length && tag.indices.all { bytes[offset + it].toInt() == tag[it].code }

private fun unsignedAt(
  bytes: ByteArray,
  offset: Int,
): Int = bytes[offset].toInt() and 0xFF

// Twelve sync bits, then the layer is always zero in ADTS, and the top sample rate indices are reserved.
private fun isAdtsHeader(bytes: ByteArray): Boolean =
  bytes.size >= ADTS_HEADER_BYTES &&
    unsignedAt(bytes, 0) == 0xFF &&
    unsignedAt(bytes, 1) and 0xF6 == 0xF0 &&
    (unsignedAt(bytes, 2) shr 2) and 0xF <= ADTS_MAX_SAMPLE_RATE_INDEX

// Eleven sync bits, then no reserved version or layer, and neither the bitrate nor the sample rate that mean bad.
private fun isMpegAudioHeader(bytes: ByteArray): Boolean {
  if (bytes.size < MPEG_AUDIO_HEADER_BYTES) return false
  if (unsignedAt(bytes, 0) != 0xFF || unsignedAt(bytes, 1) and 0xE0 != 0xE0) return false
  val version = (unsignedAt(bytes, 1) shr 3) and 0x3
  val layer = (unsignedAt(bytes, 1) shr 1) and 0x3
  val bitrate = unsignedAt(bytes, 2) shr 4
  val sampleRate = (unsignedAt(bytes, 2) shr 2) and 0x3
  return version != 1 && layer != 0 && bitrate != 0xF && sampleRate != 0x3
}

// A 192-byte M2TS packet is a 188-byte one behind a four-byte timestamp.
private fun isTransportStream(bytes: ByteArray): Boolean =
  hasTwoSyncs(bytes, first = 0, packetBytes = TS_PACKET_BYTES) ||
    hasTwoSyncs(bytes, first = M2TS_TIMESTAMP_BYTES, packetBytes = TS_PACKET_BYTES + M2TS_TIMESTAMP_BYTES)

// One sync byte is any byte, so the next packet has to carry one as well.
private fun hasTwoSyncs(
  bytes: ByteArray,
  first: Int,
  packetBytes: Int,
): Boolean =
  bytes.size > first + packetBytes &&
    unsignedAt(bytes, first) == TS_SYNC &&
    unsignedAt(bytes, first + packetBytes) == TS_SYNC

private const val EXTENSION_MP4 = "mp4"
private const val EXTENSION_MOV = "mov"
private const val EXTENSION_M4A = "m4a"
private const val EXTENSION_3GP = "3gp"

private const val FTYP_OFFSET = 4
private const val MAJOR_BRAND_OFFSET = 8
private const val COMPATIBLE_BRANDS_OFFSET = 16
private const val BRAND_BYTES = 4

private const val FORM_TYPE_OFFSET = 8
private const val ADTS_HEADER_BYTES = 3
private const val ADTS_MAX_SAMPLE_RATE_INDEX = 12
private const val MPEG_AUDIO_HEADER_BYTES = 3
private const val TS_PACKET_BYTES = 188
private const val M2TS_TIMESTAMP_BYTES = 4
private const val TS_SYNC = 0x47

private val FTYP = "ftyp".encodeToByteArray()
private val BRAND_QT = "qt  ".encodeToByteArray()
private val BRAND_M4A = "M4A ".encodeToByteArray()
private val BRAND_3GP_PREFIX = "3gp".encodeToByteArray()
