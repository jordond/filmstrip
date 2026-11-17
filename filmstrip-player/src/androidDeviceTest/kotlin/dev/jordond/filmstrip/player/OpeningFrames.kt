package dev.jordond.filmstrip.player

import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE
import kotlin.time.Duration

/**
 * The software H.264 encoder the encoder bounds in these suites were measured on.
 */
internal const val MEASURED_ENCODER = "c2.android.avc.encoder"

/**
 * The video encoder an export of the fixture runs on, the one media3's default encoder factory makes for it.
 *
 * Every export asks that factory for its video encoder, so asking it here for the fixture's H.264 format at
 * [PREVIEW_PARITY_EXPORT_BITRATE] makes the same one. It is released as soon as its name is read.
 */
internal val exportVideoEncoder: String by lazy {
  val format =
    Format
      .Builder()
      .setSampleMimeType(MimeTypes.VIDEO_H264)
      .setWidth(FIXTURE_FRAME.width)
      .setHeight(FIXTURE_FRAME.height)
      .setFrameRate(FIXTURE_FRAME_RATE.toFloat())
      .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
      .build()
  val settings =
    VideoEncoderSettings
      .Builder()
      .setBitrate(PREVIEW_PARITY_EXPORT_BITRATE.bitsPerSecond.toInt())
      .build()
  val encoder =
    DefaultEncoderFactory
      .Builder(contractContext())
      .setRequestedVideoEncoderSettings(settings)
      .build()
      .createForVideoEncoding(format, null)
  try {
    encoder.name
  } finally {
    encoder.release()
  }
}

/**
 * Whether an export runs on [MEASURED_ENCODER], so the bounds measured on it apply.
 */
internal val onMeasuredEncoder: Boolean get() = exportVideoEncoder == MEASURED_ENCODER

/**
 * How many frames open a file that [MEASURED_ENCODER] writes further off the preview than the rest.
 *
 * Frames 0 to 6 of any file sit off under every setting and bitrate media3 offers, measured three runs each on API 30
 * and 36, and frame 7 is settled. That follows the frame's place in the file, at any speed. A surface on the API 36
 * emulator reads frame 6 settled already, so the count is the wider of the two measures. Any other encoder's opening
 * isn't measured, so every frame it writes is held as an opening one.
 */
internal val openingFrames: Long get() = if (onMeasuredEncoder) MEASURED_OPENING_FRAMES else Long.MAX_VALUE

/**
 * Whether [MEASURED_ENCODER] writes [slot] among the frames it opens with, counted from the start of the file and from
 * each of [cuts], the slots where the edit goes on to another clip.
 *
 * On any other encoder no slot is, since its opening is not measured. A suite that tells the slot shown from its
 * neighbours by which export is nearest asks this, so an unmeasured encoder is held to the settled floors on every
 * slot and not let off them on every slot.
 */
internal fun isMeasuredOpeningSlot(
  slot: Long,
  cuts: List<Long> = emptyList(),
): Boolean = onMeasuredEncoder && (listOf(0L) + cuts).any { slot - it in 0 until MEASURED_OPENING_FRAMES }

/**
 * The SSIM [MEASURED_ENCODER] was measured to reach on [slot] where it coarsens a frame again after its opening,
 * counted from the start of the file, or null for a slot it does not and on any other encoder.
 */
internal fun measuredCoarseSsim(slot: Long): Double? = MEASURED_COARSE_SSIM[slot].takeIf { onMeasuredEncoder }

/**
 * The PSNR in dB and the SSIM a surface holds to against the export of the [slot] it shows.
 *
 * A slot [isMeasuredOpeningSlot] names holds [SURFACE_OPENING_MIN_PSNR_DB] and the surface's own opening SSIM floor,
 * or the settled floors where those are lower. A slot [measuredCoarseSsim] names holds [settledPsnrDb] and the SSIM
 * measured on it less [COARSE_SSIM_MARGIN], or [settledSsim] where that is lower. Every other slot holds
 * [settledPsnrDb] and [settledSsim].
 */
internal fun surfaceFloorsAt(
  slot: Long,
  cuts: List<Long>,
  settledPsnrDb: Double,
  settledSsim: Double,
): Pair<Double, Double> {
  if (isMeasuredOpeningSlot(slot, cuts)) {
    return minOf(SURFACE_OPENING_MIN_PSNR_DB, settledPsnrDb) to minOf(SURFACE_OPENING_MIN_SSIM, settledSsim)
  }
  val coarse = measuredCoarseSsim(slot) ?: return settledPsnrDb to settledSsim
  return settledPsnrDb to minOf(coarse - COARSE_SSIM_MARGIN, settledSsim)
}

/**
 * The SSIM an export's frame at [slot] holds to on luma against the preview.
 *
 * A slot past [openingFrames] holds [pastOpening]. A slot inside them holds the opening floor of a file read back,
 * or [pastOpening] if that is lower.
 */
internal fun encodedMinSsimAt(
  slot: Long,
  pastOpening: Double,
): Double = if (slot < openingFrames) minOf(OPENING_MIN_SSIM, pastOpening) else pastOpening

/**
 * The SSIM an export's frame shown at [at] holds to against the preview, the bound [encodedMinSsimAt] gives its slot.
 */
internal fun encodedMinSsimAtTime(
  at: Duration,
  pastOpening: Double,
): Double = encodedMinSsimAt(outputSlotOf(at, FIXTURE_FRAME_RATE), pastOpening)

/**
 * The frames [MEASURED_ENCODER] opens a file with, the ones the opening bounds were measured over.
 */
internal const val MEASURED_OPENING_FRAMES: Long = 7L

// The SSIM c2.android.avc.encoder reaches on the frames it writes coarser a second time, by their place in the file.
// Against a surface copied on the API 36 emulator, slots 12, 13 and 14 of an edit with a still lead read 0.9773, 0.9589
// and 0.9803 at 40.7, 39.5 and 41.7 dB, where slots 6 to 11 read 0.987 and 43 dB or more. They read the same with the
// cut to the next clip on slot 15 and on slot 21. The surface there is the one a seek to the slot lands, byte for byte,
// and the difference is spread evenly over the picture and the channels. Slot 14 clears the settled floor and is not
// listed. Slot 13 of a panned clip reads 0.9805. The slots either side of the one shown read 0.917 to 0.934 and 35.2 to
// 36.3 dB against slot 13's export, so the floor is not what tells them apart: the export of the slot shown reads 3.2
// dB over the nearest of them, and a suite holds it to being the nearest.
private val MEASURED_COARSE_SSIM = mapOf(12L to 0.9773, 13L to 0.9589)

// How far under the SSIM measured on a coarse slot its floor sits, the margin the opening floor keeps too.
private const val COARSE_SSIM_MARGIN = 0.009

// c2.android.avc.encoder's limit over its opening frames in a file read back, 45 to 49 dB on luma: frame 0 of a moving
// source reads 0.984 against its own preview on API 36, the same bytes at speed 1 and at 32, where a wrong frame reads
// 0.844 to 0.857. Another encoder holds every frame to it, 0.093 over the most a wrong frame reads.
private const val OPENING_MIN_SSIM = 0.95

// c2.android.avc.encoder's limit over its opening frames against a surface copied on the API 36 emulator, which reads
// lower than a file read back does: slots 0 to 5 read 38.1 to 41.4 dB and 0.9492 to 0.9736, slot 1 of a slowed clip
// 33.65 dB and 0.953, and slot 15 of an edit that cuts to a second clip there 42.2 dB and 0.9701, where a slot from 6
// on reads 43 dB and 0.988 or more. The floors sit 3.65 dB and 0.009 under the lowest of those. A wrong frame of a
// moving source reads 0.844 to 0.857, which the SSIM floor is 0.083 over. The slot beside the one shown reads up to 35
// dB on a panned clip, over the PSNR floor, so a suite that has to tell neighbours apart holds the export of the slot
// shown to being the nearest of those around it.
internal const val SURFACE_OPENING_MIN_PSNR_DB = 30.0
private const val SURFACE_OPENING_MIN_SSIM = 0.94
