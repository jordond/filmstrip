@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The composition's audio, played through a live graph, and the clock that graph carries.
 *
 * The graph is the export mixer's: the same placement, the same decode and the same wiring, fed a
 * live context instead of an offline one. Everything runs through one master gain, so monitor
 * volume is one write however many clips are sounding.
 *
 * Clips are scheduled a window at a time rather than all at once, so a long composition costs one
 * window of decoded audio however long it runs.
 *
 * A context starts suspended until the page has had a user gesture. [resume] is issued and never
 * waited on, because the Web Audio spec parks its promise rather than rejecting it when a page is
 * not allowed to start, and a caller that waited would hang for as long as the user did not click.
 * [isRunning] and [onStateChanged] are the signals worth reading instead.
 */
@InternalFilmstripApi
public class BrowserAudioPreview internal constructor(
  private val tracks: () -> List<ResolvedTrack>,
  private val sources: SourceCache,
) {
  private val context = AudioContext()
  private val master = context.createGain()
  private val sounding = mutableListOf<AudioBufferSourceNode>()

  private var anchorContextTime = 0.0
  private var anchorPosition = Duration.ZERO
  private var scheduledTo = Duration.ZERO
  private var running = false
  private var released = false

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
    context.resume()
    anchorContextTime = context.currentTime
    anchorPosition = from
    scheduledTo = from
    running = true
    pump(from)
  }

  /**
   * Schedules whatever has come inside the look-ahead since the last call.
   */
  public suspend fun pump(position: Duration) {
    if (released || !running) return
    val horizon = position + SCHEDULE_AHEAD
    while (scheduledTo < horizon) {
      val to = minOf(scheduledTo + SCHEDULE_WINDOW, horizon)
      scheduleBetween(scheduledTo, to)
      scheduledTo = to
    }
  }

  /**
   * Silences everything scheduled and stops the clock advancing the playhead.
   */
  public fun stop() {
    running = false
    stopSounding()
  }

  public fun release() {
    if (released) return
    released = true
    stop()
    master.disconnect()
    context.close()
  }

  /**
   * Schedules every clip that sounds in `[from, to)`, decoding only the slice each one contributes.
   */
  private suspend fun scheduleBetween(
    from: Duration,
    to: Duration,
  ) {
    BrowserAudioMix.placed(tracks()).forEach { placed ->
      val clip = placed.clip
      val period = clip.duration
      if (clip.gain <= 0f || period <= Duration.ZERO) return@forEach

      val start = maxOf(placed.offset, from)
      val end = minOf(placed.offset + period, to)
      if (end <= start) return@forEach

      val audioTrack = sources.open(clip.source)?.audioTrack() ?: return@forEach
      val into = clip.start + (start - placed.offset)
      val until = clip.start + (end - placed.offset)
      val buffer =
        BrowserAudioMix.decode(audioTrack, into.asSeconds(), until.asSeconds(), context) ?: return@forEach

      sounding +=
        BrowserAudioMix.schedule(
          context = context,
          buffer = buffer,
          gain = clip.gain,
          offsetSeconds = contextTimeFor(start),
          looping = false,
          into = master,
        )
    }
  }

  /**
   * Where [position] sits on the context's own clock, which is what a scheduled node is started at.
   */
  private fun contextTimeFor(position: Duration): Double = anchorContextTime + (position - anchorPosition).asSeconds()

  private fun stopSounding() {
    sounding.forEach { node ->
      node.stop()
      node.disconnect()
    }
    sounding.clear()
  }

  private fun Duration.asSeconds(): Double = toDouble(DurationUnit.SECONDS)

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
