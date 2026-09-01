package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItemSequence
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
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
 * @property still Whether the clip is a photo rather than a run of samples.
 */
@InternalFilmstripApi
public class Media3Span(
  public val start: Duration,
  public val end: Duration,
  public val item: MediaItem,
  public val effects: List<Effect>,
  public val still: Boolean,
) {
  /**
   * Whether this clip is on screen at [time].
   */
  public fun covers(time: Duration): Boolean = time in start..<end

  /**
   * Where [time] falls inside the trimmed item, which is what a frame reader seeks to.
   */
  public fun positionIn(time: Duration): Duration = (time - start).coerceIn(Duration.ZERO, end - start)

  /**
   * The composition time a frame reader's answer of [clipPositionMs] carries.
   *
   * A reader reports a time inside the clip it decoded, and every caller has to put it back on the
   * composition's clock before anything else compares it.
   */
  public fun compositionTimeOf(clipPositionMs: Long): Duration = start + clipPositionMs.milliseconds
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
   * Deliberately not the chain the player is drawing with. That one is held behind slots which are
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
        resolved.toMedia3(PassThroughEffects)
      } catch (refused: Media3LoweringFailure) {
        // A plan this lowering refuses has no frame to read either.
        return null
      }

    val laid = fresh.videoSpans()
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
          )
        Media3Readback(stamped, stamped.effects + compositionEffects)
      }

    return positions.map { position ->
      readbacks[laid.indexOfFirst { it.covers(position) }.takeIf { it >= 0 } ?: laid.lastIndex]
    }
  }

  /**
   * Swaps in the effect parameters [next] carries, for every frame drawn from here on.
   *
   * The plan is lowered again and matched position for position against the standing chain. A
   * colour or geometry matrix is re-read by media3 on every draw, so those positions take the new
   * value. Anything else, an overlay or a fill pass, is fixed for the life of the graph and only
   * matches an identical re-lowering.
   *
   * @return true when the standing graph now draws [next], false when it has to be rebuilt.
   */
  @Suppress("SwallowedException")
  public fun updateParameters(next: ResolvedComposition): Boolean {
    if (next.output != resolved.output || next.hdrTransfer != resolved.hdrTransfer) return false

    val incoming = mutableListOf<Effect>()
    try {
      next.toMedia3(EffectWrapper { effect -> effect.also(incoming::add) })
    } catch (refused: Media3LoweringFailure) {
      // A plan this lowering refuses is not one the standing graph can take either. The caller
      // rebuilds and hears the same refusal there, where it has somewhere to report it.
      return false
    }

    if (incoming.size != slots.size) return false
    if (slots.indices.any { !slots[it].accepts(incoming[it]) }) return false

    // Validated whole before anything moves, so a refusal leaves the graph drawing what it was.
    slots.indices.forEach { slots[it].install(incoming[it]) }
    resolved = next
    return true
  }
}

/**
 * One clip and the whole chain a frame reader runs over it.
 *
 * @property span Where the clip sits on the composition's timeline.
 * @property effects The clip's own chain and the composition's, in the order a frame goes through
 *   them.
 */
@InternalFilmstripApi
public class Media3Readback(
  public val span: Media3Span,
  public val effects: List<Effect>,
)

/**
 * Lowers this plan for a preview, keeping hold of every position a parameter change can reach.
 *
 * @throws Media3LoweringFailure when an effect or a source cannot be lowered.
 */
@InternalFilmstripApi
public fun ResolvedComposition.toMedia3Preview(): Media3Preview {
  val slots = mutableListOf<LiveSlot>()
  val composition = toMedia3(EffectWrapper { effect -> liveSlotFor(effect).also(slots::add).effect })
  return Media3Preview(composition, composition.videoSpans(), slots, this)
}

/**
 * Where each clip of the video-carrying sequence sits on the timeline.
 *
 * The first such sequence is the one a frame reader draws from, the same track the video graph puts
 * on screen. A gap contributes its duration to the offsets and no span of its own, since there is
 * nothing behind it to read.
 */
private fun Composition.videoSpans(): List<Media3Span> {
  val sequence = sequences.firstOrNull { it.carriesVideo } ?: return emptyList()

  var at = 0L
  return sequence.editedMediaItems.mapNotNull { item ->
    val start = at
    at += item.presentationDurationUs
    val local = item.mediaItem.localConfiguration
    if (local == null) {
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
      )
    }
  }
}

private val EditedMediaItemSequence.carriesVideo: Boolean get() = C.TRACK_TYPE_VIDEO in trackTypes
