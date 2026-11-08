package dev.jordond.filmstrip.media3.internal.preview

import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItemSequence
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.sourceLengthOf
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.media3.internal.ClipPace
import dev.jordond.filmstrip.media3.internal.CompositionClock
import dev.jordond.filmstrip.media3.internal.EffectWrapper
import dev.jordond.filmstrip.media3.internal.LiveSlot
import dev.jordond.filmstrip.media3.internal.SlotPace
import dev.jordond.filmstrip.media3.internal.SlotSwap
import dev.jordond.filmstrip.media3.internal.UnwrappedEffects
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.failure.Media3LoweringFailure
import dev.jordond.filmstrip.media3.internal.isGap
import dev.jordond.filmstrip.media3.internal.liveSlotFor
import dev.jordond.filmstrip.media3.internal.toMedia3
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.drawsSameFormatAs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where one clip sits on the composition's timeline, and what its own chain draws.
 *
 * The boundaries are media3's own: each item's presentation duration is read back off the sequence
 * the player runs rather than added up again from the trims, so a reader lands on the same frame
 * the player shows.
 *
 * @property start The composition time this clip starts at.
 * @property end The composition time the next one starts at.
 * @property item The media item, carrying its trim.
 * @property effects The clip's own chain, before the composition's runs.
 * @property still Whether the clip is a single picture rather than a run of samples, either a photo or the transparent
 * still a gap is laid as.
 * @property shown Each frame the item writes, as how far into the span it is written paired with where it sits in the
 * item, in order. Empty when the item runs its own frames at its speed.
 * @property speed How fast the item plays through this span.
 */
@InternalFilmstripApi
public class Media3Span(
  public val start: Duration,
  public val end: Duration,
  public val item: MediaItem,
  public val effects: List<Effect>,
  public val still: Boolean,
  public val shown: List<Pair<Duration, Duration>> = emptyList(),
  public val speed: Double = 1.0,
) {
  /**
   * Whether this clip is on screen at [time].
   */
  public fun covers(time: Duration): Boolean = time in start..<end

  /**
   * The clock a reader's chain puts this clip's frames on, which stamps each frame [shown] where the span writes it.
   */
  internal val clock: CompositionClock by lazy { CompositionClock(start, speed, shown) }

  /**
   * Where [time] falls inside the trimmed item, which is what a frame reader seeks to.
   *
   * With [shown] frames that is the position of the one written last at or before [time], which is the frame the
   * export shows there.
   */
  public fun positionIn(time: Duration): Duration {
    val offset = time - start
    if (shown.isEmpty()) {
      return sourceOffsetAt(
        offset,
        speed,
      ).coerceIn(Duration.ZERO, sourceLengthOf(end - start, speed))
    }
    val found = shown.binarySearch { (written, _) -> written.compareTo(offset) }
    return shown[if (found >= 0) found else (-found - 2).coerceAtLeast(0)].second
  }
}

/**
 * A lowered composition a preview can keep hold of while it plays.
 *
 * The graph is the one an export of the same edit would run, built through the same lowering, with
 * every position a parameter can reach kept behind a slot that can be swapped. That is what lets a
 * brightness slider or a crop drag change the picture without media3 reconfiguring the whole
 * pipeline, which `CompositionPlayer.setComposition` does on every call.
 *
 * @property composition What the player is handed.
 * @property spans Where each clip of the video sequence sits, for reading a frame back.
 */
@InternalFilmstripApi
public class Media3Preview internal constructor(
  public val composition: Composition,
  public val spans: List<Media3Span>,
  private val slots: List<LiveSlot>,
  resolved: ResolvedComposition,
  private var bounds: Media3ClipReads,
) {
  /**
   * The plan the standing graph draws, which a swap replaces.
   */
  public var resolved: ResolvedComposition = resolved
    private set

  /**
   * The composition-level chain, which a frame reader runs after a span's own.
   */
  public val compositionEffects: List<Effect> get() = composition.effects.videoEffects

  /**
   * How many of the composition's sequences carry video.
   *
   * More than one needs a video graph that takes multiple inputs. The default single-input graph
   * logs a warning and draws the wrong picture instead of failing.
   */
  public val videoSequences: Int = composition.sequences.count { it.carriesVideo }

  /**
   * The clip on screen at [position], or null where the timeline has a gap.
   */
  public fun spanAt(position: Duration): Media3Span? = spans.firstOrNull { it.covers(position) }

  /**
   * What a frame reader runs to draw [position], lowered fresh for this request.
   *
   * Not the chain the player is drawing with. That one is held behind slots which are
   * swapped in place and keep their identity, which is what lets a parameter reach a standing graph
   * without rebuilding it. A reader builds a graph of its own per request, so it takes a value.
   *
   * The item carries [revision] as its media id. Frame extraction runs on one player shared across
   * the process, and it only re-prepares that player when the media item differs, so two requests
   * for the same clip at the same time look identical to it however the effects changed in between.
   * A seek that resolves to the position it is already on renders nothing and repeats the frame it
   * last produced, which is the one drawn with the old chain. The revision is what one rendered
   * frame is decided by, so stamping it here makes the items differ exactly when the frame should
   * and stay equal when repeating the last one is the right answer.
   *
   * @param position The composition time to draw.
   * @param revision What this edit's frames are decided by, from `effectsRevision`.
   * @return the request, or null where the timeline draws nothing at [position].
   */
  public fun readbackAt(
    position: Duration,
    revision: Long,
  ): Media3Readback? = readbacksAt(listOf(position), revision)?.single()

  /**
   * What a frame reader runs to draw each of [positions], off one lowering.
   *
   * The clip layout is settled once for the whole run rather than once per position, and positions
   * landing on the same clip are handed the same [Media3Readback]. A reader compares those by
   * identity to see which of its positions one decoder can serve in a row.
   *
   * Everything [readbackAt] documents about the chain and the stamped media id holds here, since
   * that call is this one over a single position.
   *
   * A position inside a gap is handed the transparent still the export lays there, under the
   * composition's chain, so it draws the fill with the composition's effects and overlays over it.
   *
   * @param positions The composition times to draw.
   * @param revision What this edit's frames are decided by, from `effectsRevision`.
   * @return one request per entry in [positions], or null where the timeline draws nothing at all.
   */
  @Suppress("SwallowedException")
  public fun readbacksAt(
    positions: List<Duration>,
    revision: Long,
  ): List<Media3Readback>? {
    val fresh =
      try {
        resolved.toMedia3(UnwrappedEffects, bounds)
      } catch (refused: Media3LoweringFailure) {
        // A plan this lowering refuses has no frame to read either.
        return null
      }

    val laid = fresh.videoSpans(withGaps = true)
    if (laid.isEmpty()) return null

    val compositionEffects = fresh.effects.videoEffects
    val readbacks =
      laid.map { span ->
        val stamped =
          Media3Span(
            start = span.start,
            end = span.end,
            item =
              span.item
                .buildUpon()
                .setMediaId(revision.toString())
                .build(),
            effects = span.effects,
            still = span.still,
            shown = span.shown,
            speed = span.speed,
          )
        Media3Readback(stamped, stamped.readbackChain(compositionEffects))
      }

    return positions.map { position ->
      readbacks[laid.indexOfFirst { it.covers(position) }.takeIf { it >= 0 } ?: laid.lastIndex]
    }
  }

  /**
   * Swaps in the effect parameters [next] carries, for every frame drawn from here on.
   *
   * The plan is lowered again and matched position for position against the standing chain. A
   * color or geometry matrix is re-read by media3 on every draw, so those positions take the new
   * value. Anything else, an overlay or a fill pass, is fixed for the life of the graph and only
   * matches an identical re-lowering.
   *
   * @param bounds The samples [next]'s clips decode, which frames read from here on are laid by.
   * @return true when the standing graph now draws [next], false when it has to be rebuilt.
   */
  @Suppress("SwallowedException")
  public fun updateParameters(
    next: ResolvedComposition,
    bounds: Media3ClipReads,
  ): Boolean {
    if (!resolved.drawsSameFormatAs(next)) return false
    // media3's own limit: a copy lowers to an exact video sequence and an exact audio one, a transcode to a grid video
    // sequence and an exact audio one. The video items differ, and a standing graph keeps the sequences it was built
    // with, so a change of path rebuilds.
    if (next.path != resolved.path) return false

    val incoming = mutableListOf<Effect>()
    try {
      next.toMedia3(EffectWrapper { effect -> effect.also(incoming::add) }, bounds)
    } catch (refused: Media3LoweringFailure) {
      // A plan this lowering refuses is not one the standing graph can take either. The caller
      // rebuilds and hears the same refusal there, where it has somewhere to report it.
      return false
    }

    if (incoming.size != slots.size) return false
    // Validated whole before anything moves, so a refusal leaves the graph drawing what it was.
    val swaps = slots.indices.map { slots[it].swapTo(incoming[it]) ?: return false }
    swaps.forEach(SlotSwap::install)
    resolved = next
    this.bounds = bounds
    return true
  }
}

/**
 * One clip and the whole chain a frame reader runs over it.
 *
 * @property span Where the clip sits on the composition's timeline.
 * @property effects The clip's own chain and the composition's, in the order a frame goes through
 *   them, on the composition's clock.
 */
@InternalFilmstripApi
public class Media3Readback(
  public val span: Media3Span,
  public val effects: List<Effect>,
) {
  /**
   * The composition time a frame a reader answers with at [framePositionMs] into the item is drawn at.
   *
   * A reader floors its answer to the millisecond, and a slowed clip stretches each millisecond over many of the
   * composition's. So a frame the span shows is taken at its own position, which sits in the millisecond answered, and
   * drawn where the span writes it. Only a frame the span never shows, which a seek to the nearest sync sample can land
   * on, is taken at the millisecond.
   */
  public fun compositionTimeOf(framePositionMs: Long): Duration {
    val reported = framePositionMs.milliseconds
    val found = span.shown.binarySearch { (_, position) -> position.compareTo(reported) }
    val first = span.shown.getOrNull(if (found >= 0) found else -found - 1)?.second
    val shown = first?.takeIf { it < reported + 1.milliseconds }
    return span.clock.compositionTimeOf(shown ?: reported)
  }
}

/**
 * The whole chain a frame reader runs over this clip, on the composition's clock.
 *
 * A clip's frames reach a reader measured from the start of the item it decoded, so the clock goes
 * in front of the chain that was lowered against the composition, and its undoing goes behind it so
 * the reader sees the item's time it seeks by. A photo needs neither: its reader draws one picture
 * and is handed the composition time it draws at directly.
 */
private fun Media3Span.readbackChain(compositionEffects: List<Effect>): List<Effect> {
  val chain = effects + compositionEffects
  if (still) return chain
  return listOf(clock) + chain + clock.itemClock
}

/**
 * Lowers this plan for a preview, keeping hold of every position a parameter change can reach.
 *
 * @param bounds The samples each clip decodes, read off its container by `readClips`, the same ones an export of
 * this plan lays its items by.
 * @throws Media3LoweringFailure when a source cannot be lowered.
 */
@InternalFilmstripApi
public fun ResolvedComposition.toMedia3Preview(bounds: Media3ClipReads): Media3Preview {
  val slots = mutableListOf<LiveSlot>()
  val composition = toMedia3(EffectWrapper { effect -> liveSlotFor(effect).also(slots::add).effect }, bounds)
  return Media3Preview(composition, composition.videoSpans(), slots, this, bounds)
}

/**
 * Where each clip of the video-carrying sequence sits on the timeline.
 *
 * The first such sequence is the one a frame reader draws from, the same track the video graph puts
 * on screen. A gap always contributes its duration to the offsets. It gets a span of its own only
 * when [withGaps] asks for one and it is laid as a transparent still, since media3's own gap has
 * nothing behind it to read.
 */
private fun Composition.videoSpans(withGaps: Boolean = false): List<Media3Span> {
  val sequence = sequences.firstOrNull { it.carriesVideo } ?: return emptyList()

  var at = 0L
  return sequence.editedMediaItems.mapNotNull { item ->
    val start = at
    at += item.presentationDurationUs
    val local = item.mediaItem.localConfiguration
    if (local == null || (item.isGap && !withGaps)) {
      null
    } else {
      Media3Span(
        start = start.microseconds,
        end = at.microseconds,
        item = item.mediaItem,
        effects = item.effects.videoEffects,
        // The length a still is held for is what media3 itself routes an item to its image loader
        // by, so it is also what tells a frame reader there are no samples here to seek.
        still = local.imageDurationMs != C.TIME_UNSET,
        shown = (item.speedProvider as? SlotPace)?.shown.orEmpty(),
        speed = (item.speedProvider as? ClipPace)?.clipSpeed ?: 1.0,
      )
    }
  }
}

private val EditedMediaItemSequence.carriesVideo: Boolean get() = C.TRACK_TYPE_VIDEO in trackTypes
