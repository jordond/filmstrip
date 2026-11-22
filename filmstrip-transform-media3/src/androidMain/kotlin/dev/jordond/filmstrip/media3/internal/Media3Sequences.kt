package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceLengthOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media3.internal.bounds.AudioPreroll
import dev.jordond.filmstrip.media3.internal.bounds.ChosenTracks
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.bounds.PictureGrade
import dev.jordond.filmstrip.media3.internal.fill.SolidPaint
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.videoSlots
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Lays one plan's tracks onto media3 sequences, holding what every item of them is lowered against.
 *
 * With [ownsAudioSpeed] set, a clip's audio outside a copy opens on its trim start and runs at its speed through
 * filmstrip's own [clipAudioSpeed]. Without it media3 changes the speed, which is what a player needs, since it refuses
 * a processor that changes an item's length.
 *
 * With [exactLengths] set, an export lays a still or a gap by its exact length, where a player holds it for whole
 * milliseconds and lays the clips after it by that.
 *
 * With [liveGain] set, every clip's level sits on a [ClipGainProcessor] that a standing graph can hand a new curve.
 *
 * With [writesEverySlot] set, a clip's video opens on a [SlotRepeat] so every slot it covers is drawn. Without it a
 * sample is drawn once, on the first slot that shows it. Each of them reads [slotPin].
 *
 * With [drawsFrames] set, the lowering is one that draws a copy's frames itself, as a preview does, so a copied SD clip
 * opens on [SdPrimariesHead]. Without it a copy's video item carries no effect and its samples are written as they are.
 */
internal class Lowering(
  private val plan: ResolvedComposition,
  private val wrapper: EffectWrapper,
  private val bounds: Media3ClipReads,
  private val ownsAudioSpeed: Boolean,
  private val exactLengths: Boolean,
  private val liveGain: Boolean,
  private val writesEverySlot: Boolean = false,
  private val slotPin: SlotPin? = null,
  private val drawsFrames: Boolean = false,
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
    var at = if (lead > Duration.ZERO) lead.laidBy() else lead

    var laid = false
    track.clips.forEach { clip ->
      val slots = outputSlotsOf(clip.span, rate)
      if (slots.isEmpty()) return@forEach
      val length = outputSlotTime(slots.last + 1, rate) - outputSlotTime(slots.first, rate)
      val picture = clip.picture()
      when {
        picture != null -> {
          builder.addItem(clip.pictureItem(picture, length, removeAudio = true, removeVideo = false))
          at += length.laidBy()
        }
        clip.info.video == null -> {
          builder.addItem(gapItem(length, rate))
          at += length.laidBy()
        }
        else -> {
          builder.addItem(clip.gridItem(length, rate, at))
          at += length
        }
      }
      laid = true
    }
    return if (laid) builder.build() else null
  }

  /**
   * Where the item after a still or a gap this long starts, which a [SlotRepeat] keys its frames by.
   *
   * An export lays by the exact length, which is [exactLengths]. A player holds the item for [heldMilliseconds], so
   * many of them in a row put the clips after them off the exact lengths.
   */
  private fun Duration.laidBy(): Duration = if (exactLengths) this else heldMilliseconds().milliseconds

  /**
   * [track] laid at the exact times the plan resolved, carrying only [trackTypes].
   *
   * This is the audio of a track whose video went on the grid, and each of a copied track's kinds. A clip with
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
      val picture = clip.picture()
      when {
        removeVideo && (picture != null || clip.info.audio == null) -> {
          builder.addGap(clip.outputDuration.inWholeMicroseconds)
        }
        picture != null -> {
          builder.addItem(clip.pictureItem(picture, clip.outputDuration, removeAudio, removeVideo))
        }
        else -> {
          builder.addItem(clip.exactItem(removeAudio, removeVideo, mixesAudio))
        }
      }
    }
    return builder.build()
  }

  /**
   * A fully transparent still held for [length] and stretched over the output frame, which the flatten pass paints the
   * fill across edge to edge.
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
      .setEffects(Effects(emptyList(), listOf(wrapper.wrap(gapPlacement))))
      .build()
  }

  // Every clip reaches the composition's chain already placed in the output frame, so the gap's still is stretched
  // over the whole of it the same way.
  private val gapPlacement = PlacedFrame(NormalizedRect.Full, plan.output.size)

  /**
   * A clip's video, written for exactly [length] with each of its slots showing the frame nearest that slot's source
   * time.
   *
   * With the samples its window decodes read, the item is laid by [ShownFrames] and played through a [SlotPace], so
   * every sample a slot shows is written on the first slot showing it and media3's frame rate cap drops the rest. With
   * [writesEverySlot] set a [SlotRepeat] then writes it again on each later slot that shows it. A clip whose samples
   * were never read is clipped from its own start, cut to what its speed plays in [length], and run at that speed.
   * The item starts [startsAt] into the timeline.
   */
  private fun ResolvedClip.gridItem(
    length: Duration,
    rate: Int,
    startsAt: Duration,
  ): EditedMediaItem {
    val shown = bounds[this]?.let { ShownFrames.of(this, it, rate) }
    val played = sourceLengthOf(length, speed)
    val opens = shown?.opens ?: start
    val closes = shown?.closes ?: if (end - start > played) start + played else end
    // media3's limit: with no samples read there is no sample to name a slot for, so the item is cut from its own start
    // and run at its speed, and no frame is written on a slot of its own.
    val pace = if (shown != null) SlotPace(shown, speed) else atSpeed(closes - opens, length)

    return EditedMediaItem
      .Builder(clipped(opens, closes, always = true))
      .setRemoveAudio(true)
      .apply {
        sourceDuration(info)
        pace?.let { setSpeed(SpeedParameters(it, true)) }
      }
      // A maximum rather than a target: a higher-rate source has frames dropped, a lower-rate one is left alone.
      .setFrameRate(rate)
      .setEffects(
        Effects(
          emptyList(),
          listOfNotNull(
            shown
              ?.takeIf {
                writesEverySlot && it.repeatsUs.isNotEmpty()
              }?.let {
                wrapper.wrap(
                  SlotRepeat(it.repeatsUs, startsAt.inWholeMicroseconds, slotRepeatToleranceUs(rate), slotPin),
                )
              },
          ) +
            clipVideoEffects(plan.fill, plan.output.size, wrapper, bounds.sdPrimariesOf(this), plan.hdrTransfer),
        ),
      ).build()
  }

  /**
   * A clip over the exact window the plan resolved it to, with its audio pinned to its span when it runs at a speed.
   *
   * When a copy's video ends before its source does, it ends on the plan's grid instead, so it writes the prefix
   * [dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf] counts. The copy's audio rides in a sequence of its
   * own and ends on the clip's end.
   */
  private fun ResolvedClip.exactItem(
    removeAudio: Boolean,
    removeVideo: Boolean,
    mixesAudio: Boolean,
  ): EditedMediaItem {
    if (ownsAudioSpeed && plan.path != ExportPath.Copy && removeVideo && !removeAudio) {
      return ownedAudioItem(mixesAudio)
    }
    return EditedMediaItem
      .Builder(clipped(start, copyEnd(removeVideo), always = speed != 1.0))
      .setRemoveAudio(removeAudio)
      .setRemoveVideo(removeVideo)
      .apply {
        sourceDuration(info)
        setFrameRate(plan.output.frameRate)
        atSpeed(sourceDuration, outputDuration)?.let { setSpeed(SpeedParameters(it, true)) }
      }.setEffects(
        Effects(
          if (removeAudio) {
            emptyList()
          } else {
            listOfNotNull(AudioLengthPin(outputDuration, reportsLength = false).takeIf { speed != 1.0 }) +
              clipAudioProcessors(mixesAudio, liveGain)
          },
          // A copy writes the source's own samples, so a clip carrying video here has no frame to place.
          if (removeVideo) emptyList() else listOfNotNull(drawnSdHead()) + effects.toMedia3Effects().map(wrapper::wrap),
        ),
      ).build()
  }

  // Ahead of everything the wrapper sees, as on the grid.
  private fun ResolvedClip.drawnSdHead(): SdPrimariesHead? =
    if (drawsFrames) bounds.sdPrimariesOf(this)?.let(::SdPrimariesHead) else null

  /**
   * A clip's audio clipped from the access unit before the one holding its trim start, which media3 then decodes whole,
   * and run through [clipAudioSpeed] from the trim start for exactly its span.
   *
   * A clip whose unit was never read is clipped from its trim start itself.
   *
   * The source's units are timed with its encoder delay included, so the item is clipped the delay past the clip's end
   * and drops from where [AudioPreroll.opens] puts its first decoded frame up to the trim start.
   */
  private fun ResolvedClip.ownedAudioItem(mixesAudio: Boolean): EditedMediaItem {
    val preroll = bounds.audioPrerollOf(this)
    val opens = preroll?.opens ?: start
    return EditedMediaItem
      .Builder(clipped(preroll?.unit ?: start, end + (preroll?.encoderDelay ?: Duration.ZERO), always = true))
      .setRemoveVideo(true)
      .apply { sourceDuration(info) }
      .setFrameRate(plan.output.frameRate)
      .setEffects(
        Effects(
          clipAudioSpeed(start - opens, speed, outputDuration, clipAudioProcessors(mixesAudio, liveGain)),
          emptyList(),
        ),
      ).build()
  }

  // Where a copy carrying this clip's video ends, which is the end of the plan's grid when the window ends before the
  // source does. Every other item, a copy's audio included, ends on the clip's own end.
  private fun ResolvedClip.copyEnd(removeVideo: Boolean): Duration =
    if (plan.path == ExportPath.Copy && !removeVideo && info.video != null && end < info.duration) {
      start + outputSlotTime(plan.videoSlots, plan.output.frameRate)
    } else {
      end
    }

  // What runs this clip at its speed, or null at a speed of one, which is media3's own default.
  private fun ResolvedClip.atSpeed(
    decoded: Duration,
    length: Duration,
  ): ClipPace? =
    if (speed == 1.0) {
      null
    } else {
      ConstantClipSpeed(speed, decoded.inWholeMicroseconds, length.inWholeMicroseconds)
    }

  // The duration media3 wants is the source's, before any clipping.
  private fun EditedMediaItem.Builder.sourceDuration(info: MediaInfo) {
    if (info.duration > Duration.ZERO) setDurationUs(info.duration.inWholeMicroseconds)
  }

  /**
   * This clip's source, windowed from [from] to [to] in whole microseconds and tagged with the [ChosenTracks] it plays.
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
      .setTag(ChosenTracks(audioTrack?.ordinal))
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
   * The picture this clip holds for its span, or null when it is a clip with samples of its own.
   *
   * A still is read from where its source names, typed by the format the probe read out of the image's own header
   * rather than a guess from the URI, because neither a cached copy of a byte buffer nor a `content://` reference is
   * guaranteed to carry a file extension. A solid is a PNG of its colour at the size the plan laid it at.
   */
  private fun ResolvedClip.picture(): HeldPicture? =
    when (val source = source) {
      is MediaSource.Image -> {
        HeldPicture(source.image.toAndroidUri(stillFormat), stillFormat?.let { "$IMAGE_MIME_PREFIX$it" })
      }
      is MediaSource.Solid -> {
        HeldPicture(solidPngUri(source.color, checkNotNull(info.video).displaySize), PNG_MIME)
      }
      is MediaSource.Path, is MediaSource.Uri, is MediaSource.Bytes -> {
        null
      }
    }

  /**
   * The item a held [picture] lowers to, held for [length].
   *
   * media3 chooses its image loader by MIME type, and the loader wants both a duration and a rate to hold the picture
   * at, since a picture carries neither.
   *
   * The length comes from the resolved clip, whose trim the plan has already collapsed into its span. An image item has
   * no samples to clip, so there is no clipping configuration here for one to reach.
   *
   * A solid on an HDR grade carries that grade as a [PictureGrade], so it can open the export without turning it SDR.
   */
  private fun ResolvedClip.pictureItem(
    picture: HeldPicture,
    length: Duration,
    removeAudio: Boolean,
    removeVideo: Boolean,
  ): EditedMediaItem {
    val rate = plan.output.frameRate

    val mediaItem =
      MediaItem
        .Builder()
        .setUri(picture.uri)
        .apply { picture.mimeType?.let { setMimeType(it) } }
        .setImageDurationMs(length.heldMilliseconds())
        .apply { plan.hdrTransfer?.takeIf { source is MediaSource.Solid }?.let { setTag(PictureGrade(it)) } }
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
              plan.fill,
              plan.output.size,
              wrapper,
              transfer = plan.hdrTransfer,
              paint = (source as? MediaSource.Solid)?.let { SolidPaint(it.color, plan.hdrTransfer) },
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

private const val PNG_MIME = GAP_FRAME_MIME

// Where a held picture is read from, and the type that routes it to media3's image loader when there is one.
private class HeldPicture(
  val uri: Uri,
  val mimeType: String?,
)

private const val MICROS_PER_MILLISECOND = 1000L
