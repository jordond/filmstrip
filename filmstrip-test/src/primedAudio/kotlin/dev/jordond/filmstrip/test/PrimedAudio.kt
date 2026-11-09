package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.test.MovieBoxes.ascii
import dev.jordond.filmstrip.test.MovieBoxes.box
import dev.jordond.filmstrip.test.MovieBoxes.fullBox
import dev.jordond.filmstrip.test.MovieBoxes.mediaHeader
import dev.jordond.filmstrip.test.MovieBoxes.movieHeader
import dev.jordond.filmstrip.test.MovieBoxes.trackHeader
import dev.jordond.filmstrip.test.MovieBoxes.u32
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Core's own tests compile this directory in as well, since core cannot depend on this module.

/**
 * An MP4 holding one mono AAC track that opens on an encoder delay its edit list skips, laid out the way the media3
 * muxer writes one.
 *
 * Every frame is the same frame of silence, so the file decodes on any platform and its lengths are known to the
 * sample.
 */
@InternalFilmstripApi
public object PrimedAudio {
  /**
   * How many AAC frames the track holds, each [AAC_FRAME] samples long.
   */
  public const val FRAMES: Int = 345

  /**
   * The encoder delay at the head of the track, in samples, which its edit list skips.
   */
  public const val DELAY: Int = 1600

  /**
   * The sample rate of the track.
   */
  public const val RATE: Int = 48_000

  /**
   * How many samples one AAC frame holds.
   */
  public const val AAC_FRAME: Int = 1024

  /**
   * How long the track's samples run, which is what a probe that ignores the edit list reports.
   */
  public val mediaLength: Duration = (FRAMES * AAC_FRAME).toDouble().div(RATE).seconds

  /**
   * How long the file plays for, its samples less the delay its edit list skips.
   */
  public val presentedLength: Duration = (FRAMES * AAC_FRAME - DELAY).toDouble().div(RATE).seconds

  /**
   * The file, with its `moov` ahead of its `mdat` unless [moovLast] puts it after.
   */
  public fun mp4(moovLast: Boolean = false): ByteArray {
    val ftyp = box("ftyp", ascii("isom") + u32(0x200) + ascii("isomiso2mp41"))
    val payload = ByteArray(FRAMES * SILENT_FRAME.size) { SILENT_FRAME[it % SILENT_FRAME.size] }
    val mdatHeader = 8
    // The chunk offset depends on where the mdat lands, and the moov is the same length whatever offset it holds.
    val moovLength = moov(chunkOffset = 0).size
    val mdatStart = ftyp.size + if (moovLast) 0 else moovLength
    val moov = moov(chunkOffset = mdatStart + mdatHeader)
    val mdat = box("mdat", payload)
    return if (moovLast) ftyp + mdat + moov else ftyp + moov + mdat
  }

  private fun moov(chunkOffset: Int): ByteArray {
    val samples = (FRAMES * AAC_FRAME).toLong()
    val presented = samples - DELAY
    val elst = fullBox("elst", 0, u32(1) + u32(presented) + u32(DELAY.toLong()) + u16(1) + u16(0))
    val hdlr = fullBox("hdlr", 0, u32(0) + ascii("soun") + ByteArray(12) + ascii("SoundHandler") + byteArrayOf(0))
    val dinf = box("dinf", fullBox("dref", 0, u32(1) + fullBox("url ", 0, ByteArray(0), flags = 1)))
    val stbl =
      box(
        "stbl",
        fullBox("stsd", 0, u32(1) + mp4a()) +
          fullBox("stts", 0, u32(1) + u32(FRAMES.toLong()) + u32(AAC_FRAME.toLong())) +
          fullBox("stsc", 0, u32(1) + u32(1) + u32(FRAMES.toLong()) + u32(1)) +
          fullBox("stsz", 0, u32(SILENT_FRAME.size.toLong()) + u32(FRAMES.toLong())) +
          fullBox("stco", 0, u32(1) + u32(chunkOffset.toLong())),
      )
    val minf = box("minf", fullBox("smhd", 0, u32(0)) + dinf + stbl)
    val trak =
      box(
        "trak",
        trackHeader(presented) + box("edts", elst) +
          box("mdia", mediaHeader(RATE.toLong(), samples) + hdlr + minf),
      )
    return box("moov", movieHeader(RATE.toLong(), presented) + trak)
  }

  // An AAC-LC sample entry for mono at the track's rate, its decoder config carried in an esds.
  private fun mp4a(): ByteArray {
    val specific = descriptor(5, byteArrayOf(0x11, 0x88.toByte()))
    val config = descriptor(4, byteArrayOf(0x40, 0x15) + u24(0) + u32(0) + u32(0) + specific)
    val es = descriptor(3, u16(1) + byteArrayOf(0) + config + descriptor(6, byteArrayOf(2)))
    val entry = ByteArray(6) + u16(1) + ByteArray(8) + u16(1) + u16(16) + u16(0) + u16(0) + u32(RATE.toLong() shl 16)
    return box("mp4a", entry + fullBox("esds", 0, es))
  }

  private fun descriptor(
    tag: Int,
    body: ByteArray,
  ): ByteArray = byteArrayOf(tag.toByte(), body.size.toByte()) + body

  // One frame of silence as ffmpeg's AAC-LC encoder writes it for mono, repeated for every sample.
  private val SILENT_FRAME = byteArrayOf(0x01, 0x18, 0x20, 0x07)
}

/**
 * Builds the boxes of an MP4 or QuickTime file field by field, big endian as the format stores them.
 */
@InternalFilmstripApi
public object MovieBoxes {
  /**
   * A `mvhd` of [version] counting [duration] ticks of [timescale].
   */
  public fun movieHeader(
    timescale: Long,
    duration: Long,
    version: Int = 0,
  ): ByteArray {
    val rest = u32(0x00010000) + u16(0x0100) + ByteArray(10) + MATRIX + ByteArray(24) + u32(2)
    return fullBox("mvhd", version, times(timescale, duration, version) + rest)
  }

  /**
   * A `mdhd` of [version] counting [duration] ticks of [timescale].
   */
  public fun mediaHeader(
    timescale: Long,
    duration: Long,
    version: Int = 0,
  ): ByteArray = fullBox("mdhd", version, times(timescale, duration, version) + u16(UNDETERMINED) + u16(0))

  /**
   * A `tkhd` of [version] for a track enabled in its movie, counting [duration] ticks of the movie's timescale.
   */
  public fun trackHeader(
    duration: Long,
    version: Int = 0,
  ): ByteArray {
    val times =
      if (version == 1) {
        u64(0) + u64(0) + u32(1) + u32(0) + u64(duration)
      } else {
        u32(0) + u32(0) + u32(1) + u32(0) + u32(duration)
      }
    val rest = ByteArray(8) + u16(0) + u16(0) + u16(0x0100) + u16(0) + MATRIX + u32(0) + u32(0)
    return fullBox("tkhd", version, times + rest, flags = TRACK_ENABLED_IN_MOVIE)
  }

  /**
   * A `trak` holding nothing but a `tkhd` of [version] counting [duration] ticks of the movie's timescale.
   */
  public fun bareTrack(
    duration: Long,
    version: Int = 0,
  ): ByteArray = box("trak", trackHeader(duration, version))

  /**
   * A box of [type] around [payload] with a 32-bit size.
   */
  public fun box(
    type: String,
    payload: ByteArray,
  ): ByteArray = u32(8L + payload.size) + ascii(type) + payload

  /**
   * A full box of [type], [version] and [flags] around [payload].
   */
  public fun fullBox(
    type: String,
    version: Int,
    payload: ByteArray,
    flags: Int = 0,
  ): ByteArray = box(type, byteArrayOf(version.toByte()) + u24(flags) + payload)

  /**
   * The low 32 bits of [value].
   */
  public fun u32(value: Long): ByteArray = ByteArray(4) { (value shr (24 - it * 8)).toByte() }

  /**
   * All 64 bits of [value].
   */
  public fun u64(value: Long): ByteArray = ByteArray(8) { (value shr (56 - it * 8)).toByte() }

  /**
   * The bytes of [text], one per character.
   */
  public fun ascii(text: String): ByteArray = ByteArray(text.length) { text[it].code.toByte() }

  private fun times(
    timescale: Long,
    duration: Long,
    version: Int,
  ): ByteArray =
    if (version == 1) {
      u64(0) + u64(0) + u32(timescale) + u64(duration)
    } else {
      u32(0) + u32(0) + u32(timescale) + u32(duration)
    }
}

private fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

private fun u24(value: Int): ByteArray = byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

private val MATRIX =
  u32(0x00010000) + u32(0) + u32(0) + u32(0) + u32(0x00010000) + u32(0) + u32(0) + u32(0) + u32(0x40000000)

private const val TRACK_ENABLED_IN_MOVIE = 3

// The packed ISO 639 code for an undetermined language.
private const val UNDETERMINED = 0x55C4
