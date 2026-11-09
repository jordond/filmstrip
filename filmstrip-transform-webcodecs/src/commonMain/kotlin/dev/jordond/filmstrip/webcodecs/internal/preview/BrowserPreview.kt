@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.preview

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.drawsSameFormatAs
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.FrameSampler
import dev.jordond.filmstrip.webcodecs.internal.FrameWindow
import dev.jordond.filmstrip.webcodecs.internal.NO_CLIP
import dev.jordond.filmstrip.webcodecs.internal.RenderedClip
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.browserRenderOf
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.toByteArray
import dev.jordond.filmstrip.webcodecs.internal.render.BrowserCompositor
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

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
   * Whether anything in this composition contributes audible audio that this page can decode.
   *
   * A page with no `AudioDecoder` has nothing to play it with, so its preview runs silently on wall time.
   */
  public val carriesAudio: Boolean
    get() =
      browserCanDecodeAudio() &&
        render.audioFormat != null &&
        render.audioTracks.any { track -> track.clips.any { it.gain.peak > 0f && it.info.audio != null } }

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
   * Where the slot drawn last sits on the output timeline, in microseconds, or null while the
   * preview holds no compositor.
   */
  internal val drawnAtUs: Double? get() = compositor?.compositionUs

  /**
   * The texture of every table the compositor holds right now, and nothing when this preview holds no compositor.
   */
  internal val tableTextures: List<JsAny> get() = compositor?.tableTextures.orEmpty()

  /**
   * Draws the frame at [position] and reads it back, or null when the composition has none there.
   *
   * The position is snapped to the output frame grid first, so the decoded frame chosen is the one
   * the export pipeline fills that same slot with. A slot in front of a primary track that starts
   * late draws the fill alone, as the export does.
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
   * The composition time of the output slot the sync sample at or before [position] lands on, or [position] itself.
   *
   * What a relaxed seek lands on, which is never later than [position]. A sync sample in front of
   * the clip's own trim lands on the slot the clip opens on. A position in the held tail is its own
   * sync sample, since every slot there draws the primary's last frame, and one past the tail lands
   * on its last slot. A clip with no sync sample before the position, a source the container cannot
   * answer for, and a position in the gap before the primary track starts all come back as
   * [position] itself.
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
    edit: Composition,
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
    edit: Composition,
  ): Boolean {
    if (!resolved.drawsSameFormatAs(next)) return false

    val incoming = browserRenderOf(next, edit)
    if (incoming.clips.size != render.clips.size) return false

    // The fill is cleared into the canvas and a blurred one builds its own passes once, so a fill
    // that moved needs a fresh compositor. The decoded frames are untouched by it and stay.
    if (incoming.fill != render.fill) {
      compositor?.release()
      compositor = null
    }
    // The swap resolved every overlay again, so what the old plan uploaded is never drawn.
    compositor?.forgetUploads()
    render = incoming
    resolved = next
    return true
  }

  private suspend fun draw(position: Duration): PreviewFrame? {
    leadAt(position)?.let { return fillFrame(it) }
    val slot = slotAt(position) ?: return null
    val sourceUs = slot.sourceUs

    val ready =
      if (window.holds(slot.index)) {
        window.advanceTo(sourceUs)
        if (window.reaches(sourceUs)) window.nearest(sourceUs) else null
      } else {
        null
      }
    val own = if (ready == null) sampler(readbackSamplers, slot)?.nearestAt(sourceUs, slot.clip.trimEndUs) else null
    val sample = ready ?: own ?: return null
    return try {
      composite(slot, sample)
    } finally {
      own?.close()
    }
  }

  private suspend fun decodeAhead(from: Duration) {
    // Inside the leading gap this is the first clip's opening slot, so playback finds that clip
    // already decoded when it gets there.
    val slot = slotAt(from) ?: return
    if (!window.holds(slot.index) || window.startsAfter(slot.sourceUs)) {
      val sampler = sampler(playbackSamplers, slot) ?: return
      window.seat(slot.index, sampler.stream(slot.sourceUs, slot.clip.trimEndUs))
    }
    window.advanceTo(slot.sourceUs)
    window.fill()
  }

  private suspend fun syncSample(position: Duration): Duration {
    if (leadAt(position) != null) return position
    val slot = slotAt(position) ?: return position
    val landed = heldSlotAt(position) ?: syncSlotOf(slot) ?: return position
    // A position the grid rounds up sits just short of its own slot, and a seek never lands later
    // than it was asked to.
    return render.slotUs(landed).microseconds.coerceAtMost(position)
  }

  /**
   * The slot [position] lands on when that slot is in the composition's held tail, or null when it comes before the
   * tail or there is none.
   *
   * Every slot in the tail draws the primary's last frame, so each one is its own sync sample. A position past the tail
   * lands on its last slot, the last one the file has.
   */
  private fun heldSlotAt(position: Duration): Long? {
    val tail = resolved.heldTail
    val slot = outputSlotOf(position, render.frameRate)
    return if (tail.isEmpty() || slot < tail.first) null else minOf(slot, tail.last)
  }

  /**
   * The output slot the sync sample at or before [slot]'s source time lands on, or null when the source has none there
   * or cannot say.
   */
  private suspend fun syncSlotOf(slot: Slot): Long? {
    val reader = sources.open(slot.clip.source) ?: return null
    val keyUs = reader.keyFrameAt(slot.sourceUs) ?: return null
    if (keyUs > slot.sourceUs) return null

    // The sync sample's own time on the composition timeline, counted from where the clip's span
    // opens, then put on the grid the way every other time is. One in front of the clip's own trim
    // lands on the clip's first slot, so a seek never reaches back into the clip before it.
    val keyAt = slot.clip.spanStart + slot.clip.outputUsOf(keyUs - slot.clip.trimStartUs).microseconds
    return maxOf(outputSlotOf(keyAt, render.frameRate), slot.clip.firstSlot)
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
    pass.draw(sample, slot.outputUs, render.compositionOverlays, render.compositionPasses)
    return readBack(pass, slot.writtenUs)
  }

  /**
   * Draws the fill with no clip over it, for a slot in the gap before the primary track starts.
   */
  private suspend fun fillFrame(outputUs: Double): PreviewFrame {
    val pass = compositor()
    pass.drawFill(outputUs, render.compositionOverlays, render.compositionPasses)
    return readBack(pass, outputUs)
  }

  private suspend fun readBack(
    pass: BrowserCompositor,
    outputUs: Double,
  ): PreviewFrame {
    val shot = pass.present(outputUs, render.stepUs)
    try {
      val options = JsOptions().put("format", "RGBA").build()
      val target = Uint8Array(shot.allocationSize(options))
      shot.copyTo(target, options).await()
      return PreviewFrame(
        pixels = target.toByteArray(),
        size = Size(render.width, render.height),
        presentationTime = outputUs.microseconds,
      )
    } finally {
      shot.close()
    }
  }

  private fun compositor(): BrowserCompositor =
    compositor ?: BrowserCompositor
      .create(render.width, render.height, render.fill, render.hdrTransfer)
      .also { compositor = it }

  private suspend fun sampler(
    cache: MutableMap<Int, FrameSampler>,
    slot: Slot,
  ): FrameSampler? {
    cache[slot.index]?.let { return it }
    val reader = sources.open(slot.clip.source) ?: return null
    return reader.sampler(tenBit = render.readsPlanes(slot.clip))?.also { cache[slot.index] = it }
  }

  /**
   * The output time of the grid slot [position] lands on when that slot is one of the render's lead slots, or null
   * when it is not.
   *
   * The lead slots sit on the grid from zero and there are [BrowserRender.leadFrames] of them, the same count the
   * export encodes. A position on a later slot is left to [slotAt].
   */
  private fun leadAt(position: Duration): Double? {
    if (render.leadFrames == 0L || render.frameRate <= 0) return null

    val slot = outputSlotOf(position, render.frameRate)
    return if (slot < render.leadFrames) render.slotUs(slot) else null
  }

  /**
   * Which clip is on screen at [position], and which of that clip's output slots the position lands on.
   *
   * A clip that covers no slot is never on screen. A position past the end holds on the last frame the composition
   * draws rather than answering with nothing, so a playhead sitting on the duration still has a picture. One before
   * the first clip lands on that clip's opening slot, so [leadAt] is asked first wherever the gap is drawn.
   */
  private fun slotAt(position: Duration): Slot? {
    if (render.frameRate <= 0) return null
    val slot = outputSlotOf(position, render.frameRate)

    var held = NO_CLIP
    render.clips.forEachIndexed { index, clip ->
      if (clip.frames == 0L) return@forEachIndexed
      if (slot < clip.firstSlot + clip.frames) return Slot(index, clip, (slot - clip.firstSlot).coerceAtLeast(0))
      held = index
    }
    if (held == NO_CLIP) return null
    val last = render.clips[held]
    // a held slot reports the slot the export writes it on, up to the last one the file has.
    val lastWritten = last.firstSlot + last.frames + last.heldFrames - 1
    return Slot(held, last, last.frames - 1, writtenSlot = minOf(slot, lastWritten))
  }

  /**
   * One output slot of one clip: where to decode from, and what composition time it carries.
   */
  private inner class Slot(
    val index: Int,
    val clip: RenderedClip,
    private val slot: Long,
    private val writtenSlot: Long = clip.firstSlot + slot,
  ) {
    val sourceUs: Double get() = clip.sourceUsOf(slot, render.frameRate)

    val outputUs: Double get() = render.slotUs(clip.firstSlot + slot)

    val writtenUs: Double get() = render.slotUs(writtenSlot)
  }
}

/**
 * Lowers this plan for a preview, through the same lowering an export of [edit] runs.
 *
 * @param edit The edit this plan was resolved from. The lowering reads each clip's own geometry off
 *   it, which the resolved chain no longer carries as measurable specs.
 */
@InternalFilmstripApi
public fun ResolvedComposition.toBrowserPreview(edit: Composition): BrowserPreview =
  BrowserPreview(this, browserRenderOf(this, edit))

/**
 * How many decoded frames the look-ahead holds.
 *
 * A count of frames, so a clip at three frames a second holds four seconds of decoded video at
 * most. Under half a second at thirty frames a second, which is more than a preview polled at
 * display rate needs to stay ahead of its playhead, and few enough that a 4K clip's worth of
 * decoded frames does not swamp the tab.
 */
internal const val PREVIEW_LOOK_AHEAD: Int = 12
