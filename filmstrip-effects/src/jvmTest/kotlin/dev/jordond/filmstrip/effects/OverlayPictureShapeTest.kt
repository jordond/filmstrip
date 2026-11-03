package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureColor
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.awt.Transparency
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.awt.image.Raster
import kotlin.test.Test
import java.awt.color.ColorSpace as AwtColorSpace

/**
 * The picture shapes the JDK read takes apart into stored samples: JPEG, palette, grey and sixteen-bit pictures.
 */
class OverlayPictureShapeTest {
  // The JDK tags a grey PNG as linear light, so a converted read lifts every mid grey. ffmpeg draws the stored samples,
  // and so does the grade, which puts a grey texel where the same grey stored as RGB lands.
  @Test
  fun `a grey PNG on an HDR grade lands where the same grey stored as RGB does`() {
    val levels = intArrayOf(0, 64, 128, 192, 230, 255)
    val grey = BufferedImage(levels.size, 1, BufferedImage.TYPE_BYTE_GRAY)
    grey.raster.setPixels(0, 0, levels.size, 1, levels)
    val rgb = BufferedImage(levels.size, 1, BufferedImage.TYPE_INT_ARGB)
    levels.forEachIndexed { x, level -> rgb.setRGB(x, 0, OPAQUE or (level shl 16) or (level shl 8) or level) }

    for (transfer in HdrTransfer.entries) {
      val fromGrey = mappedRaster(pngOf(grey), transfer)
      val fromRgb = mappedRaster(pngOf(rgb), transfer)
      levels.indices.forEach { x ->
        withClue("$transfer grey ${levels[x]}") {
          fromGrey.getPixel(x, 0, IntArray(4)) shouldBe fromRgb.getPixel(x, 0, IntArray(4))
        }
      }
    }
  }

  @Test
  fun `a tagged grey PNG on an HDR grade lands where its profile's light puts it`() {
    val levels = intArrayOf(0, 64, 128, 192, 255)
    val grey = BufferedImage(levels.size, 1, BufferedImage.TYPE_BYTE_GRAY)
    grey.raster.setPixels(0, 0, levels.size, 1, levels)
    val png = tagged(pngOf(grey), LINEAR_GREY)
    for (transfer in HdrTransfer.entries) {
      val raster = mappedRaster(png, transfer)
      levels.forEachIndexed { x, level ->
        val texel = OPAQUE or (level shl 16) or (level shl 8) or level
        withClue("$transfer $x") {
          raster.getPixel(x, 0, IntArray(4)) shouldBe signalOf(LINEAR_GREY_COLOR, texel, transfer)
        }
      }
    }
  }

  // The JDK's JPEG reader converts through an APP2 profile on its own, so a read through it would convert twice.
  @Test
  fun `a tagged JPEG is read as the samples it stores rather than the JDK's conversion`() {
    val jpeg = jpegOf(blocksOf(TEXELS))
    val stored = argbOf(jpeg)
    val tagged = taggedJpeg(jpeg, LINEAR)
    val color = withClue("the JPEG does not carry the profile") { pictureColor(tagged).shouldNotBeNull() }
    val converted = argbOf(tagged)
    withClue("the JDK reads the tagged JPEG unconverted, so this cannot tell a stored read from a converted one") {
      stored.indices.any { channelGap(stored[it], converted[it]) > MID_TONE_MOVE } shouldBe true
    }

    val drawn = auxImage(overlayOf(tagged), null).shouldBeInstanceOf<ImageSource.Bytes>()
    argbOf(drawn.bytes) shouldBe pictureSrgbCodes(color, stored)
    val middle = BLOCK / 2
    for (transfer in HdrTransfer.entries) {
      val raster = mappedRaster(tagged, transfer)
      blockCentres(TEXELS.size).forEach { x ->
        val texel = stored[middle * TEXELS.size * BLOCK + x]
        withClue("$transfer $x") { raster.getPixel(x, middle, IntArray(4)) shouldBe signalOf(color, texel, transfer) }
      }
    }
  }

  @Test
  fun `a JPEG tagged with a grey profile is drawn the way an untagged one is`() {
    val jpeg = jpegOf(blocksOf(TEXELS))
    val tagged = taggedJpeg(jpeg, LINEAR_GREY)

    val spec = overlayOf(tagged)
    auxImage(spec, null) shouldBeSameInstanceAs spec.image
    for (transfer in HdrTransfer.entries) {
      val untagged = mappedRaster(jpeg, transfer)
      val graded = mappedRaster(tagged, transfer)
      blockCentres(TEXELS.size).forEach { x ->
        withClue("$transfer $x") { graded.getPixel(x, 0, IntArray(4)) shouldBe untagged.getPixel(x, 0, IntArray(4)) }
      }
    }
  }

  @Test
  fun `a tagged palette PNG with transparency is read as the entries it stores`() {
    val reds = intArrayOf(0xFF, 0x80, 0x40, 0x20)
    val greens = intArrayOf(0x80, 0x40, 0xC0, 0x20)
    val blues = intArrayOf(0x20, 0xFF, 0x80, 0x20)
    val alphas = intArrayOf(0xFF, 0x80, 0x40, 0x00)
    val model = IndexColorModel(8, reds.size, bytesOf(reds), bytesOf(greens), bytesOf(blues), bytesOf(alphas))
    val image = BufferedImage(reds.size, 1, BufferedImage.TYPE_BYTE_INDEXED, model)
    image.raster.setPixels(0, 0, reds.size, 1, IntArray(reds.size) { it })
    val png = tagged(pngOf(image), LINEAR)
    withClue("the JDK wrote no palette PNG") { png[COLOR_TYPE].toInt() shouldBe PALETTE }
    withClue("the palette PNG carries no transparency") { ("tRNS" in png.decodeToString()) shouldBe true }

    val texels = IntArray(reds.size) { (alphas[it] shl 24) or (reds[it] shl 16) or (greens[it] shl 8) or blues[it] }
    assertDrawnFrom(png, texels)
  }

  @Test
  fun `a tagged grey and alpha PNG is read as the samples it stores`() {
    val samples = intArrayOf(0, 0xFF, 0x40, 0x80, 0x80, 0xC0, 0xFF, 0x20)
    val raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, samples.size / 2, 1, 2, null)
    raster.setPixels(0, 0, samples.size / 2, 1, samples)
    val image = BufferedImage(model(AwtColorSpace.CS_GRAY, DataBuffer.TYPE_BYTE), raster, false, null)
    val png = tagged(pngOf(image), LINEAR_GREY)
    withClue("the JDK wrote no grey and alpha PNG") { png[COLOR_TYPE].toInt() shouldBe GREY_ALPHA }

    val texels =
      IntArray(samples.size / 2) {
        val level = samples[it * 2]
        (samples[it * 2 + 1] shl 24) or (level shl 16) or (level shl 8) or level
      }
    assertDrawnFrom(png, texels)
  }

  // Every sample is a whole number of eight-bit steps, so the eight-bit form lands where the sixteen-bit one does.
  @Test
  fun `a tagged sixteen bit PNG of whole eight bit steps lands where those codes do`() {
    val codes = intArrayOf(0xFF, 0x80, 0x40, 0xFF, 0x20, 0xC0, 0x60, 0x80)
    val png = tagged(sixteenBitPng(IntArray(codes.size) { codes[it] * SIXTEEN_BIT_MAX / 0xFF }), LINEAR)
    withClue("the JDK wrote no sixteen bit PNG") { png[BIT_DEPTH].toInt() shouldBe SIXTEEN_BITS }

    val texels =
      IntArray(codes.size / 4) {
        val at = it * 4
        (codes[at + 3] shl 24) or (codes[at] shl 16) or (codes[at + 1] shl 8) or codes[at + 2]
      }
    assertDrawnFrom(png, texels)
  }

  // Samples that sit between eight-bit steps tell a sixteen-bit read from one cut to eight bits on the way.
  @Test
  fun `a tagged sixteen bit PNG lands on an HDR grade at its full depth`() {
    val samples = intArrayOf(0x8040, 0x4020, 0xC0A0, 0xFFFF, 0x1234, 0x9876, 0x5555, 0xFFFF)
    val png = tagged(sixteenBitPng(samples), LINEAR)
    val color = pictureColor(png)
    val wide = ShortArray(samples.size) { samples[it].toShort() }
    val cut = IntArray(samples.size / 4) { texel -> argbCut(samples, texel) }

    for (transfer in HdrTransfer.entries) {
      val raster = mappedRaster(png, transfer)
      val light = pictureLight(color, wide)
      val eightBit = pictureLight(color, cut)
      for (x in 0 until samples.size / 4) {
        val expected = wideSignal(light.copyOfRange(x * 4, x * 4 + 4), transfer)
        val truncated = wideSignal(eightBit.copyOfRange(x * 4, x * 4 + 4), transfer)
        withClue("$transfer $x") { raster.getPixel(x, 0, IntArray(4)) shouldBe expected }
        withClue("$transfer $x lands the same cut to eight bits") { !expected.contentEquals(truncated) shouldBe true }
      }
    }
  }

  private fun assertDrawnFrom(
    png: ByteArray,
    texels: IntArray,
  ) {
    val color: PictureColor = withClue("the PNG does not describe its color") { pictureColor(png).shouldNotBeNull() }
    val drawn = auxImage(overlayOf(png), null).shouldBeInstanceOf<ImageSource.Bytes>()
    val expected = pictureSrgbCodes(color, texels)
    argbOf(drawn.bytes) shouldBe expected
    withClue("the profile moves no texel, so this cannot tell a stored read from any other") {
      texels.indices.any { channelGap(texels[it], expected[it]) > MID_TONE_MOVE } shouldBe true
    }
  }

  // A sixteen-bit RGBA PNG holding [samples], four to a texel in one row.
  private fun sixteenBitPng(samples: IntArray): ByteArray {
    val raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, samples.size / 4, 1, 4, null)
    raster.setPixels(0, 0, samples.size / 4, 1, samples)
    return pngOf(BufferedImage(model(AwtColorSpace.CS_sRGB, DataBuffer.TYPE_USHORT), raster, false, null))
  }

  private fun model(
    space: Int,
    type: Int,
  ): ComponentColorModel =
    ComponentColorModel(AwtColorSpace.getInstance(space), true, false, Transparency.TRANSLUCENT, type)

  private fun argbCut(
    samples: IntArray,
    texel: Int,
  ): Int {
    fun code(channel: Int): Int = samples[texel * 4 + channel] ushr 8
    return (code(3) shl 24) or (code(0) shl 16) or (code(1) shl 8) or code(2)
  }

  private fun bytesOf(values: IntArray): ByteArray = ByteArray(values.size) { values[it].toByte() }

  private companion object {
    // Where a PNG's IHDR keeps its bit depth and color type, and the color types and depth written here.
    const val BIT_DEPTH = 24
    const val COLOR_TYPE = 25
    const val PALETTE = 3
    const val GREY_ALPHA = 4
    const val SIXTEEN_BITS = 16
  }
}
