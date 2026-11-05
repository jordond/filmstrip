package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media3.internal.bounds.Media3FrameBounds
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlin.time.Duration

/**
 * Lays one plan's tracks onto media3 sequences, holding what every item of them is lowered against.
 */
internal class Lowering(
  private val plan: ResolvedComposition,
  private val wrapper: EffectWrapper,
  private val bounds: Media3FrameBounds,
) {
  /**
   * The video of [track] on the output frame grid, or null when none of its clips covers a slot.
   *
   * The track waits for the slot its start lands on, and each clip runs from the slot its span opens on to the one its
   * span closes on. Every item therefore opens where the one before it ended, and every boundary sits on a slot. A clip
   * covering no slot lays nothing. The wait, and the slots of a clip with no picture of its own, hold [gapItem]s.
   */
  fun gridSequence(track: ResolvedTrack): EditedMediaItemSequence? {
    val rate = plan.output.frameRate
    val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
    val lead = outputSlotTime(outputSlotOf(track.start, rate), rate)
    if (lead > Duration.ZERO) builder.addItem(gapItem(lead, rate))

    var laid = false
    track.clips.forEach { clip ->
      val slots = outputSlotsOf(clip.span, rate)
      if (slots.isEmpty()) return@forEach
      val length = outputSlotTime(slots.last + 1, rate) - outputSlotTime(slots.first, rate)
      val still = clip.source as? MediaSource.Image
      when {
        still != null -> builder.addItem(clip.imageItem(still, length, removeAudio = true, removeVideo = false))
        clip.info.video == null -> builder.addItem(gapItem(length, rate))
        else -> builder.addItem(clip.gridItem(length, rate))
      }
      laid = true
    }
    return if (laid) builder.build() else null
  }

  /**
   * [track] laid at the exact times the plan resolved, carrying only [trackTypes].
   *
   * This is the audio of a track whose video went on the grid, and the whole of a track on the copy path. A clip with
   * nothing to contribute to an audio-only sequence, a still or a source with no sound, holds its span with silence.
   * Every gap here is media3's own, since a copy writes no frame of its own and has no fill to show through one.
   */
  fun exactSequence(
    track: ResolvedTrack,
    trackTypes: Set<Int>,
  ): EditedMediaItemSequence? {
    if (trackTypes.isEmpty()) return null
    val removeAudio = C.TRACK_TYPE_AUDIO !in trackTypes
    val removeVideo = C.TRACK_TYPE_VIDEO !in trackTypes
    val builder = EditedMediaItemSequence.Builder(trackTypes)
    if (track.start > Duration.ZERO) builder.addGap(track.start.inWholeMicroseconds)

    // A lone clip at full volume is already the format the sequence outputs, so it doesn't need mixing.
    val mixesAudio = track.clips.size > 1
    track.clips.forEach { clip ->
      val still = clip.source as? MediaSource.Image
      when {
        removeVideo && (still != null || clip.info.audio == null) -> {
          builder.addGap(clip.duration.inWholeMicroseconds)
        }
        still != null -> {
          builder.addItem(clip.imageItem(still, clip.duration, removeAudio, removeVideo))
        }
        else -> {
          builder.addItem(clip.exactItem(removeAudio, removeVideo, mixesAudio))
        }
      }
    }
    return builder.build()
  }

  /**
   * A fully transparent still held for [length], which the flatten pass paints the fill across edge to edge.
   *
   * An overlay after that pass draws over the fill. The item carries [GAP_MEDIA_ID] so a preview can tell it from a
   * clip.
   */
  private fun gapItem(
    length: Duration,
    rate: Int,
  ): EditedMediaItem {
    val mediaItem =
      MediaItem
        .Builder()
        .setMediaId(GAP_MEDIA_ID)
        .setUri(GAP_FRAME_URI)
        .setMimeType(GAP_FRAME_MIME)
        .setImageDurationMs(length.heldMilliseconds())
        .build()

    return EditedMediaItem
      .Builder(mediaItem)
      .setRemoveAudio(true)
      .setDurationUs(length.inWholeMicroseconds)
      .setFrameRate(rate)
      .build()
  }

  /**
   * A clip's video, decoded for exactly [length].
   *
   * media3 opens a clipped item on the first sample at or after its start and ends it at its clipped end or the
   * source's own end, whichever comes first. So the item is clipped from that first sample, and one that decodes for
   * longer than [length] is cut to it. One that decodes for less, because its start sat inside a frame or its slots
   * reach past the source, holds its last frame through [HeldLastFrame] until it has run for [length]. A clip whose
   * samples were never read is clipped from its own start and not held.
   */
  private fun ResolvedClip.gridItem(
    length: Duration,
    rate: Int,
  ): EditedMediaItem {
    val window = bounds[this]
    val opens = window?.first ?: start
    val cut = window?.end ?: end
    val decoded = cut - opens
    val closes = if (decoded > length) opens + length else cut
    val hold =
      window?.takeIf { decoded < length }?.let {
        HeldLastFrame(
          fromUs = (it.last - opens).inWholeMicroseconds,
          decodedUs = decoded.inWholeMicroseconds,
          lengthUs = length.inWholeMicroseconds,
        )
      }

    return EditedMediaItem
      .Builder(clipped(opens, closes, always = true))
      .setRemoveAudio(true)
      .apply {
        sourceDuration(info)
        hold?.let(::setSpeed)
      }
      // A maximum rather than a target: a higher-rate source has frames dropped, a lower-rate one is left alone.
      .setFrameRate(rate)
      .setEffects(
        Effects(emptyList(), clipVideoEffects(plan.fit, plan.fill, plan.output.size, plan.naturalSize, wrapper)),
      ).build()
  }

  /**
   * A clip over the exact window the plan resolved it to.
   */
  private fun ResolvedClip.exactItem(
    removeAudio: Boolean,
    removeVideo: Boolean,
    mixesAudio: Boolean,
  ): EditedMediaItem =
    EditedMediaItem
      .Builder(clipped(start, end))
      .setRemoveAudio(removeAudio)
      .setRemoveVideo(removeVideo)
      .apply {
        sourceDuration(info)
        setFrameRate(plan.output.frameRate)
      }.setEffects(
        Effects(
          if (removeAudio) emptyList() else audioProcessors(gain, mixesAudio),
          if (removeVideo) {
            emptyList()
          } else {
            clipVideoEffects(
              plan.fit,
              plan.fill,
              plan.output.size,
              plan.naturalSize,
              wrapper,
            )
          },
        ),
      ).build()

  // The duration media3 wants is the source's, before any clipping.
  private fun EditedMediaItem.Builder.sourceDuration(info: MediaInfo) {
    if (info.duration > Duration.ZERO) setDurationUs(info.duration.inWholeMicroseconds)
  }

  /**
   * This clip's source, windowed from [from] to [to] in whole microseconds.
   *
   * Unless [always] is set, a window that covers the whole source carries no clipping at all, since there is nothing to
   * seek past. A probed duration is whole milliseconds and the source's own end can sit past it, so an item whose
   * length has to come out exact is always clipped.
   */
  private fun ResolvedClip.clipped(
    from: Duration,
    to: Duration,
    always: Boolean = false,
  ): MediaItem =
    MediaItem
      .Builder()
      .setUri(source.toAndroidUri())
      .apply {
        if (always || from > Duration.ZERO || to < info.duration) {
          setClippingConfiguration(
            MediaItem.ClippingConfiguration
              .Builder()
              .setStartPositionUs(from.inWholeMicroseconds)
              .setEndPositionUs(to.inWholeMicroseconds)
              .setStartsAtKeyFrame(startsAtKeyFrame)
              .build(),
          )
        }
      }.build()

  /**
   * The item a still lowers to, held for [length].
   *
   * media3 chooses its image loader by MIME type, and the loader wants both a duration and a rate to
   * hold the picture at, since a still carries neither. The type is the one the probe read out of the
   * image's own header rather than a guess from the URI, because neither a cached copy of a byte
   * buffer nor a `content://` reference is guaranteed to carry a file extension.
   *
   * The length comes from the resolved clip, whose trim the plan has already collapsed into its span. An image item has
   * no samples to clip, so there is no clipping configuration here for one to reach.
   */
  private fun ResolvedClip.imageItem(
    source: MediaSource.Image,
    length: Duration,
    removeAudio: Boolean,
    removeVideo: Boolean,
  ): EditedMediaItem {
    val rate = plan.output.frameRate

    val mediaItem =
      MediaItem
        .Builder()
        .setUri(source.image.toAndroidUri(stillFormat))
        .apply { stillFormat?.let { setMimeType("$IMAGE_MIME_PREFIX$it") } }
        .setImageDurationMs(length.heldMilliseconds())
        .build()

    // A still carries no audio of its own, so removing it takes nothing away, and media3 ignores both
    // flags on an image item. They are set anyway so an audio-only export drops a photo's video
    // through the same reading that drops every other clip's.
    return EditedMediaItem
      .Builder(mediaItem)
      .setRemoveAudio(removeAudio)
      .setRemoveVideo(removeVideo)
      .setDurationUs(length.inWholeMicroseconds)
      .setFrameRate(rate)
      .setEffects(
        Effects(
          emptyList(),
          if (removeVideo) {
            emptyList()
          } else {
            clipVideoEffects(
              plan.fit,
              plan.fill,
              plan.output.size,
              plan.naturalSize,
              wrapper,
            )
          },
        ),
      ).build()
  }
}

/**
 * This length in the whole milliseconds media3's own image duration is counted in.
 *
 * media3 takes that field in milliseconds and rejects a zero, which is its limit and not the plan's. A length that is
 * not a whole number of them rounds to the nearest and is clamped to at least one. The field routes the item to the
 * image loader and is how long a player holds the picture. The exact length rides on the edited item's duration, which
 * is what an export lays the picture and the item after it out by, so the rounding never moves the next item.
 */
private fun Duration.heldMilliseconds(): Long =
  ((inWholeMicroseconds + MICROS_PER_MILLISECOND / 2) / MICROS_PER_MILLISECOND).coerceAtLeast(1)

// The still format the probe read out of the image's header, or null when it named none.
private val ResolvedClip.stillFormat: String?
  get() =
    info.video
      ?.codec
      ?.name
      ?.lowercase()
      ?.takeIf { it.isNotBlank() }

/**
 * The media id a gap in the video grid carries, which a preview reads to leave it out of the clips a frame reader can
 * draw.
 */
internal const val GAP_MEDIA_ID = "filmstrip:gap"

/**
 * Whether this item holds a gap rather than a clip, either one media3 laid itself or the transparent still the video
 * grid lays in its place.
 */
internal val EditedMediaItem.isGap: Boolean
  get() = mediaItem.localConfiguration == null || mediaItem.mediaId == GAP_MEDIA_ID

// A 16 by 16 PNG with every pixel at zero alpha. media3 reads a data URI through its default data source.
private const val GAP_FRAME_URI =
  "data:image/png;base64," +
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAEklEQVR42mNgGAWjYBSMAggAAAQQAAGvRYgsAAAAAElFTkSuQmCC"

// What media3 reads an item's MIME type for, which is only ever whether it starts with this.
private const val IMAGE_MIME_PREFIX = "image/"

private const val GAP_FRAME_MIME = "${IMAGE_MIME_PREFIX}png"

private const val MICROS_PER_MILLISECOND = 1000L
