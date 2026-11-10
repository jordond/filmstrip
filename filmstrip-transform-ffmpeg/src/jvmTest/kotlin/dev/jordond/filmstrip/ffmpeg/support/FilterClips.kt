package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * A lossless clip of a [width] by [height] picture, each pixel drawn by [picture] from its centre's fraction of the
 * width and of the height, stored as [clipOf] stores it.
 */
internal fun FilterFrames.clipOfPicture(
  width: Int,
  height: Int,
  picture: (x: Double, y: Double) -> DoubleArray,
  effects: List<EffectSpec> = emptyList(),
): Clip {
  val still = File.createTempFile("filter-picture", ".png").apply { deleteOnExit() }
  ImageIO.write(imageOf(width, height, picture), "png", still)
  val video = File.createTempFile("filter-picture", ".mkv").apply { deleteOnExit() }
  val encode =
    ProcessBuilder(
      hostFfmpeg,
      "-y",
      "-hide_banner",
      "-loglevel",
      "error",
      "-loop",
      "1",
      "-i",
      still.path,
      "-t",
      "2",
      "-r",
      "$FILTER_RATE",
      "-vf",
      "scale=out_color_matrix=bt709:out_range=tv",
      "-colorspace",
      "bt709",
      "-color_range",
      "tv",
      "-c:v",
      "ffv1",
      "-pix_fmt",
      PLANES_FORMAT,
      video.path,
    ).inheritIO().start()
  encode.waitFor() shouldBe 0
  return Clip(MediaSource.of(video.path), effects = effects)
}

/**
 * A lossless PQ clip of a [width] by [height] grey picture, each column holding the ten-bit luma code [luma] gives
 * it, with Cb and Cr at their middle, stored and tagged as BT.2020.
 *
 * With [alpha] the clip carries an alpha plane too, each row holding the ten-bit code [alpha] gives it.
 */
internal fun FilterFrames.pqClipOfLuma(
  width: Int,
  height: Int,
  luma: (Int) -> Int,
  effects: List<EffectSpec> = emptyList(),
  alpha: ((Int) -> Int)? = null,
): Clip {
  val lumaEnd = width * height
  val chromaEnd = lumaEnd * PLANES_BYTES_PER_TWO_PIXELS / 2
  val samples =
    IntArray(if (alpha == null) chromaEnd else chromaEnd + lumaEnd) {
      when {
        it < lumaEnd -> luma(it % width)
        it < chromaEnd -> MID
        else -> checkNotNull(alpha)((it - chromaEnd) / width)
      }
    }
  val format = if (alpha == null) TEN_BIT_FORMAT else TEN_BIT_ALPHA_FORMAT
  val raw = ByteArray(samples.size * TEN_BIT_BYTES)
  samples.forEachIndexed { index, sample ->
    raw[2 * index] = (sample and BYTE).toByte()
    raw[2 * index + 1] = (sample shr 8).toByte()
  }
  val frame = File.createTempFile("filter-pq", ".yuv").apply { deleteOnExit() }
  frame.writeBytes(raw)
  val video = File.createTempFile("filter-pq", ".mkv").apply { deleteOnExit() }
  val tags =
    arrayOf(
      "-color_primaries",
      "bt2020",
      "-color_trc",
      "smpte2084",
      "-colorspace",
      "bt2020nc",
      "-color_range",
      "tv",
    )
  val encode =
    ProcessBuilder(
      hostFfmpeg,
      "-y",
      "-hide_banner",
      "-loglevel",
      "error",
      "-f",
      "rawvideo",
      "-pix_fmt",
      format,
      "-s",
      "${width}x$height",
      "-r",
      "$FILTER_RATE",
      *tags,
      "-stream_loop",
      "-1",
      "-i",
      frame.path,
      "-t",
      "2",
      *tags,
      "-c:v",
      "ffv1",
      "-pix_fmt",
      format,
      video.path,
    ).inheritIO().start()
  encode.waitFor() shouldBe 0
  return Clip(MediaSource.of(video.path), effects = effects)
}

// The texels a picture draws across a frame [width] pixels wide, the way [imageOf] samples it, for a signal to read.
internal fun ((Double) -> DoubleArray).texelsOver(width: Int): (Int) -> DoubleArray =
  { column ->
    this((column + 0.5) / width)
  }

// The picture [picture] draws from each pixel centre's fraction of the width and of the height, as 8 bit RGB.
internal fun imageOf(
  width: Int,
  height: Int,
  picture: (x: Double, y: Double) -> DoubleArray,
): BufferedImage {
  val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
  for (y in 0 until height) {
    for (x in 0 until width) {
      val rgb = picture((x + 0.5) / width, (y + 0.5) / height).map { (it * FULL).roundToInt() }
      image.setRGB(x, y, (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2])
    }
  }
  return image
}

/**
 * A lossless clip [width] by [height] pixels as stored, each pixel [sampleAspect] times as wide as it is tall, with
 * its luma drawn by the geq expression [luma] and Cb and Cr at their middle.
 *
 * [luma] reads `X`, `Y`, `W` and `H` as geq does, in stored pixels, so an expression of `X/W` and `Y/H` draws the same
 * picture at any stored width.
 */
internal fun FilterFrames.lumaClipOf(
  width: Int,
  height: Int,
  luma: String,
  sampleAspect: Int = 1,
  effects: List<EffectSpec> = emptyList(),
): Clip {
  val video = File.createTempFile("filter-luma", ".mkv").apply { deleteOnExit() }
  val encode =
    ProcessBuilder(
      hostFfmpeg,
      "-y",
      "-hide_banner",
      "-loglevel",
      "error",
      "-f",
      "lavfi",
      "-i",
      "color=c=black:s=${width}x$height:r=$FILTER_RATE:d=2,format=$PLANES_FORMAT," +
        "geq=lum='$luma':cb=128:cr=128,setsar=$sampleAspect",
      "-c:v",
      "ffv1",
      "-pix_fmt",
      PLANES_FORMAT,
      video.path,
    ).inheritIO().start()
  encode.waitFor() shouldBe 0
  return Clip(MediaSource.of(video.path), effects = effects)
}
