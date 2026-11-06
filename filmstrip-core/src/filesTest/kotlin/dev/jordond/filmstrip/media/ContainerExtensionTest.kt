package dev.jordond.filmstrip.media

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The one place a buffer's container becomes a file extension.
 */
class ContainerExtensionTest {
  private fun ftyp(
    major: String,
    vararg compatible: String,
    declaredSize: Int? = null,
  ): ByteArray {
    val size = 16 + compatible.size * 4
    val length = declaredSize ?: size
    val header =
      byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
      )
    val minor = ByteArray(4)
    return header + "ftyp".encodeToByteArray() + major.encodeToByteArray() + minor +
      compatible.joinToString("").encodeToByteArray()
  }

  private fun riff(form: String): ByteArray =
    "RIFF".encodeToByteArray() + ByteArray(4) + form.encodeToByteArray() + ByteArray(4)

  private fun form(form: String): ByteArray =
    "FORM".encodeToByteArray() + ByteArray(4) + form.encodeToByteArray() + ByteArray(4)

  private fun unsigned(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

  @Test
  fun `each hint names its own container`() {
    val bytes = ftyp("qt  ")

    containerExtensionOf(bytes, FormatHint.Mp4) shouldBe "mp4"
    containerExtensionOf(bytes, FormatHint.Mov) shouldBe "mov"
    containerExtensionOf(bytes, FormatHint.M4a) shouldBe "m4a"
    containerExtensionOf(bytes, FormatHint.ThreeGp) shouldBe "3gp"
  }

  @Test
  fun `each major brand names its container`() {
    containerExtensionOf(ftyp("qt  ")) shouldBe "mov"
    containerExtensionOf(ftyp("M4A ")) shouldBe "m4a"
    containerExtensionOf(ftyp("3gp4")) shouldBe "3gp"
    containerExtensionOf(ftyp("3gp6")) shouldBe "3gp"
    containerExtensionOf(ftyp("isom")) shouldBe "mp4"
    containerExtensionOf(ftyp("mp42")) shouldBe "mp4"
  }

  @Test
  fun `a brand in the middle of the compatible list decides`() {
    containerExtensionOf(ftyp("isom", "iso2", "M4A ", "mp41")) shouldBe "m4a"
    containerExtensionOf(ftyp("mp42", "isom", "3gp5", "avc1")) shouldBe "3gp"
    containerExtensionOf(ftyp("isom", "avc1", "qt  ", "mp41")) shouldBe "mov"
  }

  @Test
  fun `the major brand outranks the compatible list`() {
    containerExtensionOf(ftyp("qt  ", "M4A ", "3gp4")) shouldBe "mov"
  }

  @Test
  fun `the minor version is not read as a brand`() {
    val bytes = ftyp("isom")
    "qt  ".encodeToByteArray().copyInto(bytes, destinationOffset = 12)

    containerExtensionOf(bytes) shouldBe "mp4"
  }

  @Test
  fun `no ftyp box is the neutral name`() {
    containerExtensionOf(ByteArray(0)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(ByteArray(64)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf("0000moov00000000".encodeToByteArray()) shouldBe NEUTRAL_CONTAINER_EXTENSION
  }

  @Test
  fun `each container with its own magic gets its own name`() {
    containerExtensionOf(riff("WAVE")) shouldBe "wav"
    containerExtensionOf(riff("AVI ")) shouldBe "avi"
    containerExtensionOf(form("AIFF")) shouldBe "aiff"
    containerExtensionOf(form("AIFC")) shouldBe "aiff"
    containerExtensionOf("caff".encodeToByteArray() + ByteArray(8)) shouldBe "caf"
    containerExtensionOf("fLaC".encodeToByteArray() + ByteArray(8)) shouldBe "flac"
    containerExtensionOf("OggS".encodeToByteArray() + ByteArray(8)) shouldBe "ogg"
    containerExtensionOf("ID3".encodeToByteArray() + ByteArray(8)) shouldBe "mp3"
  }

  @Test
  fun `an audio sync word tells ADTS from MPEG audio by its layer`() {
    containerExtensionOf(unsigned(0xFF, 0xF1, 0x50, 0x40)) shouldBe "aac"
    containerExtensionOf(unsigned(0xFF, 0xF9, 0x4C, 0x40)) shouldBe "aac"
    containerExtensionOf(unsigned(0xFF, 0xFB, 0x40, 0xC0)) shouldBe "mp3"
    containerExtensionOf(unsigned(0xFF, 0xF3, 0x60, 0xC4)) shouldBe "mp3"
  }

  @Test
  fun `a sync word with reserved fields is the neutral name`() {
    // ADTS with a reserved sample rate index.
    containerExtensionOf(unsigned(0xFF, 0xF1, 0x34, 0x40)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    // MPEG audio with a reserved version, a bad bitrate and a reserved sample rate in turn.
    containerExtensionOf(unsigned(0xFF, 0xEB, 0x40, 0xC0)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(unsigned(0xFF, 0xFB, 0xF0, 0xC0)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(unsigned(0xFF, 0xFB, 0x4C, 0xC0)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    // A JPEG opens on FF too.
    containerExtensionOf(unsigned(0xFF, 0xD8, 0xFF, 0xE0)) shouldBe NEUTRAL_CONTAINER_EXTENSION
  }

  private fun transportStream(): ByteArray =
    ByteArray(376).also {
      it[0] = 0x47
      it[188] = 0x47
    }

  @Test
  fun `a transport stream takes a sync byte on two packets running`() {
    val stream = transportStream()

    containerExtensionOf(stream) shouldBe "ts"
    containerExtensionOf(stream.copyOf(188)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(stream.copyOf().also { it[188] = 0 }) shouldBe NEUTRAL_CONTAINER_EXTENSION
  }

  @Test
  fun `an m2ts stream takes a sync byte on two packets behind their timestamps`() {
    val stream =
      ByteArray(384).also {
        it[4] = 0x47
        it[196] = 0x47
      }

    containerExtensionOf(stream) shouldBe containerExtensionOf(transportStream())
    containerExtensionOf(stream.copyOf(196)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(stream.copyOf().also { it[196] = 0 }) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(stream.copyOf().also { it[4] = 0 }) shouldBe NEUTRAL_CONTAINER_EXTENSION
  }

  @Test
  fun `sync bytes that do not sit one packet apart are not a transport stream`() {
    val apart = { first: Int, second: Int ->
      ByteArray(384).also {
        it[first] = 0x47
        it[second] = 0x47
      }
    }

    containerExtensionOf(apart(0, 192)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(apart(4, 188)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(apart(4, 200)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    // A QuickTime atom named with a G sits on the same first offset.
    containerExtensionOf(ByteArray(384).also { "Gskp".encodeToByteArray().copyInto(it, 4) }) shouldBe
      NEUTRAL_CONTAINER_EXTENSION
  }

  @Test
  fun `a magic cut short or with another form type is the neutral name`() {
    containerExtensionOf(riff("WAVE").copyOf(11)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(riff("WEBP")) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(form("ILBM")) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf("cafe".encodeToByteArray()) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(unsigned(0xFF)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(unsigned(0xFF, 0xF1)) shouldBe NEUTRAL_CONTAINER_EXTENSION
  }

  @Test
  fun `a hint outranks a magic`() {
    containerExtensionOf(riff("WAVE"), FormatHint.Mov) shouldBe "mov"
  }

  @Test
  fun `an ftyp too short to hold a brand is the neutral name`() {
    containerExtensionOf(ftyp("qt  ").copyOf(11)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(ftyp("qt  ", declaredSize = 8)) shouldBe NEUTRAL_CONTAINER_EXTENSION
    containerExtensionOf(ftyp("qt  ", declaredSize = 1)) shouldBe NEUTRAL_CONTAINER_EXTENSION
  }

  @Test
  fun `a declared length never reads past the buffer`() {
    containerExtensionOf(ftyp("isom", "M4A ", declaredSize = Int.MAX_VALUE)) shouldBe "m4a"
    containerExtensionOf(ftyp("isom", "avc1", declaredSize = -1)) shouldBe "mp4"
  }

  @Test
  fun `a declared length of zero runs to the end of the buffer`() {
    containerExtensionOf(ftyp("isom", "mp41", "qt  ", declaredSize = 0)) shouldBe "mov"
  }

  @Test
  fun `a declared length hides what lies past it`() {
    containerExtensionOf(ftyp("isom", "mp41", "qt  ", declaredSize = 20)) shouldBe "mp4"
  }

  @Test
  fun `brands past the cap are not read`() {
    val filler = Array(MAX_FTYP_BRANDS - 2) { "avc1" }

    containerExtensionOf(ftyp("isom", *filler, "qt  ")) shouldBe "mov"
    containerExtensionOf(ftyp("isom", *filler, "avc1", "qt  ")) shouldBe "mp4"
  }
}
