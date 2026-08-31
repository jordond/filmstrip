@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.DurationUnit

/**
 * One composited preview frame, in the packed form a surface draws and a comparison reads.
 *
 * @property pixels Tightly packed RGBA_8888, row major, `width * height * 4` bytes.
 * @property size The frame's dimensions.
 * @property presentationTime The composition time this frame sits at on the output frame grid,
 *   which is the time an export writes it at rather than the time that was asked for.
 */
@InternalFilmstripApi
public class PreviewFrame internal constructor(
  public val pixels: ByteArray,
  public val size: Size,
  public val presentationTime: Duration,
)

/**
 * A lowered composition a preview draws frames out of, one position at a time.
 *
 * The graph is the export's own: the same [browserRenderOf] lowering, the same [BrowserCompositor]
 * pass and the same decoded frames, so the bytes a surface draws are the bytes the encoder would
 * take. Nothing here presents anything, because the browser surface draws the read-back pixels
 * itself.
 *
 * Two decoders reach one clip. Playback pulls a forward run into a bounded window, and a read-back
 * away from the playhead lands on a sampler of its own, so asking for a frame elsewhere neither
 * uses nor discards what playback is holding.
 *
 * Not thread safe in the sense that matters here: every entry point that touches the compositor or
 * the window takes the same lock, so two frames are never drawn into one canvas at once. A flush or
 * a release arriving while one of them is suspended over a decoder leaves its work for whoever holds
 * the lock to settle on the way out, rather than pulling the state out from under it.
 */
@InternalFilmstripApi
public class BrowserPreview internal constructor(
  resolved: ResolvedComposition,
  render: BrowserRender,
) {
  private val sources = SourceCache()
  private val readbackSamplers = mutableMapOf<Int, FrameSampler>()
  private val playbackSamplers = mutableMapOf<Int, FrameSampler>()
  private val window = FrameWindow(PREVIEW_LOOK_AHEAD)
  private val lock = Mutex()

  private var render: BrowserRender = render
  private var compositor: BrowserCompositor? = null
  private var audio: BrowserAudioPreview? = null
  private var released = false
  private var flushRequested = false

  /**
   * The plan the frames are drawn from, which a parameter swap replaces.
   */
  public var resolved: ResolvedComposition = resolved
    private set

  /**
   * The frame the composition outputs, which is the frame [frameAt] hands back.
   */
  public val outputSize: Size get() = Size(render.width, render.height)

  /**
   * The output frame rate, which is the grid every position snaps to.
   */
  public val frameRate: Int get() = render.frameRate

  /**
   * Whether anything in this composition contributes audible audio.
   */
  public val carriesAudio: Boolean
    get() =
      render.audioFormat != null &&
        render.audioTracks.any { track -> track.clips.any { it.gain > 0f && it.info.audio != null } }

  /**
   * The live audio graph for this composition, built on first use, or null when nothing here is
   * audible.
   *
   * Built lazily rather than with the preview, because an `AudioContext` costs a hardware clock and
   * a silent composition never needs one.
   */
  public fun audio(): BrowserAudioPreview? {
    if (released || !carriesAudio) return null
    return audio ?: BrowserAudioPreview({ render.audioTracks }, sources).also { audio = it }
  }

  /**
   * How many decoded frames the look-ahead is holding.
   */
  internal val buffered: Int get() = window.size

  /**
   * How many decoded frames the look-ahead has closed, counted against how many it opened.
   */
  internal val closedFrames: Int get() = window.closed

  internal val openedFrames: Int get() = window.opened

  /**
   * Draws the frame at [position] and reads it back, or null when the composition has none there.
   *
   * The position is snapped to the output frame grid first, so the decoded frame chosen is the one
   * the export pipeline fills that same slot with.
   */
  public suspend fun frameAt(position: Duration): PreviewFrame? = exclusively { draw(position) }

  /**
   * Decodes ahead of [from], up to the window's bound.
   *
   * Called on a loose interval while playback runs. A position the window cannot reach by reading
   * forward, such as one behind what it already holds, reseats it on a fresh decoder.
   */
  public suspend fun fillAhead(from: Duration) {
    exclusively { decodeAhead(from) }
  }

  /**
   * Closes every frame the look-ahead is holding and drops its decoder.
   *
   * A decode already under way keeps the frames it is mid-way through taking, and they are closed
   * with the rest of the window the moment that decode finishes.
   */
  public fun flush() {
    if (released) return
    if (lock.tryLock()) {
      try {
        window.clear()
      } finally {
        lock.unlock()
      }
    } else {
      flushRequested = true
    }
  }

  /**
   * The composition time of the sync sample at or before [position].
   *
   * What a relaxed seek lands on. A clip with no sync sample before the position, and a source the
   * container cannot answer for, both come back as [position] itself.
   */
  public suspend fun syncSampleAt(position: Duration): Duration = exclusively { syncSample(position) } ?: position

  /**
   * Swaps in the effect parameters [next] carries, for every frame drawn from here on.
   *
   * The plan is lowered again through the export's own lowering and the result replaces the
   * standing one. A change that moves the output frame or the grade is refused, because the
   * compositor is built against both and the caller has to rebuild instead.
   *
   * @param next The same timeline, lowered again with different effect parameters.
   * @param edit The edit [next] was lowered from, which the lowering reads clip geometry off.
   * @return true when the preview now draws [next], false when it has to be rebuilt.
   */
  public suspend fun updateParameters(
    next: ResolvedComposition,
    edit: EditComposition,
  ): Boolean = exclusively { swap(next, edit) } ?: false

  /**
   * Closes every frame, decoder, source and GL object this preview opened.
   *
   * A draw or a decode already under way finishes against the state it started on, and the teardown
   * runs the moment it lets the lock go. Nothing entered from here on gets that far.
   */
  public fun release() {
    if (released) return
    released = true
    if (lock.tryLock()) {
      try {
        teardown()
      } finally {
        lock.unlock()
      }
    }
  }

  /**
   * Runs [work] with sole use of the window and the compositor, then settles whatever arrived while
   * it was suspended.
   *
   * @return What [work] came back with, or null when this preview was already released.
   */
  private suspend fun <T> exclusively(work: suspend () -> T): T? =
    lock.withLock {
      if (released) return@withLock null
      try {
        work()
      } finally {
        settle()
      }
    }

  /**
   * Carries out the flush or the release that was asked for while the lock was held.
   */
  private fun settle() {
    if (released) {
      teardown()
    } else if (flushRequested) {
      flushRequested = false
      window.clear()
    }
  }

  private fun teardown() {
    flushRequested = false
    window.clear()
    audio?.release()
    audio = null
    compositor?.release()
    compositor = null
    readbackSamplers.clear()
    playbackSamplers.clear()
    sources.close()
  }

  private fun swap(
    next: ResolvedComposition,
    edit: EditComposition,
  ): Boolean {
    if (next.output != resolved.output || next.hdrTransfer != resolved.hdrTransfer) return false

    val incoming = browserRenderOf(next, edit)
    if (incoming.clips.size != render.clips.size) return false

    // The fill is cleared into the canvas and a blurred one builds its own passes once, so a fill
    // that moved needs a fresh compositor. The decoded frames are untouched by it and stay.
    if (incoming.fill != render.fill) {
      compositor?.release()
      compositor = null
    }
    render = incoming
    resolved = next
    return true
  }

  private suspend fun draw(position: Duration): PreviewFrame? {
    val slot = slotAt(position) ?: return null
    val sourceUs = slot.sourceUs

    val ready =
      if (window.holds(slot.index)) {
        window.advanceTo(sourceUs)
        window.nearest(sourceUs)
      } else {
        null
      }
    val own = if (ready == null) sampler(readbackSamplers, slot)?.sampleAt(sourceUs) else null
    val sample = ready ?: own ?: return null
    return try {
      composite(slot, sample)
    } finally {
      own?.close()
    }
  }

  private suspend fun decodeAhead(from: Duration) {
    val slot = slotAt(from) ?: return
    if (!window.holds(slot.index) || window.startsAfter(slot.sourceUs)) {
      val sampler = sampler(playbackSamplers, slot) ?: return
      window.seat(slot.index, sampler.stream(slot.sourceUs, slot.clip.trimEndUs))
    }
    window.advanceTo(slot.sourceUs)
    window.fill()
  }

  private suspend fun syncSample(position: Duration): Duration {
    val slot = slotAt(position) ?: return position
    val reader = sources.open(slot.clip.source) ?: return position
    val keyUs = reader.keyFrameAt(slot.sourceUs) ?: return position
    if (keyUs > slot.sourceUs) return position

    // Back onto the output timeline, then down onto the frame grid, so the position a seek settles
    // on is one the preview can draw rather than one between two slots.
    val outputUs = slot.clip.offsetUs + (keyUs - slot.clip.trimStartUs)
    val step = stepUs
    val landed = floor((outputUs - slot.clip.offsetUs) / step) * step + slot.clip.offsetUs
    return landed.coerceAtLeast(0.0).microseconds
  }

  /**
   * Draws [sample] through the clip's pass and copies the canvas back out as packed RGBA.
   */
  private suspend fun composite(
    slot: Slot,
    sample: VideoSample,
  ): PreviewFrame {
    val pass = compositor()
    pass.clip(slot.clip)
    pass.draw(sample)

    val shot = pass.snapshot(slot.outputUs, stepUs)
    try {
      val options = JsOptions().put("format", "RGBA").build()
      val target = Uint8Array(shot.allocationSize(options))
      shot.copyTo(target, options).await()
      return PreviewFrame(
        pixels = target.toByteArray(),
        size = Size(render.width, render.height),
        presentationTime = slot.outputUs.microseconds,
      )
    } finally {
      shot.close()
    }
  }

  private fun compositor(): BrowserCompositor =
    compositor ?: BrowserCompositor.create(render.width, render.height, render.fill).also { compositor = it }

  private suspend fun sampler(
    cache: MutableMap<Int, FrameSampler>,
    slot: Slot,
  ): FrameSampler? {
    cache[slot.index]?.let { return it }
    val reader = sources.open(slot.clip.source) ?: return null
    return reader.sampler()?.also { cache[slot.index] = it }
  }

  private val stepUs: Double get() = MICROS_PER_SECOND / render.frameRate

  /**
   * Which clip is on screen at [position], and which of its output slots the position falls in.
   *
   * A position past the end holds on the composition's last frame rather than answering with
   * nothing, so a playhead sitting on the duration still has a picture.
   */
  private fun slotAt(position: Duration): Slot? {
    if (render.clips.isEmpty() || render.frameRate <= 0) return null
    val step = stepUs
    val positionUs = position.toDouble(DurationUnit.MICROSECONDS).coerceAtLeast(0.0)

    render.clips.forEachIndexed { index, clip ->
      val slot = ((positionUs - clip.offsetUs) / step).roundToLong()
      if (slot < clip.frames) return Slot(index, clip, slot.coerceAtLeast(0))
    }
    val last = render.clips.last()
    return Slot(render.clips.lastIndex, last, last.frames - 1)
  }

  /**
   * One output slot of one clip: where to decode from, and what composition time it carries.
   */
  private inner class Slot(
    val index: Int,
    val clip: RenderedClip,
    private val slot: Long,
  ) {
    val sourceUs: Double get() = clip.trimStartUs + slot * stepUs

    val outputUs: Double get() = clip.offsetUs + slot * stepUs
  }
}

/**
 * Whether this page can give out the WebGL2 context the compositor draws on.
 *
 * Asked by taking one and handing it straight back, because a browser can carry the API and still
 * refuse a context on a driver it has blocklisted.
 */
@InternalFilmstripApi
public fun browserCanComposite(): Boolean =
  runCatching {
    val canvas = OffscreenCanvas(1, 1)
    val gl = canvas.getContext("webgl2", JsOptions().build()) ?: return false
    gl.getExtension("WEBGL_lose_context")?.loseContext()
    true
  }.getOrDefault(false)

/**
 * Whether this page carries the Web Audio API the preview's clock and monitor volume run on.
 */
@InternalFilmstripApi
public fun browserCanMonitorAudio(): Boolean = hasAudioContext()

/**
 * Lowers this plan for a preview, through the same lowering an export of [edit] runs.
 *
 * @param edit The edit this plan was resolved from. The lowering reads each clip's own geometry off
 *   it, which the resolved chain no longer carries as measurable specs.
 */
@InternalFilmstripApi
public fun ResolvedComposition.toBrowserPreview(edit: EditComposition): BrowserPreview =
  BrowserPreview(this, browserRenderOf(this, edit))

/**
 * The decoded frames waiting ahead of the playhead, for one clip at a time.
 *
 * Every sample in here is the window's: it closes one when the playhead leaves it behind, when the
 * window is cleared, and when the preview is released. Nothing else closes one, so a decoded frame
 * has exactly one owner from the moment the decoder yields it. A frame the decoder hands back after
 * a clear is closed on arrival rather than kept, since there is no longer anywhere to keep it.
 *
 * The bound is a count and not a span of time, so a clip running at three frames a second cannot
 * hold half a minute of decoded video.
 */
private class FrameWindow(
  private val capacity: Int,
) {
  private val frames = ArrayDeque<VideoSample>()
  private var stream: FrameStream? = null
  private var drained = false
  private var generation = 0

  var clipIndex: Int = NO_CLIP
    private set

  /**
   * How many frames this window has taken from a decoder, which a leak test counts [closed]
   * against.
   */
  var opened: Int = 0
    private set

  var closed: Int = 0
    private set

  val size: Int get() = frames.size

  /**
   * Whether this window is the one reading [index], and has anything left to say about it.
   */
  fun holds(index: Int): Boolean = clipIndex == index && (frames.isNotEmpty() || !drained)

  /**
   * Whether everything held starts after [sourceUs], which is a playhead that moved backwards.
   */
  fun startsAfter(sourceUs: Double): Boolean = frames.firstOrNull()?.let { it.microsecondTimestamp > sourceUs } == true

  fun seat(
    index: Int,
    stream: FrameStream,
  ) {
    clear()
    clipIndex = index
    this.stream = stream
    drained = false
  }

  /**
   * Decodes forward until a frame past [sourceUs] is in hand, dropping what the playhead has left
   * behind to make room for it.
   */
  suspend fun advanceTo(sourceUs: Double) {
    dropBehind(sourceUs)
    while (!drained && (frames.isEmpty() || frames.last().microsecondTimestamp <= sourceUs)) {
      if (frames.size >= capacity) break
      if (!pull()) break
    }
  }

  /**
   * Decodes forward until the window is full or the clip runs out.
   */
  suspend fun fill() {
    while (!drained && frames.size < capacity) {
      if (!pull()) break
    }
  }

  /**
   * The frame the export draws at [sourceUs], still owned by this window, or null when nothing here
   * answers for that time.
   *
   * The choice between the two frames straddling the slot is the export pipeline's own: whichever
   * timestamp sits closer.
   */
  fun nearest(sourceUs: Double): VideoSample? {
    val first = frames.firstOrNull() ?: return null
    if (first.microsecondTimestamp > sourceUs) return null

    var index = 1
    var current = first
    while (index < frames.size && frames[index].microsecondTimestamp <= sourceUs) {
      current = frames[index]
      index++
    }
    val ahead = frames.getOrNull(index) ?: return current
    val nearer = abs(ahead.microsecondTimestamp - sourceUs) < abs(current.microsecondTimestamp - sourceUs)
    return if (nearer) ahead else current
  }

  fun clear() {
    generation++
    stream?.close()
    stream = null
    drained = false
    clipIndex = NO_CLIP
    while (frames.isNotEmpty()) closeFront()
  }

  private suspend fun pull(): Boolean {
    val open = stream ?: return false
    val at = generation
    val sample = open.next()
    if (at != generation) {
      sample?.also {
        opened++
        it.close()
        closed++
      }
      return false
    }
    if (sample == null) {
      drained = true
      return false
    }
    opened++
    frames.addLast(sample)
    return true
  }

  private fun dropBehind(sourceUs: Double) {
    while (frames.size > 1 && frames[1].microsecondTimestamp <= sourceUs) closeFront()
  }

  private fun closeFront() {
    frames.removeFirst().close()
    closed++
  }
}

/**
 * How many decoded frames the look-ahead holds.
 *
 * A count rather than a span, so a clip at three frames a second cannot hold ten seconds of decoded
 * video. Under half a second at thirty frames a second, which is more than a preview polled at
 * display rate needs to stay ahead of its playhead, and few enough that a 4K clip's worth of
 * decoded frames does not swamp the tab.
 */
internal const val PREVIEW_LOOK_AHEAD: Int = 12

private const val NO_CLIP = -1
