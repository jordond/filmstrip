package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.describe
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadataNode

// Why this overlay's animation cannot be driven over [drawnRange] whatever its picture holds, or null when it can or
// has none. It needs no read of the picture, so it runs ahead of one.
internal fun OverlayEffect.animationRefusal(
  attributes: Attributes,
  drawnRange: TimeRange,
): String? =
  when {
    animation == null -> null
    attributes.gridRate == null -> ANIMATION_NO_GRID
    drawnRange.endExclusive == null -> ANIMATION_NO_END
    else -> null
  }

// What an animated overlay's branch has to be driven by. The animation is sampled once per output
// frame of the drawn range, and each option is written as the intervals it holds one value over. A
// command written at exactly a frame's own time lands on that frame, so an interval opens on the
// grid rather than anywhere nudged off it, and the clock is the branch's, the same one the enable
// gate reads. An option is still only sent where its own value moves, and its intervals cover the
// whole drawn range, so a branch opened part way through reads what it is holding there instead of
// whatever the graph configured the filter at.
//
// On an HDR grade the region the blend runs on has to hold every position the overlay reaches, so
// it is laid out from all of them before a command is written. Each of the two branches gets its
// own file, since sendcmd hands a command to the first filter of that name and no further.
//
// Each frame's placement is the still placement moved by what the animation answers for that frame.
internal fun PlacedPicture.commandsFor(
  attributes: Attributes,
  drawnRange: TimeRange,
  graded: Boolean,
): Driven {
  if (effect.animation == null) return Driven.Still
  val rate = attributes.gridRate ?: return Driven.Refused(ANIMATION_NO_GRID)
  val end = drawnRange.endExclusive ?: return Driven.Refused(ANIMATION_NO_END)
  val span = attributes.span
  val drawnOn = attributes.inputSize

  val firstSlot = outputSlotOf(span.start, rate)
  val last = frameIndexAt(end, span, rate)
  // A frame the sampler draws nothing on is left out rather than written as a change, so the
  // interval running across it carries on holding what it held.
  val frames =
    (frameIndexAt(drawnRange.start, span, rate) until last).mapNotNull { index ->
      effect.frameWithin(drawnRange, outputSlotTime(firstSlot + index, rate))?.let { index to it }
    }
  // A drawn range reaching no output frame, which is a window falling outside the span or one shorter
  // than a frame. There is nothing to drive, and the gate on the branch already draws nothing.
  if (frames.isEmpty()) return Driven.Still
  val drawn = frames.map { (_, frame) -> drawnRect(placement.animatedBy(frame), drawnOn) }
  val layout = if (graded) hdrOverlayLayout(drawn, drawnOn) else null
  val sampled =
    frames.mapIndexed { position, (index, frame) -> index to commandValues(frame, drawn[position], layout) }

  val written = sampled.written(last, rate)
  val name = instanceNameOf(written)
  if (layout == null) return Driven.Commands(commandFile(written, name), name)

  val picture = sampled.map { (index, values) -> index to values.filterKeys { !it.startsWith(MIXER) } }
  return Driven.Commands(
    sidecar = commandFile(picture.written(last, rate), name),
    instance = name,
    mask = commandFile(written, name + MASK_INSTANCE),
    layout = layout,
    first = drawn.first(),
    pictureHeld = picture.all { it.second == picture.first().second },
  )
}

private fun commandFile(
  written: String,
  instance: String,
): Sidecar = Sidecar(written.replace(INSTANCE_SLOT, instance).encodeToByteArray(), "cmd")

// Every option the samples carry, written as the intervals each holds one value over.
private fun List<Pair<Int, Map<String, String>>>.written(
  last: Int,
  rate: Int,
): String =
  first()
    .second
    .keys
    .flatMap { command -> holdsOf(command, last) }
    .sortedBy { it.from }
    .joinToString("") { hold ->
      "${commandSeconds(hold.from.toDouble() / rate)}-${commandSeconds(hold.until.toDouble() / rate)} " +
        "${hold.command};\n"
    }

// One option's intervals across a sampled drawn range: each opens on the frame the value moves to and
// runs to the frame that moves it next, or to the end of the drawn range for the last of them.
private fun List<Pair<Int, Map<String, String>>>.holdsOf(
  command: String,
  end: Int,
): List<Hold> {
  val opens =
    filterIndexed { position, sample ->
      position == 0 || this[position - 1].second.driving(command) != sample.second.driving(command)
    }

  return opens.mapIndexed { position, (index, values) ->
    Hold(index, opens.getOrNull(position + 1)?.first ?: end, "$command ${values.driving(command)}")
  }
}

// The keys come off the first frame sampled, so a frame answering a different set of them would
// otherwise write a file that drives some of the filters and leaves the rest where they were.
private fun Map<String, String>.driving(command: String): String =
  this[command] ?: error("A sampled overlay frame carries no $command, so the commands cannot cover the drawn range.")

// What each driven filter is set to on one frame, keyed by the target and option the command
// names, so each one can be written over the frames it holds. The position goes to the merge, or
// on an HDR grade to the cut that lines the picture up with the region the blend runs on.
private fun commandValues(
  frame: OverlayFrame,
  drawn: PixelRect,
  layout: HdrOverlayLayout?,
): Map<String, String> {
  val x = layout?.windowX(drawn.x) ?: drawn.x
  val y = layout?.windowY(drawn.y) ?: drawn.y
  val positioned = if (layout == null) "overlay" else "crop"

  return mapOf(
    "$MIXER$INSTANCE_SLOT aa" to alphaValue(frame.opacity),
    "scale$INSTANCE_SLOT w" to drawn.width.toString(),
    "scale$INSTANCE_SLOT h" to drawn.height.toString(),
    "$positioned$INSTANCE_SLOT x" to x.toString(),
    "$positioned$INSTANCE_SLOT y" to y.toString(),
  )
}

/**
 * The size this picture is shown at, which is the size the graph places it by.
 *
 * The JDK reads the size out of the picture's header, and [decoder] measures it only when the JDK has no reader for
 * the format. A path or file URL that is not a file this process can read is handed to [refuse] without asking
 * [decoder], as is a picture neither can size. The size is cut to the crop [cropLeftOf] reads and turned by the
 * orientation the graph lays the picture out by.
 */
internal inline fun ImageSource.shownSize(
  decoder: OverlayPictureDecoder?,
  refuse: (String) -> Nothing,
): Size {
  val bytes = encodedBytes() ?: refuse(unreadableImageFile(this))
  val stored =
    measureOverlay(bytes) ?: when (val read = decoder?.measure(this)) {
      is OverlayPictureRead.Read -> read.value
      is OverlayPictureRead.Refused -> refuse(UNREADABLE_IMAGE.saying(read.reason))
      null -> refuse(UNREADABLE_IMAGE)
    }
  return orientationLeftOf(bytes).orientedSize(cropLeftOf(bytes)?.size ?: stored)
}

/**
 * The stored pixel size of the picture [bytes] encode, or null when the JDK has no reader for them.
 *
 * ImageIO picks a reader off the bytes' own magic and answers the bounds out of the header, so no texel is decoded. A
 * GIF answers its logical screen rather than its first frame's bounds, since that canvas is the frame ffmpeg decodes.
 */
internal fun measureOverlay(bytes: ByteArray): Size? {
  val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null

  return try {
    val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
    reader?.let {
      try {
        it.input = stream
        it.gifScreen() ?: Size(it.getWidth(FIRST_IMAGE), it.getHeight(FIRST_IMAGE))
      } finally {
        it.dispose()
      }
    }
  } catch (unreadable: IOException) {
    null
  } finally {
    stream.close()
  }
}

// The logical screen a GIF's stream metadata describes, or null for a reader of any other format.
private fun ImageReader.gifScreen(): Size? {
  if (!formatName.equals(GIF, ignoreCase = true)) return null
  val root = streamMetadata?.getAsTree(GIF_STREAM_METADATA) ?: return null
  val screen =
    (0 until root.childNodes.length)
      .map { root.childNodes.item(it) }
      .firstOrNull { it.nodeName == LOGICAL_SCREEN } as? IIOMetadataNode ?: return null
  val width = screen.getAttribute(SCREEN_WIDTH).toIntOrNull() ?: return null
  val height = screen.getAttribute(SCREEN_HEIGHT).toIntOrNull() ?: return null
  return Size(width, height)
}

// colorchannelmixer documents its coefficients in -2..2, which is this filter's own limit. The
// shared sampler already answers an opacity in 0f..1f, so this pins that guarantee rather than
// bending a value.
private fun alphaValue(opacity: Float): String =
  String.format(Locale.ROOT, COMMAND_FORMAT, opacity.coerceIn(MIXER_FLOOR, MIXER_CEILING))

private fun commandSeconds(value: Double): String = String.format(Locale.ROOT, COMMAND_FORMAT, value)

// The instance names have to be unique across the graph, and they are part of the file that
// drives them, so the name is taken from a digest of the commands before they are named. Sidecar
// already answers a digest of what it holds, so the throwaway one here is that hash rather than a
// second file. Two overlays whose commands differ are named apart. Two whose commands are
// identical share one name and one sidecar, which is harmless: identical commands draw the same
// picture, so either branch's copy drives both the same way.
private fun instanceNameOf(commands: String): String =
  "@fs" +
    Sidecar(commands.encodeToByteArray(), "cmd")
      .placeholder
      .trim('<', '>')
      .substringAfterLast('-')

// One sendcmd interval, before it is written as text: the output frames it spans, and the single
// command it sends on the frame it opens on.
private class Hold(
  val from: Int,
  val until: Int,
  val command: String,
)

internal sealed interface Driven {
  // Nothing to drive: an overlay holding still, or one whose drawn range reaches no output frame.
  object Still : Driven

  class Refused(
    val message: String,
  ) : Driven

  // On an HDR grade sidecar drives the picture's branch and mask the alpha's, and layout is where
  // the blend runs, holding every position from first on. pictureHeld is set when nothing but the
  // opacity moves, so the picture is drawn at first on every frame and needs no commands.
  class Commands(
    val sidecar: Sidecar,
    val instance: String,
    val mask: Sidecar? = null,
    val layout: HdrOverlayLayout? = null,
    val first: PixelRect? = null,
    val pictureHeld: Boolean = false,
  ) : Driven
}

// The token the instance names are written with while the commands are being built, since the name
// itself is a digest of what they say. It is replaced once, on the way into the file.
private const val INSTANCE_SLOT = "@fs?"

// Six decimals. A frame time on any rate this backend writes stops moving well before that, and it
// is what a command's own value is written to as well, so one format serves both.
private const val COMMAND_FORMAT = "%.6f"

// colorchannelmixer's documented coefficient range, which is this filter's own limit.
private const val MIXER_FLOOR = -2f
private const val MIXER_CEILING = 2f

// The filter an animated opacity drives, which only the alpha's branch carries on an HDR grade.
private const val MIXER = "colorchannelmixer"

// What the alpha's branch appends to an animated overlay's instance name, so its filters are told
// apart from the picture's.
internal const val MASK_INSTANCE = "a"

// The image in an ImageIO stream, which is the only one a still carries.
private const val FIRST_IMAGE = 0

// The JDK GIF reader's format name and the stream metadata nodes it writes the logical screen to.
private const val GIF = "gif"
private const val GIF_STREAM_METADATA = "javax_imageio_gif_stream_1.0"
private const val LOGICAL_SCREEN = "LogicalScreenDescriptor"
private const val SCREEN_WIDTH = "logicalScreenWidth"
private const val SCREEN_HEIGHT = "logicalScreenHeight"

private const val ANIMATION_NO_GRID =
  "An animated overlay is sampled once per output frame, and this composition resolved no frame " +
    "rate to sample it onto. Give the export a frame rate, or drop the animation to draw the " +
    "overlay still."

private const val ANIMATION_NO_END =
  "An animated overlay is sampled across the range it is drawn over, and this composition names no " +
    "end for that range. Give the export a duration, or drop the animation to draw the overlay still."

internal const val UNREADABLE_IMAGE =
  "The overlay image could not be read. An overlay is sized and placed against the picture's own " +
    "pixels, so this backend reads its header, and neither the JDK nor this ffmpeg could read it. " +
    "Check that the path or URL is readable by this process, and that the bytes are a picture this " +
    "ffmpeg build decodes."

// Why an overlay is refused when [image] names no file this process can read, with the path or URL it names.
internal fun unreadableImageFile(image: ImageSource): String =
  "The overlay image ${image.describe()} could not be read. An overlay is sized and placed against the picture's " +
    "own pixels, so this backend reads its header, and this process found no file there to read. Check that the " +
    "path or URL names a file readable by this process."
