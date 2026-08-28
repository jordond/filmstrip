package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.brightness
import dev.jordond.filmstrip.effects.crop
import dev.jordond.filmstrip.effects.flip
import dev.jordond.filmstrip.effects.rotate
import dev.jordond.filmstrip.effects.scale
import dev.jordond.filmstrip.effects.text
import dev.jordond.filmstrip.effects.watermark
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the frame is reframed, since [Crop] and [CropRect] are two different effects rather than two
 * settings of one.
 */
public enum class CropMode {
  Off,
  Aspect,
  Rect,
}

/**
 * Which arm of [Fill] backs the frame.
 */
public enum class FillMode {
  Solid,
  Blurred,
}

/**
 * Which arm of [AudioSpec] the composition's mixed audio takes.
 */
public enum class AudioMode {
  Keep,
  Volume,
  Mute,
  Remove,
  AudioOnly,
}

/**
 * Every knob the editor exposes, held as compose state and lowered onto an [EditComposition] on
 * demand.
 *
 * One field per parameter of the built-in effect catalogue, so nothing in `filmstrip-effects` is
 * reachable from the library but unreachable from the sample.
 */
@Stable
public class EditState {
  var trimEnabled: Boolean by mutableStateOf(false)
  var trimStartSeconds: Float by mutableStateOf(0f)
  var trimEndSeconds: Float by mutableStateOf(0f)
  var clipMuted: Boolean by mutableStateOf(false)

  var rotationDegrees: Int by mutableStateOf(0)
  var flipHorizontal: Boolean by mutableStateOf(false)
  var flipVertical: Boolean by mutableStateOf(false)

  var cropMode: CropMode by mutableStateOf(CropMode.Off)
  var cropAspect: AspectRatio by mutableStateOf(AspectRatio.Portrait)
  var cropFit: Fit by mutableStateOf(Fit.Crop)
  var cropAnchorX: Float by mutableStateOf(0.5f)
  var cropAnchorY: Float by mutableStateOf(0.5f)
  var cropLeft: Float by mutableStateOf(0.1f)
  var cropTop: Float by mutableStateOf(0.1f)
  var cropRight: Float by mutableStateOf(0.9f)
  var cropBottom: Float by mutableStateOf(0.9f)

  var scaleEnabled: Boolean by mutableStateOf(false)
  var scaleHeight: Int by mutableStateOf(720)
  var scaleFit: Fit by mutableStateOf(Fit.Contain)

  var brightness: Float by mutableStateOf(1f)

  var textEnabled: Boolean by mutableStateOf(false)
  var text: String by mutableStateOf("")
  var textSize: Float by mutableStateOf(0.06f)
  var textWeight: FontWeight by mutableStateOf(FontWeight.Bold)
  var textColor: Int by mutableStateOf(WHITE)
  var textPlate: Int? by mutableStateOf(null)
  var textAlignment: TextAlignment by mutableStateOf(TextAlignment.Center)
  var textMaxWidth: Float by mutableStateOf(0.9f)
  var textAnchorX: Float by mutableStateOf(0.5f)
  var textAnchorY: Float by mutableStateOf(1f)
  var textTimed: Boolean by mutableStateOf(false)
  var textStartSeconds: Float by mutableStateOf(0f)
  var textEndSeconds: Float by mutableStateOf(3f)

  var watermarkImage: ImageSource? by mutableStateOf(null)
  var watermarkLabel: String by mutableStateOf("")
  var watermarkCorner: Corner by mutableStateOf(Corner.BottomEnd)
  var watermarkMargin: Float by mutableStateOf(0.04f)
  var watermarkScale: Float by mutableStateOf(0.2f)
  var watermarkOpacity: Float by mutableStateOf(1f)
  var watermarkTimed: Boolean by mutableStateOf(false)
  var watermarkStartSeconds: Float by mutableStateOf(0f)
  var watermarkEndSeconds: Float by mutableStateOf(3f)

  var audioMode: AudioMode by mutableStateOf(AudioMode.Keep)
  var audioGain: Float by mutableStateOf(1f)

  var fillMode: FillMode by mutableStateOf(FillMode.Solid)
  var fillColor: Int by mutableStateOf(BLACK)
  var blurRadius: Float by mutableStateOf(0.04f)
  var blurDim: Float by mutableStateOf(0f)

  val cropRect: NormalizedRect
    get() = NormalizedRect(cropLeft, cropTop, cropRight, cropBottom)

  val cropAnchor: Anchor
    get() = Anchor(cropAnchorX, cropAnchorY)

  val textAnchor: Anchor
    get() = Anchor(textAnchorX, textAnchorY)

  val textStyle: TextStyle
    get() = TextStyle(
      fontSize = textSize,
      weight = textWeight,
      color = textColor,
      backgroundColor = textPlate,
      alignment = textAlignment,
      maxWidth = textMaxWidth,
    )

  val fill: Fill
    get() = when (fillMode) {
      FillMode.Solid -> Fill.Solid(fillColor)
      FillMode.Blurred -> Fill.Blurred(blurRadius, blurDim)
    }

  val audioSpec: AudioSpec
    get() = when (audioMode) {
      AudioMode.Keep -> AudioSpec.Keep
      AudioMode.Volume -> AudioSpec.Volume(audioGain)
      AudioMode.Mute -> AudioSpec.Mute
      AudioMode.Remove -> AudioSpec.Remove
      AudioMode.AudioOnly -> AudioSpec.AudioOnly
    }

  /**
   * How much of the source the edit keeps, or null while nothing has been trimmed.
   */
  fun trimRange(sourceDuration: Duration?): TimeRange? {
    if (!trimEnabled || sourceDuration == null) return null
    val start = trimStartSeconds.toDouble().seconds.coerceIn(Duration.ZERO, sourceDuration)
    val end = trimEndSeconds.toDouble().seconds.coerceIn(start, sourceDuration)
    return if (end > start) TimeRange(start, end) else null
  }

  /**
   * How long the edit runs, which is the trim when there is one and the whole source otherwise.
   */
  fun editedDuration(sourceDuration: Duration?): Duration? =
    trimRange(sourceDuration)?.duration ?: sourceDuration

  /**
   * The output frame's aspect after every geometry effect, so the preview can letterbox to the
   * shape the file will actually have.
   */
  fun outputAspect(sourceAspect: Float): Float {
    val rotated = if (rotationDegrees == 90 || rotationDegrees == 270) 1f / sourceAspect else sourceAspect
    return when (cropMode) {
      CropMode.Off -> rotated
      CropMode.Aspect -> cropAspect.value
      CropMode.Rect -> {
        val rect = cropRect
        if (rect.isValid) rotated * (rect.width / rect.height) else rotated
      }
    }
  }

  /**
   * Builds the value that `plan`, `export` and `preview` all take.
   */
  fun composition(
    filmstrip: Filmstrip,
    source: MediaSource,
    sourceDuration: Duration?,
  ): EditComposition =
    filmstrip.composition {
      clip(source) {
        trimRange(sourceDuration)?.let { trim(it) }
        if (clipMuted) audio(AudioLevel.Mute)
      }

      effects {
        if (rotationDegrees != 0) rotate(rotationDegrees)
        if (flipHorizontal) flip(FlipAxis.Horizontal)
        if (flipVertical) flip(FlipAxis.Vertical)

        when (cropMode) {
          CropMode.Off -> Unit
          CropMode.Aspect -> crop(cropAspect, cropFit, cropAnchor)
          CropMode.Rect -> if (cropRect.isValid) crop(cropRect)
        }

        if (scaleEnabled) scale(scaleHeight, scaleFit)
        if (brightness != 1f) brightness(brightness)

        if (textEnabled && text.isNotBlank()) {
          text(
            text = text,
            style = textStyle,
            anchor = textAnchor,
            visibleDuring = window(textTimed, textStartSeconds, textEndSeconds),
          )
        }

        watermarkImage?.let { image ->
          watermark(
            image = image,
            corner = watermarkCorner,
            margin = watermarkMargin,
            scale = watermarkScale,
            opacity = watermarkOpacity,
            visibleDuring = window(watermarkTimed, watermarkStartSeconds, watermarkEndSeconds),
          )
        }
      }

      audio(audioSpec)
      fill(fill)
    }

  /**
   * Puts every knob back where it started, for the editor's reset action.
   */
  fun reset(sourceDuration: Duration?) {
    trimEnabled = false
    trimStartSeconds = 0f
    trimEndSeconds = sourceDuration?.let { it.inWholeMilliseconds / 1000f } ?: 0f
    clipMuted = false
    rotationDegrees = 0
    flipHorizontal = false
    flipVertical = false
    cropMode = CropMode.Off
    cropAspect = AspectRatio.Portrait
    cropFit = Fit.Crop
    cropAnchorX = 0.5f
    cropAnchorY = 0.5f
    cropLeft = 0.1f
    cropTop = 0.1f
    cropRight = 0.9f
    cropBottom = 0.9f
    scaleEnabled = false
    scaleHeight = 720
    scaleFit = Fit.Contain
    brightness = 1f
    textEnabled = false
    text = ""
    textSize = 0.06f
    textWeight = FontWeight.Bold
    textColor = WHITE
    textPlate = null
    textAlignment = TextAlignment.Center
    textMaxWidth = 0.9f
    textAnchorX = 0.5f
    textAnchorY = 1f
    textTimed = false
    watermarkImage = null
    watermarkLabel = ""
    watermarkCorner = Corner.BottomEnd
    watermarkMargin = 0.04f
    watermarkScale = 0.2f
    watermarkOpacity = 1f
    watermarkTimed = false
    audioMode = AudioMode.Keep
    audioGain = 1f
    fillMode = FillMode.Solid
    fillColor = BLACK
    blurRadius = 0.04f
    blurDim = 0f
  }

  private fun window(
    enabled: Boolean,
    startSeconds: Float,
    endSeconds: Float,
  ): TimeRange? {
    if (!enabled) return null
    val start = startSeconds.toDouble().seconds
    val end = endSeconds.toDouble().seconds
    return if (end > start) TimeRange(start, end) else null
  }

  public companion object {
    public const val WHITE: Int = 0xFFFFFFFF.toInt()
    public const val BLACK: Int = 0xFF000000.toInt()
  }
}
