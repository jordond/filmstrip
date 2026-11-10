@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.preview

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.audio.BrowserAudioMix
import dev.jordond.filmstrip.webcodecs.internal.audio.ClipStretch
import dev.jordond.filmstrip.webcodecs.internal.audio.StretchedAudio
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBufferSourceNode
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioContext
import dev.jordond.filmstrip.webcodecs.internal.interop.InputAudioTrack
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The composition's audio, played through a live graph, and the clock that graph carries.
 *
 * The graph is the export mixer's: the same placement, the same decode, the same stretcher and the same
 * wiring, fed a live context instead of an offline one. Everything runs through one master gain, so
 * monitor volume is one write however many clips are sounding.
 *
 * Clips are scheduled a window at a time rather than all at once, so a long composition costs one
 * window of decoded audio however long it runs. A clip at speed 1 decodes each window's slice as it is.
 * A clip at another speed keeps one [ClipStretch] open for as long as it plays, so one window joins the
 * next with no click and a decoder carries on instead of starting again. Asking for a new start throws
 * those away, and the clip opens again where the new start lands.
 *
 * A context starts suspended until the page has had a user gesture. [resume] is issued and never
 * waited on, because the Web Audio spec parks its promise rather than rejecting it when a page is
 * not allowed to start, and a caller that waited would hang for as long as the user did not click.
 * [isRunning] and [onStateChanged] are the signals worth reading instead.
 *
 * @param onScheduled Told about each stretch of audio a clip at a speed other than 1 schedules.
 */
@InternalFilmstripApi
public class BrowserAudioPreview internal constructor(
  private val tracks: () -> List<ResolvedTrack>,
  private val sources: SourceCache,
  private val onScheduled: (StretchedAudio) -> Unit = {},
) {
  private val context = AudioContext()
  private val master = context.createGain()
  private val sounding = mutableListOf<AudioBufferSourceNode>()

  private var anchorContextTime = 0.0
  private var anchorPosition = Duration.ZERO
  private var scheduledTo = Duration.ZERO
  private var running = false
  private var released = false
  private var run = PlaybackRun()

  init {
    master.connect(context.destination)
  }

  /**
   * Whether the browser is letting this graph run. False means the page still owes a user gesture.
   */
  public val isRunning: Boolean get() = context.state == RUNNING

  /**
   * Where the playhead is, read off the audio hardware's own clock.
   *
   * A suspended context does not advance its clock, so this holds still until the browser lets the
   * graph run rather than drifting ahead of the audio.
   */
  public val position: Duration
    get() = anchorPosition + (context.currentTime - anchorContextTime).seconds

  /**
   * Calls [listener] whenever the browser changes its mind about letting the graph run.
   */
  public fun onStateChanged(listener: () -> Unit) {
    context.addEventListener(STATE_CHANGE, listener)
  }

  /**
   * Sets monitor volume, which a suspended context accepts and applies when it resumes.
   */
  public fun setVolume(volume: Float) {
    master.gain.value = volume.coerceIn(0f, 1f)
  }

  /**
   * Asks the browser to run the graph and schedules the first window from [from].
   */
  public suspend fun start(from: Duration) {
    if (released) return
    stopSounding()
    run.close()
    run = PlaybackRun()
    context.resume()
    anchorContextTime = context.currentTime
    anchorPosition = from
    scheduledTo = from
    running = true
    pump(from)
  }

  /**
   * Schedules whatever has come inside the look-ahead since the last call.
   *
   * A pass can suspend on a decode, so a [stop] or [release] that lands mid-pump ends it before the
   * next window is scheduled, and drops what the decode comes back with. A pump that arrives while
   * another is still working leaves it to finish, since the two would otherwise feed one stretcher.
   */
  public suspend fun pump(position: Duration) {
    val current = run
    if (current.pumping) return
    current.pumping = true
    try {
      val horizon = position + SCHEDULE_AHEAD
      while (!released && running && current === run && scheduledTo < horizon) {
        val to = minOf(scheduledTo + SCHEDULE_WINDOW, horizon)
        scheduleBetween(scheduledTo, to, current)
        if (current === run) scheduledTo = to
      }
    } finally {
      current.pumping = false
    }
  }

  /**
   * Silences everything scheduled and stops the clock advancing the playhead.
   */
  public fun stop() {
    running = false
    stopSounding()
    run.close()
    run = PlaybackRun()
  }

  public fun release() {
    if (released) return
    released = true
    stop()
    master.disconnect()
    context.close()
  }

  /**
   * Schedules every clip that sounds in `[from, to)`, decoding only the slice each one contributes, or
   * pulling it out of the [ClipStretch] a sped clip keeps in [current]. A run a [stop] or a new [start]
   * replaced while this was waiting on a decode schedules nothing more.
   */
  private suspend fun scheduleBetween(
    from: Duration,
    to: Duration,
    current: PlaybackRun,
  ) {
    BrowserAudioMix.placed(tracks()).forEach { placed ->
      val clip = placed.clip
      val period = clip.outputDuration
      if (clip.gain.peak <= 0f || period <= Duration.ZERO) return@forEach

      val start = maxOf(placed.offset, from)
      val closes = placed.offset + period
      val end = minOf(closes, to)
      if (end <= start) return@forEach

      val audioTrack = sources.open(clip.source)?.audioTrack(clip.audioTrack) ?: return@forEach
      val slice =
        if (clip.speed == 1.0) {
          val into = clip.start + (start - placed.offset)
          val until = clip.start + (end - placed.offset)
          BrowserAudioMix
            .decode(audioTrack, into.asSeconds(), until.asSeconds(), context)
            ?.let { ScheduledSlice(it, start.asSeconds()) }
        } else {
          stretchedSlice(clip, start, end, closes, audioTrack, current)
        }
      if (current !== run) return
      if (slice == null) return@forEach

      // The preview does not follow a ramp yet, so a clip's curve is sampled where the slice opens
      // and held flat across it. Auditioning a fade is a later change.
      val level = ResolvedGain.constant(clip.gain.gainAt(start - placed.offset), Duration.ZERO, end - start)

      sounding +=
        BrowserAudioMix.schedule(
          context = context,
          buffer = slice.buffer,
          gain = level,
          offsetSeconds = contextTimeFor(slice.opensAtSeconds),
          into = master,
        )
    }
  }

  // The clip's stretcher carries on from the last slice, and goes once the clip has played to where it closes.
  private suspend fun stretchedSlice(
    clip: ResolvedClip,
    start: Duration,
    end: Duration,
    closes: Duration,
    audioTrack: InputAudioTrack,
    current: PlaybackRun,
  ): ScheduledSlice? {
    val pass = PlayedPass.of(clip)
    val stretch =
      current.stretches[pass] ?: ClipStretch.of(clip, start, audioTrack).also { opened ->
        if (current !== run) return null
        current.stretches[pass] = opened
      }
    val audio = stretch.between(start, end)
    if (current !== run) return null
    if (end >= closes) current.stretches.remove(pass)?.close()
    if (audio == null) return null
    onScheduled(audio)
    return ScheduledSlice(audio.toBuffer(context), audio.opensAtSeconds)
  }

  /**
   * Where [positionSeconds] sits on the context's own clock, which is what a scheduled node is started at.
   */
  private fun contextTimeFor(positionSeconds: Double): Double =
    anchorContextTime + positionSeconds - anchorPosition.asSeconds()

  private fun stopSounding() {
    sounding.forEach { node ->
      node.stop()
      node.disconnect()
    }
    sounding.clear()
  }

  private fun Duration.asSeconds(): Double = toDouble(DurationUnit.SECONDS)

  /**
   * The stretchers one uninterrupted stretch of playback holds open, which go when it is stopped or started over.
   */
  private class PlaybackRun {
    val stretches = mutableMapOf<PlayedPass, ClipStretch>()
    var pumping = false

    fun close() {
      stretches.values.forEach { it.close() }
      stretches.clear()
    }
  }

  /**
   * The audio one clip contributes to a window, and where on the composition timeline it opens.
   */
  private class ScheduledSlice(
    val buffer: AudioBuffer,
    val opensAtSeconds: Double,
  )

  /**
   * What says two resolved clips are the same pass, which holds across a parameter swap that resolves them all again.
   */
  private data class PlayedPass(
    val source: MediaSource,
    val start: Duration,
    val end: Duration,
    val opensAt: Duration,
    val speed: Double,
  ) {
    companion object {
      fun of(clip: ResolvedClip): PlayedPass =
        PlayedPass(clip.source, clip.start, clip.end, clip.span.start, clip.speed)
    }
  }

  private companion object {
    const val RUNNING = "running"
    const val STATE_CHANGE = "statechange"

    /**
     * How far past the playhead audio is scheduled. Long enough that a wake-up that is late by a
     * frame or two never leaves a gap, short enough that a seek throws away little work.
     */
    val SCHEDULE_AHEAD = 500.milliseconds

    /**
     * How much of the timeline one scheduling pass covers.
     */
    val SCHEDULE_WINDOW = 250.milliseconds
  }
}
