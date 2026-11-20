package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.FramePixels
import dev.jordond.filmstrip.picture.animated.animatedPeakBytes
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.picture.animated.animatedSourceBytes
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Resolves the animated picture at the path it is given, on an SDR grade or on a kept PQ grade when a second argument
 * says `pq`, in a JVM of its own with a heap that only holds the copies a lowering needs, and exits with 0 when the
 * overlay resolved.
 *
 * Called with [COUNT] and a path instead, it prints the peak [animatedPeakBytes] counts for that picture, one line for
 * each of [COUNTED] in order, so the JVM that sizes the heap never decodes the picture itself.
 */
internal object AtlasMemoryProbe {
  val SIZE = Size(480, 270)
  const val FRAMES = 100
  const val COUNT = "count"
  val COUNTED: List<AnimatedCopies> =
    listOf(AnimatedCopies.Ffmpeg, AnimatedCopies.FfmpegTagged, AnimatedCopies.FfmpegHdr)

  @JvmStatic
  fun main(args: Array<String>) {
    if (args.first() == COUNT) {
      count(File(args[1]))
      return
    }
    val frame = Size(640, 360)
    val attributes =
      Attributes(
        inputSize = frame,
        outputSize = frame,
        layoutSize = frame,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = if (args.getOrNull(1) == "pq") HdrTransfer.Pq else null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 3.seconds),
      )
    val spec = ImageOverlay(ImageSource.of(args.first()), Corner.TopStart)
    val resolution = BuiltInEffectResolver(FfmpegPictureDecoder("ffmpeg")).resolve(spec, CAPABILITIES, attributes)
    if (resolution is EffectResolution.Resolved) {
      println("resolved")
      System.exit(0)
    }
    println(resolution)
    System.exit(1)
  }

  private fun count(file: File) {
    val picture = (animatedPictureOf(file.readBytes()) as AnimatedRead.Animated).picture
    val sixteenBit = picture.frames.any { (it.pixels as? FramePixels.Samples)?.sixteenBit == true }
    val source = checkNotNull(animatedSourceBytes(picture, file.length()))
    for (held in COUNTED) {
      println(checkNotNull(animatedPeakBytes(picture.shown, picture.frames.size, held, sixteenBit, source)))
    }
  }
}
