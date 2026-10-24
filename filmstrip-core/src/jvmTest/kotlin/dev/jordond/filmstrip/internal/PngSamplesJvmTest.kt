package dev.jordond.filmstrip.internal

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random
import kotlin.test.Test

/**
 * Holds [pngSamples] to what `javax.imageio` reads from the same PNG, sample for sample.
 *
 * One matrix is written by the test PNG writer, which covers every colour type, depth, Adam7 and each filter strategy
 * with real deflate streams. The other is written by ImageIO itself, plain and progressive, with its own filter choice.
 */
class PngSamplesJvmTest {
  @Test
  fun `every format layout and filter reads as ImageIO reads it`() {
    val random = Random(3)
    val sizes = listOf(1 to 1, 3 to 3, 13 to 11, 33 to 9)
    for ((colourType, depth) in PNG_FORMATS) {
      for (interlaced in listOf(false, true)) {
        for ((index, size) in sizes.withIndex()) {
          val (width, height) = size
          val samples = randomSamples(random, width, height, colourType, depth)
          val entries = minOf(256, 1 shl depth)
          val palette = if (colourType == 3) ByteArray(entries * 3) { random.nextInt(256).toByte() } else null
          val transparency = if (index % 2 == 1) keyFor(colourType, samples, entries, random) else null
          for (strategy in FILTER_STRATEGIES) {
            val png =
              encodePng(width, height, colourType, depth, samples, interlaced, strategy, palette, transparency) {
                deflate(it)
              }
            val ours = pngSamples(png).shouldNotBeNull().rgba.unsigned()
            // ImageIO compares a grey key under 8 bits with the sample after scaling it to 8 bits, so it misses the key
            // the PNG spec matches on the stored sample. PngSamplesTest pins that alpha instead.
            if (colourType == 0 && depth < 8 && transparency != null) {
              ours.withoutAlpha() shouldBe imageIoSamples(png).withoutAlpha()
            } else {
              ours shouldBe imageIoSamples(png)
            }
          }
        }
      }
    }
  }

  @Test
  fun `pictures ImageIO writes read as ImageIO reads them`() {
    for (image in imageIoPictures(Random(5))) {
      for (progressive in listOf(false, true)) {
        val png = write(image, progressive)
        png[28].toInt() shouldBe if (progressive) 1 else 0
        val decoded = pngSamples(png).shouldNotBeNull()
        decoded.rgba.unsigned() shouldBe imageIoSamples(png)
        decoded.sixteenBit shouldBe (image.colorModel.getComponentSize(0) == 16)
      }
    }
  }

  // The key colour of a grey or RGB picture is its first pixel, and a palette makes its first half translucent.
  private fun keyFor(
    colourType: Int,
    samples: IntArray,
    entries: Int,
    random: Random,
  ): ByteArray? =
    when (colourType) {
      0 -> bytes { u16(samples[0]) }
      2 -> bytes { (0 until 3).forEach { u16(samples[it]) } }
      3 -> ByteArray(maxOf(1, entries / 2)) { random.nextInt(256).toByte() }
      else -> null
    }

  private fun imageIoPictures(random: Random): List<BufferedImage> {
    val width = 33
    val height = 17
    val types =
      listOf(
        BufferedImage.TYPE_BYTE_GRAY,
        BufferedImage.TYPE_USHORT_GRAY,
        BufferedImage.TYPE_3BYTE_BGR,
        BufferedImage.TYPE_INT_RGB,
        BufferedImage.TYPE_4BYTE_ABGR,
        BufferedImage.TYPE_INT_ARGB,
        BufferedImage.TYPE_BYTE_BINARY,
      ).map { BufferedImage(width, height, it) }
    val palettes =
      listOf(2, 4, 8).map { bits ->
        val colours = ByteArray(1 shl bits) { random.nextInt(256).toByte() }
        val blues = colours.copyOf().apply { shuffle(random) }
        val model = IndexColorModel(bits, 1 shl bits, colours, colours.reversedArray(), blues)
        val type = if (bits < 8) BufferedImage.TYPE_BYTE_BINARY else BufferedImage.TYPE_BYTE_INDEXED
        BufferedImage(width, height, type, model)
      }
    val alphaPalette =
      ByteArray(16) { random.nextInt(256).toByte() }.let { colours ->
        val alphas = colours.copyOf().apply { shuffle(random) }
        val model = IndexColorModel(8, 16, colours, colours, colours.reversedArray(), alphas)
        BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, model)
      }
    val components =
      listOf(
        component(ColorSpace.CS_sRGB, alpha = false, DataBuffer.TYPE_USHORT),
        component(ColorSpace.CS_sRGB, alpha = true, DataBuffer.TYPE_USHORT),
        component(ColorSpace.CS_GRAY, alpha = true, DataBuffer.TYPE_BYTE),
        component(ColorSpace.CS_GRAY, alpha = true, DataBuffer.TYPE_USHORT),
      ).map { model -> BufferedImage(model, model.createCompatibleWritableRaster(width, height), false, null) }
    return (types + palettes + alphaPalette + components).onEach { it.fill(random) }
  }

  private fun component(
    space: Int,
    alpha: Boolean,
    type: Int,
  ): ComponentColorModel =
    ComponentColorModel(
      ColorSpace.getInstance(space),
      alpha,
      false,
      if (alpha) Transparency.TRANSLUCENT else Transparency.OPAQUE,
      type,
    )

  private fun BufferedImage.fill(random: Random) {
    val raster = raster
    val model = colorModel
    for (y in 0 until height) {
      for (x in 0 until width) {
        for (band in 0 until raster.numBands) {
          val bits = raster.sampleModel.getSampleSize(band)
          val bound = if (model is IndexColorModel) model.mapSize else 1 shl bits
          raster.setSample(x, y, band, random.nextInt(bound))
        }
      }
    }
  }

  private fun write(
    image: BufferedImage,
    progressive: Boolean,
  ): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("png").next()
    val param = writer.defaultWriteParam
    if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
    val out = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(out).use { stream ->
      writer.output = stream
      writer.write(null, IIOImage(image, null, null), param)
    }
    writer.dispose()
    return out.toByteArray()
  }

  // What ImageIO reads, as the same four 16-bit samples per pixel: a palette entry's 8-bit components, or each band
  // scaled from its own bit depth.
  private fun imageIoSamples(png: ByteArray): List<Int> {
    val image = ImageIO.read(ByteArrayInputStream(png)).shouldNotBeNull()
    val raster = image.raster
    val model = image.colorModel
    val samples = ArrayList<Int>(image.width * image.height * 4)
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        if (model is IndexColorModel) {
          val index = raster.getSample(x, y, 0)
          samples +=
            listOf(model.getRed(index), model.getGreen(index), model.getBlue(index), model.getAlpha(index))
              .map { it * 257 }
          continue
        }

        fun band(band: Int) = raster.getSample(x, y, band) * (65535 / ((1 shl model.getComponentSize(band)) - 1))
        val colour = model.numColorComponents == 3
        val red = band(0)
        samples += red
        samples += if (colour) band(1) else red
        samples += if (colour) band(2) else red
        samples += if (model.hasAlpha()) band(model.numColorComponents) else 65535
      }
    }
    return samples
  }

  private fun deflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(data)
    deflater.finish()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
    deflater.end()
    return out.toByteArray()
  }

  private fun List<Int>.withoutAlpha(): List<Int> = filterIndexed { index, _ -> index % 4 != 3 }

  private fun ShortArray.unsigned(): List<Int> = map { it.toInt() and 0xFFFF }
}
