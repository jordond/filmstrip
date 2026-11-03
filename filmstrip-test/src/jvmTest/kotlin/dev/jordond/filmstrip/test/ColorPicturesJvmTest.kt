package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import kotlin.test.Test

/**
 * The PNGs described by color chunks read by the JDK, as an independent check that their bytes mean what they were
 * built to.
 *
 * ImageIO decodes every PNG back to its texel, and its PNG metadata reports each `gAMA`, `cHRM`, `sRGB` and `iCCP`
 * chunk with the fields it was written with. ImageIO does not know `cICP`, so it lists that chunk among the unknown
 * ones, and its bytes are read back from there. Every chunk checksum is worked again by [CRC32].
 */
class ColorPicturesJvmTest {
  @Test
  fun imageIoDecodesEveryEightBitPngToItsTexel() {
    for (picture in ColorPictures.PNGS) {
      val (raster, _) = read(picture.pngOf(SIZE))
      val texel = AuthoredColors.channelsOf(picture.texel) + (picture.texel ushr 24)

      withClue("$picture bit depth") { raster.sampleModel.getSampleSize(0) shouldBe 8 }
      withClue("$picture") {
        raster.getPixel(SIZE.width - 1, SIZE.height - 1, null as IntArray?).toList() shouldBe texel
      }
    }
  }

  @Test
  fun imageIoDecodesEveryHdrPngToItsSixteenBitTexel() {
    for (picture in ColorPictures.HDR_PNGS) {
      val (raster, _) = read(picture.pngOf(SIZE))

      withClue("$picture bit depth") { raster.sampleModel.getSampleSize(0) shouldBe 16 }
      raster.getPixel(SIZE.width - 1, SIZE.height - 1, null as IntArray?).toList() shouldBe picture.texel
    }
  }

  @Test
  fun imageIoReadsEveryColorChunkAsWritten() {
    for (picture in ColorPictures.PNGS) {
      val (_, tree) = read(picture.pngOf(SIZE))
      val color = picture.color

      withClue("$picture gAMA") { tree.child("gAMA")?.getAttribute("value")?.toInt() shouldBe color.gamma }
      tree.child("cHRM")?.let { node -> CHRM.map { node.int(it) } } shouldBe color.chromaticities?.values
      tree.child("sRGB")?.getAttribute("renderingIntent") shouldBe color.srgbIntent?.let { INTENTS[it] }
      val iccp = tree.child("iCCP")
      if (picture.profile == null) {
        withClue("$picture iCCP") { iccp.shouldBeNull() }
      } else {
        withClue("$picture profile") { inflate(iccp!!.userObject as ByteArray) shouldBe picture.profile }
      }
    }
  }

  @Test
  fun imageIoListsEveryCicpChunkWithItsCodePoints() {
    val pictures =
      ColorPictures.PNGS.mapNotNull { picture ->
        picture.color.cicp?.let { Triple("$picture", it, picture.pngOf(SIZE)) }
      } +
        ColorPictures.HDR_PNGS.map { Triple("$it", it.cicp, it.pngOf(SIZE)) }

    for ((name, cicp, png) in pictures) {
      val unknown =
        read(png).second.getElementsByTagName("UnknownChunk").let { nodes ->
          (0 until nodes.length).map { nodes.item(it) as IIOMetadataNode }
        }
      val chunk = unknown.single { it.getAttribute("type") == "cICP" }
      val expected = listOf(cicp.primaries, cicp.transfer, cicp.matrix, if (cicp.fullRange) 1 else 0)

      withClue(name) { (chunk.userObject as ByteArray).map { it.toInt() and 0xFF } shouldBe expected }
    }
  }

  @Test
  fun everyChunkChecksumMatchesTheJdks() {
    val pngs = ColorPictures.PNGS.map { it.pngOf(LARGE) } + ColorPictures.HDR_PNGS.map { it.pngOf(LARGE) }

    for ((index, png) in pngs.withIndex()) {
      for (chunk in pngChunks(png)) {
        val crc = CRC32().apply { update(chunk.type.encodeToByteArray() + chunk.data) }.value.toInt()

        withClue("$index ${chunk.type}") { chunk.crc shouldBe crc }
      }
    }
  }

  private fun read(png: ByteArray): Pair<Raster, IIOMetadataNode> {
    val reader = ImageIO.getImageReadersByFormatName("png").next()
    reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(png))
    val raster = reader.read(0).raster
    return raster to reader.getImageMetadata(0).getAsTree("javax_imageio_png_1.0") as IIOMetadataNode
  }

  private fun IIOMetadataNode.child(name: String): IIOMetadataNode? =
    getElementsByTagName(name).item(0) as IIOMetadataNode?

  private fun IIOMetadataNode.int(attribute: String): Int = getAttribute(attribute).toInt()

  private fun inflate(zlib: ByteArray): ByteArray {
    val inflater = Inflater().apply { setInput(zlib) }
    val out = ByteArray(PROFILE_LIMIT)
    val length = inflater.inflate(out)
    inflater.end()
    return out.copyOf(length)
  }

  private companion object {
    val SIZE = Size(5, 3)

    // Wider than one stored deflate block holds, so the rows take two blocks.
    val LARGE = Size(200, 100)

    const val PROFILE_LIMIT = 1 shl 16
    val CHRM = listOf("whitePointX", "whitePointY", "redX", "redY", "greenX", "greenY", "blueX", "blueY")
    val INTENTS = listOf("Perceptual", "Relative colorimetric", "Saturation", "Absolute colorimetric")
  }
}
