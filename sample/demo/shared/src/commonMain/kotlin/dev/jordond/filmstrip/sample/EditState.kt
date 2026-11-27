@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.OutputFrame
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
import dev.jordond.filmstrip.effects.filters.glitch.glitch
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.hi8.hi8
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.lut
import dev.jordond.filmstrip.effects.filters.nightvision.nightVision
import dev.jordond.filmstrip.effects.filters.pixel.pixel
import dev.jordond.filmstrip.effects.filters.tape.TapeLookBuilder
import dev.jordond.filmstrip.effects.filters.tape.TapeStock
import dev.jordond.filmstrip.effects.filters.tape.Wobble
import dev.jordond.filmstrip.effects.filters.tape.tapeAudio
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.flip
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.effects.geometry.scale
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
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
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every knob the editor exposes, held as compose state and lowered onto a [Composition] on
 * demand.
 *
 * One field per parameter of the built-in effect catalogue, so nothing in `filmstrip-effects` is
 * reachable from the library but unreachable from the sample.
 */
@Stable
public class EditState {
  /**
   * The turn and the mirroring every clip gets.
   */
  val transform: TransformState = TransformState()

  /**
   * The colour grade every clip gets.
   */
  val grade: GradeState = GradeState()

  /**
   * Which of the two the transform and adjust panels are editing.
   */
  var scope: EditScope by mutableStateOf(EditScope.Edit)

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

  var blur: Float by mutableStateOf(0f)
  var vignette: Float by mutableStateOf(0f)
  var vignetteRadius: Float by mutableStateOf(VIGNETTE_RADIUS)
  var vignetteSoftness: Float by mutableStateOf(VIGNETTE_SOFTNESS)

  var tapeEnabled: Boolean by mutableStateOf(false)
  var tapeIntensity: Float by mutableStateOf(1f)
  var tapeStock: TapeStock by mutableStateOf(TapeFormat.Vhs.default)
  var tapeTracking: TapeTrackingMode by mutableStateOf(TapeTrackingMode.Events)
  var tapeSnow: Float by mutableStateOf(Tracking.DEFAULT_SNOW)
  var tapeSettleEnabled: Boolean by mutableStateOf(false)
  var tapeSettleFrom: Float by mutableStateOf(1f)
  var tapeSettleSeconds: Float by mutableStateOf(2f)
  var tapeClock: TapeClock by mutableStateOf(TapeClock.Composition)
  var tapeAudioEnabled: Boolean by mutableStateOf(false)
  var tapeAudioStock: TapeStock by mutableStateOf(TapeFormat.Vhs.default)
  var tapeAudioIntensity: Float by mutableStateOf(1f)
  var tapeAudioHiss: Float by mutableStateOf(1f)
  var tapeAudioBandLimit: Float by mutableStateOf(1f)
  var tapeAudioWow: Float by mutableStateOf(1f)
  var tapeAudioFlutter: Float by mutableStateOf(1f)
  var tapeAudioMono: Boolean by mutableStateOf(true)
  var filmEnabled: Boolean by mutableStateOf(false)
  var filmGrain: Float by mutableStateOf(0.5f)
  var filmHalation: Float by mutableStateOf(0.5f)
  var filmLeak: Float by mutableStateOf(0f)
  var filmWeave: Float by mutableStateOf(0f)
  var filmFlicker: Float by mutableStateOf(0f)
  var filmDust: Float by mutableStateOf(0f)
  var filmGate: Float by mutableStateOf(0f)
  var filmBreath: Float by mutableStateOf(0f)
  var filmFade: Float by mutableStateOf(0f)
  var nightVisionEnabled: Boolean by mutableStateOf(false)
  var nightVisionIntensity: Float by mutableStateOf(1f)
  var glitchEnabled: Boolean by mutableStateOf(false)
  var glitchIntensity: Float by mutableStateOf(1f)
  var glitchBlocks: Float by mutableStateOf(0f)
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

  val stories: StoryOverlays = StoryOverlays()

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
  val textMotion: OverlayMotionState = OverlayMotionState()

  var watermarkImage: ImageSource? by mutableStateOf(null)
  var watermarkLabel: String by mutableStateOf("")
  var watermarkCorner: Corner by mutableStateOf(Corner.BottomEnd)
  var watermarkMargin: Float by mutableStateOf(0.04f)
  var watermarkScale: Float by mutableStateOf(0.2f)
  var watermarkOpacity: Float by mutableStateOf(1f)
  var watermarkTimed: Boolean by mutableStateOf(false)
  var watermarkStartSeconds: Float by mutableStateOf(0f)
  var watermarkEndSeconds: Float by mutableStateOf(3f)
  val watermarkMotion: OverlayMotionState = OverlayMotionState()
  var watermarkSheetEnabled: Boolean by mutableStateOf(false)
  var watermarkSheetColumns: Int by mutableStateOf(4)
  var watermarkSheetRows: Int by mutableStateOf(1)
  var watermarkSheetFrameMillis: Float by mutableStateOf(80f)
  var watermarkSheetOnce: Boolean by mutableStateOf(false)

  var audioMode: AudioMode by mutableStateOf(AudioMode.Keep)
  var audioGain: Float by mutableStateOf(1f)

  var fillMode: FillMode by mutableStateOf(FillMode.Solid)
  var fillColor: Int by mutableStateOf(BLACK)
  var blurRadius: Float by mutableStateOf(0.04f)
  var blurDim: Float by mutableStateOf(0f)

  var frameMode: FrameMode by mutableStateOf(FrameMode.Auto)
  var frameAspect: AspectRatio by mutableStateOf(AspectRatio.Landscape)
  var frameSize: Size by mutableStateOf(Size(1920, 1080))

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
   * The grid the watermark picture is cut into, or null while it plays as one picture.
   */
  val watermarkSheet: SpriteSheet?
    get() =
      if (watermarkSheetEnabled) {
        SpriteSheet(
          columns = watermarkSheetColumns,
          rows = watermarkSheetRows,
          frameDuration = watermarkSheetFrameMillis.roundToInt().milliseconds,
          plays = if (watermarkSheetOnce) FramePlays.Once else FramePlays.Forever,
        )
      } else {
        null
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

  /**
   * Puts every knob back where it started, for the editor's reset action.
   */
  fun reset() {
    transform.reset()
    grade.reset()
    scope = EditScope.Edit
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
    tapeEnabled = false
    tapeIntensity = 1f
    tapeStock = TapeFormat.Vhs.default
    tapeTracking = TapeTrackingMode.Events
    tapeSnow = Tracking.DEFAULT_SNOW
    tapeSettleEnabled = false
    tapeSettleFrom = 1f
    tapeSettleSeconds = 2f
    tapeClock = TapeClock.Composition
    tapeAudioEnabled = false
    tapeAudioStock = TapeFormat.Vhs.default
    tapeAudioIntensity = 1f
    tapeAudioHiss = 1f
    tapeAudioBandLimit = 1f
    tapeAudioWow = 1f
    tapeAudioFlutter = 1f
    tapeAudioMono = true
    filmEnabled = false
    filmGrain = 0.5f
    filmHalation = 0.5f
    filmLeak = 0f
    filmWeave = 0f
    filmFlicker = 0f
    filmDust = 0f
    filmGate = 0f
    filmBreath = 0f
    filmFade = 0f
    nightVisionEnabled = false
    nightVisionIntensity = 1f
    glitchEnabled = false
    glitchIntensity = 1f
    glitchBlocks = 0f
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
    stories.clear()
    blur = 0f
    vignette = 0f
    vignetteRadius = VIGNETTE_RADIUS
    vignetteSoftness = VIGNETTE_SOFTNESS
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
    textStartSeconds = 0f
    textEndSeconds = 3f
    textMotion.reset()
    watermarkImage = null
    watermarkLabel = ""
    watermarkCorner = Corner.BottomEnd
    watermarkMargin = 0.04f
    watermarkScale = 0.2f
    watermarkOpacity = 1f
    watermarkTimed = false
    watermarkStartSeconds = 0f
    watermarkEndSeconds = 3f
    watermarkMotion.reset()
    watermarkSheetEnabled = false
    watermarkSheetColumns = 4
    watermarkSheetRows = 1
    watermarkSheetFrameMillis = 80f
    watermarkSheetOnce = false
    audioMode = AudioMode.Keep
    audioGain = 1f
    fillMode = FillMode.Solid
    fillColor = BLACK
    blurRadius = 0.04f
    blurDim = 0f
    frameMode = FrameMode.Auto
    frameAspect = AspectRatio.Landscape
    frameSize = Size(1920, 1080)
  }

  public companion object {
    public const val WHITE: Int = 0xFFFFFFFF.toInt()
    public const val BLACK: Int = 0xFF000000.toInt()

    /**
     * The radius a vignette has until its slider moves. The library keeps its own default private, so this repeats
     * it and a test holds the two together.
     */
    public const val VIGNETTE_RADIUS: Float = 0.75f

    /**
     * The softness a vignette has until its slider moves, repeated from the library the way [VIGNETTE_RADIUS] is.
     * A test holds the two together.
     */
    public const val VIGNETTE_SOFTNESS: Float = 0.45f
  }
}
