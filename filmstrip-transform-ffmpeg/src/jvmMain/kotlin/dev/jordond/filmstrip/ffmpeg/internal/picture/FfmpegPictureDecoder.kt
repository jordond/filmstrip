package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.decodedPng
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.filePathOf
import dev.jordond.filmstrip.picture.heif.HeifAlpha
import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifAlpha
import dev.jordond.filmstrip.picture.heif.heifWithExplicitCodestreamColor
import dev.jordond.filmstrip.picture.heif.heifWithoutOrientation
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureYcbcrCoding
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal actual fun decodingOverlayPictures(
  resolvers: List<EffectResolver>,
  toolchain: Toolchain,
): List<EffectResolver> {
  val decoder = FfmpegPictureDecoder(toolchain.ffmpeg)
  return resolvers.map { if (it is BuiltInEffectResolver) BuiltInEffectResolver(decoder) else it }
}

/**
 * Reads an overlay picture with the backend's own ffmpeg.
 *
 * The picture is opened the way an export opens it, bytes written to a file first and a URI read as the file it names,
 * so this reads exactly the pictures an export draws. Every child runs with `-noautorotate`, so ffmpeg turns it by no
 * orientation tag, and an AVIF or HEIC is read with its `irot`, `imir` and `clap` dropped on top. A JPEG XL still
 * comes back turned, since libjxl lays it out by its codestream's orientation and ffmpeg's decoder has no way to keep
 * it from doing so. Every read runs the first frame of the picture's primary image through a filter, as
 * [primaryLayout] lays it out. A picture ffmpeg decodes as one stream goes through a simple graph, whose input is the
 * stream ffmpeg picks for its output, and for a HEIF file that is the primary item rather than a thumbnail written
 * ahead of it. A picture this process cannot read the bytes of takes that graph too, so a tiled one ffmpeg alone opens
 * is refused rather than read off a thumbnail.
 *
 * ffmpeg never opens an AVIF or HEIC whose primary item is a `grid` or an `iovl`. Its coded images are read out of
 * the file and decoded as elementary streams, each run of them by one child, and put together here by [assembled]
 * through [gridAssembly], [overlayAssembly] or, on the planes route, [planesAssembly]. What ffmpeg still does is
 * decode each run and convert each frame, and run the finished canvas through the filter in one more child. An
 * `iovl`'s canvas is painted in the color [pictureColor] reads out of the bytes, the overlay's own `colr` ahead of its
 * inputs', as plane codes on the planes route. An `iovl` put together as RGB comes back at eight bits whatever its
 * inputs' depth.
 *
 * A decode takes one of the routes [pictureRoute] picks for the picture's YCbCr coding. Most hand back the frame
 * through ffmpeg's own conversion to RGB, and it travels as a PNG of the depth and channels ffmpeg picks for the
 * source, so a grey picture stays grey and one deeper than eight bits keeps sixteen. The PNG carries the color
 * description ffmpeg's decoder found in its `cICP` and `iCCP` chunks, read by [decodedPng]. A picture whose matrix
 * swscale cannot convert travels instead as the frame's 4:4:4 planes at its own depth in a `yuv4mpegpipe` stream, and
 * core's evaluator for its reading turns them into RGB here, with no description. A measure of a `grid` or an `iovl`
 * answers with its canvas size and runs no child. Any other measure runs the frame through a graph that hands it on
 * untouched into ffmpeg's `framecrc` muxer, which decodes the frame and writes its size without encoding a picture, so
 * it is the size the decode hands back at a fraction of its cost.
 *
 * A picture whose file carries an alpha item, as [heifAlpha] reads it, runs one more read that decodes that item's
 * coded images straight out of the file, alone or put together by [alphaAssembly], as a sixteen-bit grey PNG, and its
 * codes become the alpha of every texel either route hands back. A file that records its color as premultiplied by the
 * alpha comes back straight. An alpha plane that does not decode, or one of another size than the picture, is
 * refused. So is an `iovl` alpha item core gives a [HeifAlpha.refusal], before anything is written or run.
 *
 * An AVIF or HEIC is decoded from [heifWithExplicitCodestreamColor]'s bytes, whose codestream states the matrix and
 * range its `nclx` box records. Without an `nclx` it states its own signal, with a matrix it leaves out or leaves
 * unspecified written as BT.601, so the picture lands per its codestream. Its matrix and range are still set on the
 * conversion from what [pictureYcbcrCoding] reads, so the file's own box wins the way libavif and libheif read it. A
 * picture whose [YcbcrCoding.colorKnown] is false is left to the decoder. One whose `irot`, `imir` and `clap` drop is
 * refused is [OverlayPictureDecode.Undrawn] with [UnturnedHeif.Refused.message] before anything is written or run. A
 * measure reads the bytes [heifWithoutOrientation] hands over, and is refused with that message the same way.
 *
 * A picture with a [YcbcrCoding.refusal] is [OverlayPictureDecode.Undrawn] with core's reason before anything is
 * written or run. So is one whose matrix swscale cannot convert when core does not know the depth its samples are
 * stored at, with ffmpeg's own reason. A `grid` or `iovl` core refuses is refused with its reason the same way. How
 * much a picture put together holds is bounded by core's ceiling on the bytes a picture holds, and every child decodes
 * on one thread.
 *
 * Every read is a child process that never outlives the call, run by [ranToEnd]. A decode's children share one
 * deadline, [timeoutMillis] after the decode starts, and a measure has one of its own. A child still running at its
 * deadline is stopped and the read refused. One whose calling thread is interrupted, which is how a cancelled plan
 * reaches it, is stopped and the [InterruptedException] is thrown on to the caller.
 *
 * @param scratch The directory picture bytes are written to, or null for the system's temporary directory.
 * @param write Writes picture bytes to the file ffmpeg reads them from.
 */
internal class FfmpegPictureDecoder(
  private val ffmpeg: String,
  private val timeoutMillis: Long = PICTURE_TIMEOUT_MILLIS,
  private val scratch: File? = null,
  private val write: (File, ByteArray) -> Unit = File::writeBytes,
) : OverlayPictureDecoder {
  // ffmpeg's swscale limit, not one this applies: a YCbCr source deeper than eight bits that swscale converts reaches
  // sixteen-bit RGB short of full scale, limited range at 255/256 and full range at 1023/1024, so its
  // StoredSamples.Rgba16 sit up to four ten-bit codes low. No flag or format hop moves it, and the shortfall is not one
  // scale, so nothing rescales it. A picture decoded from its planes has no shortfall.
  override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> {
    val deadline = deadlineFromNow()
    val bytes = encodedBytes(image)
    val alpha = bytes?.let(::heifAlpha)
    alpha?.refusal?.let { return OverlayPictureRead.Refused(it) }
    val route = pictureRoute(bytes?.let(::pictureYcbcrCoding))
    return when (route) {
      is PictureRoute.Refused -> {
        OverlayPictureDecode.Undrawn(route.reason)
      }
      is PictureRoute.Converted -> {
        val read = FrameRead(route.filter, PNG_ON_STDOUT, null, ::wholeStream, ::decodedPng, DecodedPicture::size)
        decodedThrough(image, bytes, alpha, deadline, read) { picture, plane -> plane?.appliedTo(picture) ?: picture }
      }
      is PictureRoute.Planes -> {
        val planes: (InputStream) -> YuvPlanes? = { yuv4mpegPlanes(it, route.bitDepth) }
        val read = FrameRead(route.filter, PLANES_ON_STDOUT, route, planes, { it }, YuvPlanes::size)
        decodedThrough(image, bytes, alpha, deadline, read) { planes, plane ->
          decodedPlanes(planes, route.evaluator, plane)
        }
      }
    }
  }

  // The first frame of [image] decoded the way [read] says, with the plane of [alpha] drawn over it by [draw]. Its
  // encoded [bytes] are read as [heifWithExplicitCodestreamColor] hands them over, or the decode is Undrawn with
  // core's sentence when their irot, imir and clap drop is refused.
  private fun <R : Any, C> decodedThrough(
    image: ImageSource,
    bytes: ByteArray?,
    alpha: HeifAlpha?,
    deadline: Long,
    read: FrameRead<R, C>,
    draw: (C, PictureAlpha?) -> DecodedPicture,
  ): OverlayPictureDecode<DecodedPicture> {
    val handed =
      bytes?.let {
        when (val heif = heifWithExplicitCodestreamColor(it)) {
          is UnturnedHeif.Refused -> return OverlayPictureDecode.Undrawn(heif.message)
          is UnturnedHeif.Handed -> heif.bytes
        }
      }
    val color =
      when (val layout = primaryLayout(bytes)) {
        is PrimaryLayout.Refused -> {
          return OverlayPictureRead.Refused(layout.reason)
        }
        is PrimaryLayout.Single -> {
          readThrough(image, bytes, handed) { input ->
            val command = opening(input) + listOf("-frames:v", "1", "-vf", read.filter) + read.output
            ranToEnd(command, deadline, timeoutMillis, null, read.collect, read.parse)
          }
        }
        is PrimaryLayout.Assembled -> {
          val assembly = assemblyOf(layout, read.filter, read.planes, checkNotNull(bytes))
          val held = checkNotNull(handed)
          assembled(ffmpeg, assembly, held, deadline, timeoutMillis, scratch, read.output, read.collect, read.parse)
        }
      }
    return when (color) {
      is OverlayPictureRead.Refused -> color
      is OverlayPictureRead.Read -> withAlpha(handed, alpha, deadline, color.value, read.sizeOf(color.value), draw)
    }
  }

  // How one route reads its first frame: the filter it runs, where ffmpeg writes the frame, as RGB unless the [planes]
  // route reads it, how stdout is gathered and parsed, and the size of what comes back.
  private class FrameRead<R : Any, C>(
    val filter: String,
    val output: List<String>,
    val planes: PictureRoute.Planes?,
    val collect: (InputStream) -> R?,
    val parse: (R) -> C?,
    val sizeOf: (C) -> Size,
  )

  // [color] drawn with the plane of [alpha] read out of [bytes], the bytes ffmpeg reads, by [deadline], or as it is
  // when the file carries no alpha item. A plane ffmpeg does not read, or one of another [size] than the color's, is
  // refused.
  @Throws(InterruptedException::class)
  private fun <C> withAlpha(
    bytes: ByteArray?,
    alpha: HeifAlpha?,
    deadline: Long,
    color: C,
    size: Size,
    draw: (C, PictureAlpha?) -> DecodedPicture,
  ): OverlayPictureRead<DecodedPicture> {
    if (alpha == null) return OverlayPictureRead.Read(draw(color, null))
    val held = checkNotNull(bytes)
    val plane =
      when (val assembly = alphaAssembly(alpha)) {
        null -> {
          decodedAlone(
            ffmpeg,
            alpha.item,
            held,
            ALPHA_FORMAT,
            deadline,
            timeoutMillis,
            PNG_ON_STDOUT,
            ::wholeStream,
            ::decodedPng,
          )
        }
        else -> {
          assembled(
            ffmpeg,
            assembly,
            held,
            deadline,
            timeoutMillis,
            scratch,
            PNG_ON_STDOUT,
            ::wholeStream,
            ::decodedPng,
          )
        }
      }
    return when (plane) {
      is OverlayPictureRead.Refused -> {
        plane
      }
      is OverlayPictureRead.Read -> {
        val read = pictureAlpha(plane.value, alpha)
        if (read.size != size) return OverlayPictureRead.Refused(alphaSizeReason(read.size, size))
        OverlayPictureRead.Read(draw(color, read))
      }
    }
  }

  override fun measure(image: ImageSource): OverlayPictureRead<Size> {
    val bytes = encodedBytes(image)
    val handed =
      bytes?.let {
        when (val heif = heifWithoutOrientation(it)) {
          is UnturnedHeif.Refused -> return OverlayPictureRead.Refused(heif.message)
          is UnturnedHeif.Handed -> heif.bytes
        }
      }
    return when (val layout = primaryLayout(bytes)) {
      is PrimaryLayout.Refused -> {
        OverlayPictureRead.Refused(layout.reason)
      }
      is PrimaryLayout.Assembled -> {
        OverlayPictureRead.Read(layout.size)
      }
      is PrimaryLayout.Single -> {
        readThrough(image, bytes, handed) { input ->
          val command = opening(input) + listOf("-frames:v", "1", "-vf", PASS_THROUGH) + SIZE_ON_STDOUT
          ranToEnd(command, deadlineFromNow(), timeoutMillis, null, ::wholeStream, ::sizeOfFrameCrc)
        }
      }
    }
  }

  // ffmpeg opening [input] with its own turning switched off, so a picture comes back as stored whatever the build
  // reads its orientation tag from, decoding on one thread.
  private fun opening(input: String): List<String> =
    listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-noautorotate", "-threads", "1", "-i", input)

  // Runs [read] on the file ffmpeg opens for [image], whose encoded [bytes] are null when this process cannot read
  // them. [handed] are the bytes ffmpeg reads in their place, null when [bytes] are. Bytes handed over as bytes, and a
  // file whose [handed] bytes are not its own, are written to a scratch file that lives for the length of [read].
  private fun <T> readThrough(
    image: ImageSource,
    bytes: ByteArray?,
    handed: ByteArray?,
    read: (String) -> OverlayPictureRead<T>,
  ): OverlayPictureRead<T> {
    val named = pathOf(image)
    val scratch = handed?.takeIf { named == null || it !== bytes }
    val written =
      scratch?.let {
        when (val file = writtenToFile(it)) {
          is OverlayPictureRead.Read -> file.value
          is OverlayPictureRead.Refused -> return file
        }
      }
    val input = written?.absolutePath ?: checkNotNull(named)

    return try {
      read(input)
    } finally {
      written?.delete()
    }
  }

  // The extension an export's scratch directory writes picture bytes under, so ffmpeg probes the same file name here.
  // A write that fails part way takes the file with it.
  private fun writtenToFile(bytes: ByteArray): OverlayPictureRead<File> {
    val file =
      try {
        File.createTempFile(SCRATCH_PREFIX, ".png", scratch)
      } catch (unwritable: IOException) {
        return OverlayPictureRead.Refused(unwritable.message.orEmpty())
      }

    var whole = false
    try {
      write(file, bytes)
      whole = true
    } catch (unwritable: IOException) {
      return OverlayPictureRead.Refused(unwritable.message.orEmpty())
    } finally {
      if (!whole) file.delete()
    }
    return OverlayPictureRead.Read(file)
  }

  // The System.nanoTime a read starting now has to finish by, [timeoutMillis] away.
  private fun deadlineFromNow(): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
}

// How [layout], the primary image of [bytes], is put together for a route that reads its frame through [filter], as
// planes when [planes] is set and as RGB otherwise.
private fun assemblyOf(
  layout: PrimaryLayout.Assembled,
  filter: String,
  planes: PictureRoute.Planes?,
  bytes: ByteArray,
): Assembly =
  when (layout) {
    is PrimaryLayout.Grid -> {
      gridAssembly(layout.grid, filter, planes)
    }
    is PrimaryLayout.Overlay -> {
      val color = pictureColor(bytes)
      val overlay = layout.overlay
      planes?.let { planesAssembly(overlay, filter, PlanesCanvas.of(overlay.fill, color, it)) }
        ?: overlayAssembly(overlay, filter, color)
    }
  }

// The file path [image] names, a URI read as the file it names, or null for bytes handed over as bytes.
private fun pathOf(image: ImageSource): String? =
  when (image) {
    is ImageSource.Path -> image.path
    is ImageSource.Uri -> filePathOf(image.uri) ?: image.uri
    is ImageSource.Bytes -> null
  }

// The encoded bytes of [image], or null when this process cannot read them.
private fun encodedBytes(image: ImageSource): ByteArray? =
  if (image is ImageSource.Bytes) image.bytes else pathOf(image)?.let { readable(File(it)) }

// Everything left in [stream], or null when that is nothing.
private fun wholeStream(stream: InputStream): ByteArray? = stream.readAllBytes().takeIf { it.isNotEmpty() }

// The bytes of [file], or null when it is not a file this process can read, which one it may not open or one too large
// for an array comes to.
private fun readable(file: File): ByteArray? =
  try {
    file.takeIf { it.isFile }?.readBytes()
  } catch (unreadable: IOException) {
    null
  } catch (forbidden: SecurityException) {
    null
  } catch (tooLarge: OutOfMemoryError) {
    null
  }

// The width and height the `#dimensions` line of ffmpeg's framecrc output gives its first stream, or null when it has
// none.
internal fun sizeOfFrameCrc(output: ByteArray): Size? {
  val dimensions =
    output
      .decodeToString()
      .lineSequence()
      .firstOrNull { it.startsWith(FRAMECRC_DIMENSIONS) }
      ?.removePrefix(FRAMECRC_DIMENSIONS)
      ?.trim() ?: return null
  val width = dimensions.substringBefore('x').toIntOrNull()
  val height = dimensions.substringAfter('x', "").toIntOrNull()
  return if (width != null && height != null && width > 0 && height > 0) Size(width, height) else null
}

private val PNG_ON_STDOUT = listOf("-c:v", "png", "-f", "image2pipe", "-")

// The frame's planes as they are behind a short text header. The muxer takes 4:4:4 past eight bits only when told to
// be less strict.
private val PLANES_ON_STDOUT = listOf("-f", "yuv4mpegpipe", "-strict", "-1", "-")

// The frame wrapped rather than encoded, so the muxer writes its size and a checksum and no picture.
private val SIZE_ON_STDOUT = listOf("-c:v", "wrapped_avframe", "-f", "framecrc", "-")

private const val FRAMECRC_DIMENSIONS = "#dimensions 0:"

// Long enough for a large AVIF on a loaded machine, and short of leaving a plan hung on a child that never finishes.
internal const val PICTURE_TIMEOUT_MILLIS: Long = 30_000

private const val SCRATCH_PREFIX = "filmstrip-overlay"
