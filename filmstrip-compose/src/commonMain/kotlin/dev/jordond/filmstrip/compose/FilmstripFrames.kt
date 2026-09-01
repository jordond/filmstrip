package dev.jordond.filmstrip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.effectsRevision
import dev.jordond.filmstrip.media.FrameRenderer
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.PlatformImage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.withIndex
import kotlin.time.Duration

/**
 * The frames of a timeline strip, held for a window around what is on screen.
 *
 * Built by [rememberFilmstripFrames]. Draw the strip with a `LazyRow` of [count] items, read each
 * item's frame from [get], and call [onVisibleRange] as the row scrolls. Frames are fetched for the
 * visible items plus an overscan either side, and everything outside that window is closed.
 *
 * A frame read from [get] is only valid while its index is inside the window that was last
 * reported. Some platforms hand out the decoded frame itself rather than a copy, so an item drawn
 * from a bitmap it kept after scrolling away draws from pixels that have been released.
 */
@Stable
public class FilmstripFrames internal constructor(
  private val renderer: FrameRenderer,
) {
  private val frames = mutableStateMapOf<Int, StripFrame>()

  private var strip: StripSpec? by mutableStateOf(null)
  private var visible: IntRange by mutableStateOf(IntRange.EMPTY)
  private var held: Long = 0

  /**
   * How many positions the strip covers.
   */
  public val count: Int get() = strip?.positions?.size ?: 0

  /**
   * How many bytes of decoded frames are held right now, against the cap the strip was built with.
   */
  public val heldBytes: Long get() = held

  /**
   * The frame at [index], or null while it has not arrived.
   */
  public operator fun get(index: Int): ImageBitmap? = frames[index]?.bitmap()

  /**
   * Whether a decoded frame for [index] is held, without asking for a drawable form of it.
   */
  internal fun holds(index: Int): Boolean = index in frames

  /**
   * Says which items the strip is showing, as item indices.
   *
   * Wire it to the row's own scroll state, for instance from a `LazyListState`'s
   * `firstVisibleItemIndex` and the size of its `layoutInfo.visibleItemsInfo`. Nothing is fetched
   * until this is called, and a range reported outside the strip is clamped into it.
   */
  public fun onVisibleRange(
    first: Int,
    last: Int,
  ) {
    val indices = strip?.positions?.indices
    if (indices == null || indices.isEmpty() || last < first) {
      visible = IntRange.EMPTY
      return
    }
    visible = first.coerceIn(indices)..last.coerceIn(indices)
  }

  /**
   * Fetches what [next] asks for, and keeps fetching as the reported window moves.
   *
   * Suspends until cancelled. A window that moves cancels the fetch in flight, which is what stops
   * a decode the strip has already scrolled past.
   */
  internal suspend fun fill(next: StripSpec) {
    val previous = strip
    if (next.invalidates(previous)) {
      clear()
    } else if (previous != null) {
      remap(previous, next)
    }
    strip = next

    snapshotFlow { window() }.collectLatest { window ->
      retain(window)
      val missing = window.filter { it !in frames }
      if (missing.isEmpty()) return@collectLatest

      val spec = strip ?: return@collectLatest
      renderer
        .frames(spec.composition, missing.map { spec.positions[it] }, spec.heightPx)
        .withIndex()
        .collect { (offset, result) -> store(spec, missing[offset], result) }
    }
  }

  /**
   * Moves what is already decoded onto [next]'s indices, and closes what [next] has no place for.
   *
   * A strip that only moves its positions, such as one being zoomed, renders the same picture at a
   * position it kept. Clearing there would re-decode the whole strip on every step.
   */
  private fun remap(
    previous: StripSpec,
    next: StripSpec,
  ) {
    if (frames.isEmpty()) return
    if (previous.positions == next.positions) return

    // Keyed by the position a frame was decoded for rather than by the index it sat at, so what is
    // built here is the size of what is held rather than the size of the strip.
    val source = mutableMapOf<Duration, StripFrame>()
    frames.forEach { (index, frame) ->
      val position = previous.positions.getOrNull(index)
      if (position == null || position in source) {
        held -= frame.bytes
        frame.close()
      } else {
        source[position] = frame
      }
    }

    // Walked backwards so a position appearing twice in the new strip keeps its frame at the later
    // of the two indices.
    val moved = mutableMapOf<Int, StripFrame>()
    for (index in next.positions.indices.reversed()) {
      if (source.isEmpty()) break
      val frame = source.remove(next.positions[index]) ?: continue
      moved[index] = frame
    }

    source.values.forEach { frame ->
      held -= frame.bytes
      frame.close()
    }

    frames.clear()
    frames.putAll(moved)
  }

  /**
   * Closes every frame held and forgets the strip.
   */
  internal fun clear() {
    frames.values.forEach { it.close() }
    frames.clear()
    held = 0
  }

  /**
   * The indices worth holding: everything on screen, plus the overscan either side.
   */
  private fun window(): List<Int> {
    val indices = strip?.positions?.indices ?: return emptyList()
    val overscan = strip?.overscan ?: return emptyList()
    if (indices.isEmpty() || visible.isEmpty()) return emptyList()
    return ((visible.first - overscan).coerceIn(indices)..(visible.last + overscan).coerceIn(indices)).toList()
  }

  /**
   * Closes everything outside [window].
   */
  private fun retain(window: List<Int>) {
    frames.keys.filterNot { it in window }.forEach(::evict)
  }

  /**
   * Files a decoded frame at [index], as long as [spec] is still the strip that asked for it.
   *
   * A fill whose job is cancelled can deliver one more frame after the next fill has taken over,
   * and that frame's index was resolved against positions the strip no longer has. Writing it would
   * put one source time's picture under another's.
   */
  private fun store(
    spec: StripSpec,
    index: Int,
    result: FrameResult,
  ) {
    val frame = (result as? FrameResult.Success)?.image ?: return
    if (spec !== strip || index !in window()) {
      frame.close()
      return
    }

    evict(index)
    val stored = StripFrame(frame)
    frames[index] = stored
    held += stored.bytes
    if (index in visible) stored.bitmap()
    trim()
  }

  /**
   * Drops frames until the cap is met, furthest from the visible items first.
   *
   * One frame is always kept, so a cap set below the size of a single frame still shows something
   * rather than clearing itself on every arrival.
   */
  private fun trim() {
    val cap = strip?.maxBytes ?: return
    while (held > cap && frames.size > 1) {
      evict(frames.keys.maxWith(compareBy({ distance(it) }, { it })))
    }
  }

  /**
   * How far [index] sits outside the visible items, and zero for one inside them.
   */
  private fun distance(index: Int): Int {
    if (visible.isEmpty()) return index
    return maxOf(visible.first - index, index - visible.last, 0)
  }

  private fun evict(index: Int) {
    val frame = frames.remove(index) ?: return
    held -= frame.bytes
    frame.close()
  }
}

/**
 * Defaults for [rememberFilmstripFrames].
 */
public object FilmstripFramesDefaults {
  /**
   * How many bytes of decoded frames a strip holds.
   *
   * Bytes rather than a count of frames, because a decoded frame costs its own pixels wherever it
   * lives and a strip drawn at two heights holds wildly different amounts under the same count.
   */
  public val MaxBytes: Long = 16L * 1024 * 1024

  /**
   * How many items either side of the visible ones are fetched and kept.
   */
  public val Overscan: Int = 4
}

/**
 * Remembers the frames of a timeline strip over [composition], and closes them when this leaves
 * composition.
 *
 * Fetching is driven by [FilmstripFrames.onVisibleRange], so a strip that never reports its window
 * never decodes anything. Requests are served one at a time, since extraction running alongside a
 * preview contends with it for the device's decoders.
 *
 * The cache is dropped whenever anything that would change a rendered frame changes: an edit that
 * moves the pixels, or a different [heightPx]. An edit that changes nothing a frame is rendered
 * from, such as a clip's gain, keeps every frame already held. A different set of [positions] keeps
 * the frames whose position survives and closes the rest, so a strip being zoomed only asks for the
 * positions it gained.
 *
 * @param renderer What renders the frames, usually the `Filmstrip` itself.
 * @param composition The edit to render from.
 * @param positions Where in the composition each strip item sits.
 * @param heightPx The height to render at, in pixels.
 * @param maxBytes How many bytes of decoded frames to hold.
 * @param overscan How many items either side of the visible ones to keep ready.
 * @return State keyed to [renderer], the same instance across recompositions.
 */
@OptIn(InternalFilmstripApi::class)
@Composable
public fun rememberFilmstripFrames(
  renderer: FrameRenderer,
  composition: EditComposition,
  positions: List<Duration>,
  heightPx: Int,
  maxBytes: Long = FilmstripFramesDefaults.MaxBytes,
  overscan: Int = FilmstripFramesDefaults.Overscan,
): FilmstripFrames {
  val frames = remember(renderer) { FilmstripFrames(renderer) }
  val revision = remember(composition) { composition.effectsRevision() }

  DisposableEffect(frames) {
    onDispose { frames.clear() }
  }

  LaunchedEffect(frames, composition, revision, positions, heightPx, maxBytes, overscan) {
    frames.fill(StripSpec(composition, revision, positions, heightPx, maxBytes, overscan))
  }

  return frames
}

/**
 * Everything one strip renders against.
 */
internal class StripSpec(
  val composition: EditComposition,
  val revision: Long,
  val positions: List<Duration>,
  val heightPx: Int,
  val maxBytes: Long,
  val overscan: Int,
) {
  /**
   * Whether a strip already filled against [previous] holds frames this one cannot reuse.
   *
   * Positions are not part of this. A frame is rendered from the edit and the height alone, so one
   * decoded for a position both strips ask for is still the right picture at whatever index it has
   * moved to.
   */
  fun invalidates(previous: StripSpec?): Boolean =
    previous == null ||
      revision != previous.revision ||
      heightPx != previous.heightPx
}

/**
 * One decoded frame, and the drawable form of it.
 *
 * A frame that arrives for an item the strip is showing is converted there and then, on the
 * coroutine that fetched it. One that arrives into the overscan waits until something asks for it,
 * since it may never be drawn at all and on every target but Android the conversion costs a copy.
 */
internal class StripFrame(
  private val image: PlatformImage,
) {
  val bytes: Long = image.widthPx.toLong() * image.heightPx * BYTES_PER_PIXEL

  private var drawable: ImageBitmap? = null

  fun bitmap(): ImageBitmap? = drawable ?: image.toImageBitmap()?.also { drawable = it }

  fun close() {
    drawable = null
    image.close()
  }
}

/**
 * What one pixel costs the strip's cache, shared with tests pinning a source's own frame cost.
 */
internal const val BYTES_PER_PIXEL = 4
