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
import dev.jordond.filmstrip.edit.Track
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
 * The output frame's aspect after every geometry effect, so the preview can letterbox to the
 * shape the file will actually have.
 *
 * @param cropped Whether the rectangle crop and the output frame are folded in. False reports the frame before
 *   them, which is what a crop overlay measures itself against.
 */
internal fun EditState.outputAspect(
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
  val rotated = if (transform.sideways) 1f / sourceAspect else sourceAspect
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
 * Builds the value that `plan`, `export` and `preview` all take, over every clip of [sequence].
 *
 * @param cropped Whether to apply the rectangle crop and the output frame. False keeps the whole
 *   frame, which is what a crop overlay renders against, since the rectangle it authors addresses
 *   the uncropped frame.
 * @param unpanned The clip whose pan is left out, or null to keep them all. The rectangles of a pan are dragged
 *   over that clip's still frame.
 */
internal fun EditState.composition(
  sequence: Sequence,
  cropped: Boolean = true,
  unpanned: ClipId? = null,
): Composition =
  composition(
    clips = sequence.playable.map { it.clip(panned = it.id != unpanned) },
    cropped = cropped,
    tracks = sequence.tracks.mapNotNull { it.track() },
  )

/**
 * The edit over [clip] alone, whole, untrimmed and at the pace it was shot.
 *
 * Built for a timeline strip that lays one clip's source out end to end and draws the trim window over it
 * rather than following it, so a time on the strip is a time in the source whatever speed the clip plays at.
 */
internal fun EditState.clipComposition(clip: ClipEdit): Composition =
  composition(listOf(clip.clip(trimmed = false).withSpeed(1.0)), cropped = true)

private fun EditState.composition(
  clips: List<Clip>,
  cropped: Boolean,
  tracks: List<Track> = emptyList(),
): Composition =
  compositionOf {
    clips.forEach { addClip(it) }
    tracks.forEach { addTrack(it) }
    if (cropped) frame(outputFrame)

    if (tapeAudioEnabled) {
      audioEffects {
        tapeAudio {
          tape = tapeAudioStock
          intensity = tapeAudioIntensity
          hiss = tapeAudioHiss
          bandLimit = tapeAudioBandLimit
          wow = Wobble(tapeAudioWow)
          flutter = Wobble(tapeAudioFlutter)
          mono = tapeAudioMono
        }
      }
    }

    effects {
      addAll(transform.effects)

      when (cropMode) {
        CropMode.Off -> Unit
        CropMode.Aspect -> crop(cropAspect, cropFit, cropAnchor)
        CropMode.Rect -> if (cropped && cropRect.isValid) crop(cropRect)
      }

      if (scaleEnabled) scale(scaleHeight, scaleFit)
      addAll(grade.effects)
      if (blur != 0f) blur(blur)
      if (vignette != 0f) vignette(vignette, vignetteRadius, vignetteSoftness)
      val burned = camcorderDisplay.shown && camcorderDisplay.burned
      if (tapeEnabled) {
        when (val stock = tapeStock) {
          is TapeSpeed -> {
            vhs {
              tape = stock
              tapeLook(this@composition, burned)
            }
          }
          is BetaSpeed -> {
            betamax {
              tape = stock
              tapeLook(this@composition, burned)
            }
          }
          is Hi8Speed -> {
            hi8 {
              tape = stock
              tapeLook(this@composition, burned)
            }
          }
          else -> {
            error("No tape look plays ${stock.label}")
          }
        }
      } else if (burned) {
        camcorder(camcorderDisplay.elements(tapeStock))
      }
      if (bloomEnabled) bloom(bloomIntensity, bloomThreshold, bloomSpread)
      if (filmEnabled) film(filmGrain, filmHalation, filmWeave, filmFlicker, filmDust, filmGate, filmLeak)
      if (nightVisionEnabled) nightVision(nightVisionIntensity)
      if (glitchEnabled) glitch(glitchIntensity, glitchBlocks)
      if (crtEnabled) crt(crtIntensity)
      if (pixelLook.enabled) pixel(pixelLook.cells.toInt(), pixelLook.levelCount, pixelLook.dither, pixelLook.palette)
      if (lutEnabled) lut(SAMPLE_LUT, lutIntensity)
      if (camcorderDisplay.shown && !camcorderDisplay.burned) camcorder(camcorderDisplay.elements(tapeStock))

      if (textEnabled && text.isNotBlank()) {
        textOverlay(
          text = text,
          style = textStyle,
          anchor = textAnchor,
          visibleDuring = visibleWindow(textTimed, textStartSeconds, textEndSeconds),
          animation = textMotion.animation,
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
          animation = watermarkMotion.animation,
          sheet = watermarkSheet,
        )
      }
    }

    audio(audioSpec)
    fill(fill)
  }

private fun TapeLookBuilder.tapeLook(
  edit: EditState,
  burned: Boolean,
) = with(edit) {
  if (burned) camcorder(camcorderDisplay.elements(tapeStock))
  intensity = tapeIntensity
  clock = tapeClock
  if (tapeSettleEnabled) settle(tapeSettleFrom, tapeSettleSeconds.asSeconds())
  tracking =
    when (tapeTracking) {
      TapeTrackingMode.Events -> events { snow = tapeSnow }
      TapeTrackingMode.Rolling -> rolling { snow = tapeSnow }
      TapeTrackingMode.Off -> Tracking.Off
    }
}

private val SAMPLE_LUT: CubeLut by lazy { CubeLut.parse(TEAL_AND_ORANGE_CUBE) }
