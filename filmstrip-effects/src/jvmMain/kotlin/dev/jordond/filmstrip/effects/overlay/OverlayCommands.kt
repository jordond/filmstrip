package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.filePathOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.stream.ImageInputStream
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

// Why this overlay's animation cannot be driven over [run] whatever its picture holds, or null when it can or has
// none. It needs no read of the picture, so it runs ahead of one.
internal fun ImageOverlay.animationRefusal(
  attributes: Attributes,
  run: TimeRange,
): String? =
  when {
    animation == null -> null
    attributes.gridRate == null -> ANIMATION_NO_GRID
    run.endExclusive == null -> ANIMATION_NO_RUN
    else -> null
  }

// What an animated overlay's branch has to be driven by. The animation is sampled once per output
// frame of the run, and each option is written as the intervals it holds one value over. A
// command written at exactly a frame's own time lands on that frame, so an interval opens on the
// grid rather than anywhere nudged off it, and the clock is the branch's, the same one the enable
// gate reads. An option is still only sent where its own value moves, and its intervals cover the
// whole run, so a branch opened part way through reads what it is holding there instead of
// whatever the graph configured the filter at.
//
// On an HDR grade the region the blend runs on has to hold every position the overlay reaches, so
// it is laid out from all of them before a command is written. Each of the two branches gets its
// own file, since sendcmd hands a command to the first filter of that name and no further.
//
// [decoded] is the picture's size when it was already decoded for this resolve. Otherwise the decoder sizes it
// whenever there is one, turned the way it decodes it.
internal fun ImageOverlay.commandsFor(
  attributes: Attributes,
  run: TimeRange,
  graded: Boolean,
  decoder: OverlayPictureDecoder?,
  decoded: Size?,
): Driven {
  if (animation == null) return Driven.Still
  val rate = attributes.gridRate ?: return Driven.Refused(ANIMATION_NO_GRID)
  val end = run.endExclusive ?: return Driven.Refused(ANIMATION_NO_RUN)
  val imageSize =
    decoded ?: when (val read = decoder?.measure(image)) {
      is OverlayPictureRead.Read -> read.value
      is OverlayPictureRead.Refused -> return Driven.Refused(UNREADABLE_IMAGE.saying(read.reason))
      null -> measureOverlay(image) ?: return Driven.Refused(UNREADABLE_IMAGE)
    }
  val span = attributes.span
  val drawnOn = attributes.inputSize
  val base = placedOn(drawnOn, imageSize)

  val firstSlot = outputSlotOf(span.start, rate)
  val last = frameIndexAt(end, span, rate)
  // A frame the sampler draws nothing on is left out rather than written as a change, so the
  // interval running across it carries on holding what it held.
  val frames =
    (frameIndexAt(run.start, span, rate) until last).mapNotNull { index ->
      frameWithin(run, outputSlotTime(firstSlot + index, rate))?.let { index to it }
    }
  // A run reaching no output frame, which is a window falling outside the span or one shorter
  // than a frame. There is nothing to drive, and the gate on the branch already draws nothing.
  if (frames.isEmpty()) return Driven.Still
  val drawn = frames.map { (_, frame) -> drawnRect(base.animatedBy(frame), drawnOn) }
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

// One option's intervals across a sampled run: each opens on the frame the value moves to and
// runs to the frame that moves it next, or to the end of the run for the last of them.
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
  this[command] ?: error("A sampled overlay frame carries no $command, so the commands cannot cover the run.")

// Where the overlay lands on one frame, in pixels. Every number is the shared placement's:
// animatedBy scales both sides and moves the frame anchor, and the point inside the overlay that
// the anchor holds is what turns the pair into a position.
private fun drawnRect(
  drawn: OverlayPlacement,
  drawnOn: Size,
): PixelRect {
  val width = drawn.size.width
  val height = drawn.size.height
  val anchorX = (drawn.frameAnchor.x * drawnOn.width).roundToInt()
  val anchorY = (drawn.frameAnchor.y * drawnOn.height).roundToInt()

  return PixelRect(
    x = anchorX - (drawn.overlayAnchor.x * width).roundToInt(),
    y = anchorY - (drawn.overlayAnchor.y * height).roundToInt(),
    width = width,
    height = height,
  )
}

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
 * The overlay image's stored pixel size, or null when the JDK cannot read it.
 *
 * ImageIO picks a reader off the stream's own magic bytes and answers the bounds out of the
 * header, so nothing here pulls the picture into memory. A URI is read as the file path the
 * backend materialises it to, which is the file ffmpeg itself opens. Only read for a catalogue
 * built without an [OverlayPictureDecoder].
 */
private fun measureOverlay(image: ImageSource): Size? {
  val stream = image.openHeader() ?: return null

  return try {
    val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
    reader?.let {
      try {
        it.input = stream
        Size(it.getWidth(FIRST_IMAGE), it.getHeight(FIRST_IMAGE))
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

internal fun ImageSource.openHeader(): ImageInputStream? =
  try {
    when (this) {
      is ImageSource.Path -> {
        File(path).takeIf { it.isFile }?.let(ImageIO::createImageInputStream)
      }
      is ImageSource.Uri -> {
        filePathOf(uri)?.let(::File)?.takeIf { it.isFile }?.let(ImageIO::createImageInputStream)
      }
      is ImageSource.Bytes -> {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
      }
    }
  } catch (unreadable: IOException) {
    null
  }

// colorchannelmixer documents its coefficients in -2..2, which is this filter's own limit. The
// shared sampler already answers an opacity in 0f..1f, so this pins that guarantee rather than
// bending a value.
private fun alphaValue(opacity: Float): String =
  String.format(Locale.ROOT, COMMAND_FORMAT, opacity.coerceIn(MIXER_FLOOR, MIXER_CEILING))

// The first output frame at or after a composition time, counted on the branch's own clock. The
// branch's frames sit on the output slots from the one the span's start lands on, so this counts
// the slots from there to the first one at or after [time]. The comparison is in whole
// microseconds, the grid's own unit, so a time sitting on a slot counts as that slot.
internal fun frameIndexAt(
  time: Duration,
  span: TimeRange,
  rate: Int,
): Int {
  val landed = outputSlotOf(time, rate)
  val atOrAfter = if (outputSlotTime(landed, rate) < time.inWholeMicroseconds.microseconds) landed + 1 else landed
  return (atOrAfter - outputSlotOf(span.start, rate)).coerceAtLeast(0).toInt()
}

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
  // Nothing to drive: an overlay holding still, or one whose run reaches no output frame.
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

private const val ANIMATION_NO_GRID =
  "An animated overlay is sampled once per output frame, and this composition resolved no frame " +
    "rate to sample it onto. Give the export a frame rate, or drop the animation to draw the " +
    "overlay still."

private const val ANIMATION_NO_RUN =
  "An animated overlay is sampled across the run it is drawn over, and this composition names no " +
    "end for that run. Give the export a duration, or drop the animation to draw the overlay still."

private const val UNREADABLE_IMAGE =
  "The overlay image could not be read. An animated overlay is sized against the picture's own " +
    "pixels, so this backend reads its header, and neither the JDK nor this ffmpeg could read it. " +
    "Check that the path or URL is readable by this process, and that the bytes are a picture this " +
    "ffmpeg build decodes."
