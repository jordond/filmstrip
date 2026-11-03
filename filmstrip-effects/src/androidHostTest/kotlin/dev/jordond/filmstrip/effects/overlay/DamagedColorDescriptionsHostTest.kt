package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.withColorDescriptionsHidden
import dev.jordond.filmstrip.test.ColorPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

/**
 * Damaged and truncated pictures handed to [withColorDescriptionsHidden], which come back as they were or with
 * their descriptions hidden, never as an exception.
 */
class DamagedColorDescriptionsHostTest {
  @Test
  fun aFormatWithNoKnownProfileComesBackAsTheSameArray() {
    val gif = "GIF89a".encodeToByteArray() + ByteArray(PROFILE.size) { it.toByte() }

    gif.withColorDescriptionsHidden() shouldBeSameInstanceAs gif
  }

  @Test
  fun anEmptyOrOneBytePictureComesBackAsItWas() {
    for (picture in listOf(ByteArray(0), byteArrayOf(0xFF.toByte()), byteArrayOf(0xFF.toByte(), 0xD8.toByte()))) {
      picture.withColorDescriptionsHidden() shouldBeSameInstanceAs picture
    }
  }

  @Test
  fun aChunkOrSegmentRunningPastTheFileComesBackAsItWas() {
    val ihdr = PNG_SIGNATURE_SIZE + 12 + 13
    val oversize = png(chunk("iCCP", ICCP_PAYLOAD)).also { be32(Int.MAX_VALUE).copyInto(it, ihdr) }
    val truncatedJpeg = jpeg(jpegProfile(PROFILE)).copyOf(SEGMENT_SIGNATURE_AT + 20)
    val oversizeWebp = webp(riffChunk("ICCP", PROFILE)).also { le32(Int.MAX_VALUE).copyInto(it, ICCP_LENGTH_AT) }
    val emptyHeaderLast = webp(riffChunk("VP8X", ByteArray(0)))
    val pictures = listOf(oversize, truncatedJpeg, oversizeWebp, emptyHeaderLast)

    for (picture in pictures) picture.withColorDescriptionsHidden() shouldBeSameInstanceAs picture
  }

  @Test
  fun anHeifBoxSizedOneWithNoRoomForItsLargeSizeComesBackAsItWas() {
    val cut = heif(box("colr", "prof".encodeToByteArray() + PROFILE)).let { it + be32(1) + "meta".encodeToByteArray() }
    val runaway = heif(be32(1) + "colr".encodeToByteArray() + be32(0x7FFF) + be32(0) + "prof".encodeToByteArray())

    withClue("the profile ahead of the cut box was missed") {
      pictureColor(cut.withColorDescriptionsHidden()).shouldBeNull()
    }
    runaway.withColorDescriptionsHidden() shouldBeSameInstanceAs runaway
  }

  @Test
  fun everyTruncationOfATaggedPictureHidesWithoutThrowing() {
    val tagged =
      listOf(
        png(chunk("iCCP", ICCP_PAYLOAD), chunk("cICP", P3_CICP)),
        jpeg(jpegProfile(PROFILE)),
        webp(riffChunk("VP8X", VP8X_FLAGGED) + riffChunk("ICCP", PROFILE.copyOf(PROFILE.size - 1))),
        ColorPictures.NCLX_DISPLAY_P3_WITH_PROFILE.bytes(),
        ColorPictures.HEIC_NCLX_BT709_FULL.bytes(),
      )

    for (picture in tagged) {
      for (length in 0..picture.size) picture.copyOf(length).withColorDescriptionsHidden()
    }
  }

  @Test
  fun aProfileSegmentEndingAtItsSignatureComesBackAsItWas() {
    val bare = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE2.toByte(), 0, 14) + ICC_SIGNATURE

    bare.withColorDescriptionsHidden() shouldBeSameInstanceAs bare
  }

  @Test
  fun everyLengthFieldOverwrittenHidesWithoutThrowing() {
    for ((picture, fields) in taggedWithLengthFields()) {
      for (field in fields) {
        for (value in lengthsAround(picture.size)) {
          picture.copyOf().also { field.write(it, value) }.withColorDescriptionsHidden()
        }
      }
    }
  }

  private fun taggedWithLengthFields(): List<Pair<ByteArray, List<LengthField>>> {
    val png = png(chunk("iCCP", ICCP_PAYLOAD), chunk("cICP", P3_CICP))
    val half = PROFILE.size / 2
    val rest = PROFILE.copyOfRange(half, PROFILE.size)
    val jpeg = jpeg(jpegProfile(PROFILE.copyOf(half), 1, 2) + jpegProfile(rest, 2, 2))
    val webp = webp(riffChunk("VP8X", VP8X_FLAGGED) + riffChunk("ICCP", PROFILE))
    val heif = ColorPictures.NCLX_DISPLAY_P3_WITH_PROFILE.bytes()
    return listOf(
      png to buildList { png.forEachChunk { at, _ -> add(LengthField(at, 4)) } },
      jpeg to jpegLengthFields(jpeg),
      webp to riffLengthFields(webp),
      heif to boxLengthFields(heif, 0, heif.size),
    )
  }

  private companion object {
    val ICC_SIGNATURE = "ICC_PROFILE".encodeToByteArray() + byteArrayOf(0)
    const val ICCP_LENGTH_AT = 16
  }
}

// A big endian length of [width] bytes, or a little endian one, at [at].
private class LengthField(
  val at: Int,
  val width: Int,
  val little: Boolean = false,
) {
  fun write(
    picture: ByteArray,
    value: Int,
  ) {
    for (index in 0 until width) {
      val shift = 8 * if (little) index else width - 1 - index
      picture[at + index] = (value ushr shift).toByte()
    }
  }
}

// Every small length, every length within reach of the picture's own size and the largest ones.
private fun lengthsAround(size: Int): List<Int> =
  (0..48).toList() + (size - 48..size + 8).toList() + listOf(0xFFFF, Int.MAX_VALUE, -1)

private fun jpegLengthFields(jpeg: ByteArray): List<LengthField> =
  buildList {
    var at = 2
    while (at + 4 <= jpeg.size && jpeg[at + 1] != 0xDA.toByte()) {
      add(LengthField(at + 2, 2))
      at += 2 + ((jpeg[at + 2].toInt() and 0xFF) shl 8 or (jpeg[at + 3].toInt() and 0xFF))
    }
  }

private fun riffLengthFields(webp: ByteArray): List<LengthField> =
  buildList {
    add(LengthField(4, 4, little = true))
    var at = 12
    while (at + 8 <= webp.size) {
      add(LengthField(at + 4, 4, little = true))
      val length = webp.copyOfRange(at + 4, at + 8).reversedArray().int32(0)
      at += 8 + length + (length and 1)
    }
  }

// Every box's size field, walking into meta, iprp and ipco, the boxes the hiding walks.
private fun boxLengthFields(
  file: ByteArray,
  from: Int,
  to: Int,
): List<LengthField> =
  buildList {
    var at = from
    while (at + 8 <= to) {
      val length = file.int32(at)
      if (length < 8 || at + length > to) break
      add(LengthField(at, 4))
      when (file.typeAt(at)) {
        "meta" -> addAll(boxLengthFields(file, at + 12, at + length))
        "iprp", "ipco" -> addAll(boxLengthFields(file, at + 8, at + length))
      }
      at += length
    }
  }
