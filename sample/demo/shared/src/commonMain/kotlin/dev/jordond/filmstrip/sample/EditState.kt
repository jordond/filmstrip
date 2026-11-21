@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.color.colorMatrix
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.grayscale
import dev.jordond.filmstrip.effects.color.hueRotate
import dev.jordond.filmstrip.effects.color.invert
import dev.jordond.filmstrip.effects.color.rgbAdjustment
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.color.sepia
import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.betamax.betamax
import dev.jordond.filmstrip.effects.filters.bloom.bloom
import dev.jordond.filmstrip.effects.filters.camcorder.camcorder
import dev.jordond.filmstrip.effects.filters.crt.crt
import dev.jordond.filmstrip.effects.filters.film.film
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.hi8.hi8
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.lut
import dev.jordond.filmstrip.effects.filters.pixel.pixel
import dev.jordond.filmstrip.effects.filters.tape.TapeLookBuilder
import dev.jordond.filmstrip.effects.filters.tape.TapeStock
import dev.jordond.filmstrip.effects.filters.tape.tapeAudio
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.flip
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.effects.geometry.scale
import dev.jordond.filmstrip.effects.overlay.imageOverlay
import dev.jordond.filmstrip.effects.overlay.nearestCornerInset
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.effects.spatial.blur
import dev.jordond.filmstrip.effects.spatial.vignette
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
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
 * Which tracking band the tape look runs, events now and then, one rolling band, or none.
 */
public enum class TapeTrackingMode {
  Events,
  Rolling,
  Off,
}

/**
 * A tape format the tape look and the tape audio can play.
 *
 * @property label The name the format picker shows.
 * @property stocks The speeds the format ran at, each under the name its chip shows.
 * @property default The speed the format starts on.
 */
public enum class TapeFormat(
  public val label: String,
  public val stocks: List<Pair<String, TapeStock>>,
  public val default: TapeStock,
) {
  Vhs("VHS", TapeSpeed.entries.map { it.name to it }, TapeSpeed.EP),
  Betamax("Betamax", BetaSpeed.entries.map { it.name to it }, BetaSpeed.II),
  Hi8("Hi8", Hi8Speed.entries.map { it.name to it }, Hi8Speed.SP),
  ;

  public companion object {
    /**
     * The format [stock] is a speed of.
     */
    public fun of(stock: TapeStock): TapeFormat = entries.first { format -> format.stocks.any { it.second == stock } }
  }
}

/**
 * Which arm of [Fill] backs the frame.
 */
public enum class FillMode {
  Solid,
  Blurred,
}

/**
 * Which arm of [OutputFrame] sets the frame the clip lands in.
 */
public enum class FrameMode {
  Auto,
  Aspect,
  Fixed,
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
 * Every knob the editor exposes, held as compose state and lowered onto a [Composition] on
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
  var snapWithinSeconds: Float by mutableStateOf(0f)
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
  var cropLockAspect: AspectRatio? by mutableStateOf(null)

  var scaleEnabled: Boolean by mutableStateOf(false)
  var scaleHeight: Int by mutableStateOf(720)
  var scaleFit: Fit by mutableStateOf(Fit.Contain)

  var brightness: Float by mutableStateOf(1f)
  var channelRed: Float by mutableStateOf(1f)
  var channelGreen: Float by mutableStateOf(1f)
  var channelBlue: Float by mutableStateOf(1f)
  var contrast: Float by mutableStateOf(1f)
  var saturation: Float by mutableStateOf(1f)
  var hueDegrees: Float by mutableStateOf(0f)
  var sepia: Float by mutableStateOf(0f)
  var invert: Float by mutableStateOf(0f)
  var blur: Float by mutableStateOf(0f)
  var vignette: Float by mutableStateOf(0f)
  var customMatrix: ColorMatrix by mutableStateOf(ColorMatrix.Identity)

  var tapeEnabled: Boolean by mutableStateOf(false)
  var tapeIntensity: Float by mutableStateOf(1f)
  var tapeStock: TapeStock by mutableStateOf(TapeFormat.Vhs.default)
  var tapeTracking: TapeTrackingMode by mutableStateOf(TapeTrackingMode.Events)
  var tapeSnow: Float by mutableStateOf(Tracking.DEFAULT_SNOW)
  var tapeAudioEnabled: Boolean by mutableStateOf(false)
  var tapeAudioStock: TapeStock by mutableStateOf(TapeFormat.Vhs.default)
  var filmEnabled: Boolean by mutableStateOf(false)
  var filmGrain: Float by mutableStateOf(0.5f)
  var filmHalation: Float by mutableStateOf(0.5f)
  var filmLeak: Float by mutableStateOf(0f)
  var bloomEnabled: Boolean by mutableStateOf(false)
  var bloomIntensity: Float by mutableStateOf(0.5f)
  var bloomThreshold: Float by mutableStateOf(0.7f)
  var bloomSpread: Float by mutableStateOf(0.5f)
  var crtEnabled: Boolean by mutableStateOf(false)
  var crtIntensity: Float by mutableStateOf(1f)
  val pixelLook: PixelState = PixelState()
  var lutEnabled: Boolean by mutableStateOf(false)
  var lutIntensity: Float by mutableStateOf(1f)
  val camcorderDisplay: CamcorderState = CamcorderState()

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

  var frameMode: FrameMode by mutableStateOf(FrameMode.Auto)
  var frameAspect: AspectRatio by mutableStateOf(AspectRatio.Landscape)
  var frameSize: Size by mutableStateOf(Size(1920, 1080))
  var endCardEnabled: Boolean by mutableStateOf(false)
  var endCardColor: Int by mutableStateOf(BLACK)
  var endCardSeconds: Float by mutableStateOf(2f)

  val cropRect: NormalizedRect
    get() = NormalizedRect(cropLeft, cropTop, cropRight, cropBottom)

  val cropAnchor: Anchor
    get() = Anchor(cropAnchorX, cropAnchorY)

  /**
   * Moves the rectangle crop to [rect].
   */
  fun setCropRect(rect: NormalizedRect) {
    cropLeft = rect.left
    cropTop = rect.top
    cropRight = rect.right
    cropBottom = rect.bottom
  }

  val textAnchor: Anchor
    get() = Anchor(textAnchorX, textAnchorY)

  /**
   * Moves the text overlay to [anchor].
   */
  fun setTextAnchor(anchor: Anchor) {
    textAnchorX = anchor.x
    textAnchorY = anchor.y
  }

  /**
   * Snaps the watermark to the corner and margin that bring it to [anchor] on a frame of [frame].
   */
  fun setWatermarkAnchor(
    anchor: Anchor,
    frame: Size,
  ) {
    val inset = anchor.nearestCornerInset(frame)
    watermarkCorner = inset.corner
    watermarkMargin = inset.margin
  }

  val textStyle: TextStyle
    get() =
      TextStyle(
        fontSize = textSize,
        weight = textWeight,
        color = textColor,
        backgroundColor = textPlate,
        alignment = textAlignment,
        maxWidth = textMaxWidth,
      )

  val fill: Fill
    get() =
      when (fillMode) {
        FillMode.Solid -> Fill.Solid(fillColor)
        FillMode.Blurred -> Fill.Blurred(blurRadius, blurDim)
      }

  val outputFrame: OutputFrame
    get() =
      when (frameMode) {
        FrameMode.Auto -> OutputFrame.Auto
        FrameMode.Aspect -> OutputFrame.Aspect(frameAspect)
        FrameMode.Fixed -> OutputFrame.Fixed(frameSize)
      }

  /**
   * How long the end card holds after the clip, or zero while it's off.
   */
  val endCardDuration: Duration
    get() = if (endCardEnabled) endCardSeconds.toDouble().seconds else Duration.ZERO

  val audioSpec: AudioSpec
    get() =
      when (audioMode) {
        AudioMode.Keep -> AudioSpec.Keep
        AudioMode.Volume -> AudioSpec.Volume(audioGain)
        AudioMode.Mute -> AudioSpec.Mute
        AudioMode.Remove -> AudioSpec.Remove
        AudioMode.AudioOnly -> AudioSpec.AudioOnly
      }

  /**
   * The color effects the knobs author, in the order the pipeline runs them, or an empty list while
   * every knob sits at its default. A saturation of zero is authored as the grayscale shorthand, and
   * the custom matrix goes last, after everything the sliders authored.
   */
  val colorEffects: List<EffectSpec>
    get() =
      EffectsBuilder()
        .apply {
          if (brightness != 1f) brightness(brightness)
          if (channelRed != 1f || channelGreen != 1f || channelBlue != 1f) {
            rgbAdjustment(channelRed, channelGreen, channelBlue)
          }
          if (contrast != 1f) contrast(contrast)
          when {
            saturation == 0f -> grayscale()
            saturation != 1f -> saturation(saturation)
          }
          if (hueDegrees != 0f) hueRotate(hueDegrees)
          if (sepia != 0f) sepia(sepia)
          if (invert != 0f) invert(invert)
          if (customMatrix != ColorMatrix.Identity) colorMatrix(customMatrix)
        }.build()

  /**
   * Whether any color knob is off its default.
   */
  val colorGraded: Boolean
    get() = colorEffects.isNotEmpty()

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
   * How long the edit runs, which is the trim when there is one and the whole source otherwise, plus the end card.
   */
  fun editedDuration(sourceDuration: Duration?): Duration? =
    (trimRange(sourceDuration)?.duration ?: sourceDuration)?.plus(endCardDuration)

  /**
   * The output frame's aspect after every geometry effect, so the preview can letterbox to the
   * shape the file will actually have.
   *
   * @param cropped Whether the rectangle crop and the output frame are folded in. False reports the frame before
   *   them, which is what a crop overlay measures itself against.
   */
  fun outputAspect(
    sourceAspect: Float,
    cropped: Boolean = true,
  ): Float {
    if (cropped) {
      when (frameMode) {
        FrameMode.Auto -> Unit
        FrameMode.Aspect -> return frameAspect.value
        FrameMode.Fixed -> return frameSize.width.toFloat() / frameSize.height
      }
    }
    val rotated = if (rotationDegrees == 90 || rotationDegrees == 270) 1f / sourceAspect else sourceAspect
    return when (cropMode) {
      CropMode.Off -> {
        rotated
      }
      CropMode.Aspect -> {
        cropAspect.value
      }
      CropMode.Rect -> {
        if (!cropped) return rotated
        val rect = cropRect
        if (rect.isValid) rotated * (rect.width / rect.height) else rotated
      }
    }
  }

  /**
   * Builds the value that `plan`, `export` and `preview` all take.
   *
   * @param trimmed Whether to cut the clip to [trimRange] and add the end card. False keeps the whole
   *   source alone, which is what the timeline strip renders against so it covers the same ground no
   *   matter where the trim handles sit.
   * @param cropped Whether to apply the rectangle crop and the output frame. False keeps the whole
   *   frame, which is what a crop overlay renders against, since the rectangle it authors addresses
   *   the uncropped frame.
   */
  fun composition(
    source: MediaSource,
    sourceDuration: Duration?,
    trimmed: Boolean = true,
    cropped: Boolean = true,
  ): Composition =
    compositionOf {
      clip(source) {
        if (trimmed) trimRange(sourceDuration)?.let { trim(it) }
        if (clipMuted) audio(AudioLevel.Mute)
        snapWithin(snapWithinSeconds.toDouble().seconds)
      }
      if (trimmed && endCardEnabled) solid(endCardColor, endCardDuration)
      if (cropped) frame(outputFrame)

      if (tapeAudioEnabled) audioEffects { tapeAudio { tape = tapeAudioStock } }

      effects {
        if (rotationDegrees != 0) rotate(rotationDegrees)
        if (flipHorizontal) flip(FlipAxis.Horizontal)
        if (flipVertical) flip(FlipAxis.Vertical)

        when (cropMode) {
          CropMode.Off -> Unit
          CropMode.Aspect -> crop(cropAspect, cropFit, cropAnchor)
          CropMode.Rect -> if (cropped && cropRect.isValid) crop(cropRect)
        }

        if (scaleEnabled) scale(scaleHeight, scaleFit)
        addAll(colorEffects)
        if (blur != 0f) blur(blur)
        if (vignette != 0f) vignette(strength = vignette)
        val burned = camcorderDisplay.shown && camcorderDisplay.burned
        if (tapeEnabled) {
          when (val stock = tapeStock) {
            is TapeSpeed -> {
              vhs {
                tape = stock
                tapeLook(burned)
              }
            }
            is BetaSpeed -> {
              betamax {
                tape = stock
                tapeLook(burned)
              }
            }
            is Hi8Speed -> {
              hi8 {
                tape = stock
                tapeLook(burned)
              }
            }
            else -> {
              error("No tape look plays ${stock.label}")
            }
          }
        } else if (burned) {
          camcorder(camcorderDisplay.elements)
        }
        if (bloomEnabled) bloom(bloomIntensity, bloomThreshold, bloomSpread)
        if (filmEnabled) film(filmGrain, filmHalation, leak = filmLeak)
        if (crtEnabled) crt(crtIntensity)
        if (pixelLook.enabled) pixel(pixelLook.cells.toInt(), pixelLook.levelCount, pixelLook.dither, pixelLook.palette)
        if (lutEnabled) lut(SAMPLE_LUT, lutIntensity)
        if (camcorderDisplay.shown && !camcorderDisplay.burned) camcorder(camcorderDisplay.elements)

        if (textEnabled && text.isNotBlank()) {
          textOverlay(
            text = text,
            style = textStyle,
            anchor = textAnchor,
            visibleDuring = visibleWindow(textTimed, textStartSeconds, textEndSeconds),
          )
        }

        watermarkImage?.let { image ->
          imageOverlay(
            image = image,
            corner = watermarkCorner,
            margin = watermarkMargin,
            scale = watermarkScale,
            opacity = watermarkOpacity,
            visibleDuring = visibleWindow(watermarkTimed, watermarkStartSeconds, watermarkEndSeconds),
          )
        }
      }

      audio(audioSpec)
      fill(fill)
    }

  /**
   * The format the tape look plays, the one [tapeStock] is a speed of.
   */
  val tapeFormat: TapeFormat
    get() = TapeFormat.of(tapeStock)

  /**
   * Puts the tape look and the tape audio on [format] at the speed it starts on.
   */
  fun selectTapeFormat(format: TapeFormat) {
    tapeStock = format.default
    tapeAudioStock = format.default
  }

  private fun TapeLookBuilder.tapeLook(burned: Boolean) {
    if (burned) camcorder(camcorderDisplay.elements)
    intensity = tapeIntensity
    tracking =
      when (tapeTracking) {
        TapeTrackingMode.Events -> events { snow = tapeSnow }
        TapeTrackingMode.Rolling -> rolling { snow = tapeSnow }
        TapeTrackingMode.Off -> Tracking.Off
      }
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
    cropLockAspect = null
    scaleEnabled = false
    scaleHeight = 720
    scaleFit = Fit.Contain
    brightness = 1f
    channelRed = 1f
    channelGreen = 1f
    channelBlue = 1f
    contrast = 1f
    saturation = 1f
    hueDegrees = 0f
    sepia = 0f
    tapeEnabled = false
    tapeIntensity = 1f
    tapeStock = TapeFormat.Vhs.default
    tapeTracking = TapeTrackingMode.Events
    tapeSnow = Tracking.DEFAULT_SNOW
    tapeAudioEnabled = false
    tapeAudioStock = TapeFormat.Vhs.default
    filmEnabled = false
    filmGrain = 0.5f
    filmHalation = 0.5f
    filmLeak = 0f
    bloomEnabled = false
    bloomIntensity = 0.5f
    bloomThreshold = 0.7f
    bloomSpread = 0.5f
    crtEnabled = false
    crtIntensity = 1f
    pixelLook.reset()
    lutEnabled = false
    lutIntensity = 1f
    camcorderDisplay.reset()
    invert = 0f
    blur = 0f
    vignette = 0f
    customMatrix = ColorMatrix.Identity
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
    frameMode = FrameMode.Auto
    frameAspect = AspectRatio.Landscape
    frameSize = Size(1920, 1080)
    endCardEnabled = false
    endCardColor = BLACK
    endCardSeconds = 2f
  }

  public companion object {
    public const val WHITE: Int = 0xFFFFFFFF.toInt()
    public const val BLACK: Int = 0xFF000000.toInt()
  }
}

private val SAMPLE_LUT: CubeLut by lazy { CubeLut.parse(TEAL_AND_ORANGE_CUBE) }
