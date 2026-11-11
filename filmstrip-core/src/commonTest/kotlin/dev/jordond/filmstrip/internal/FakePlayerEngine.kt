package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import kotlin.time.Duration

/**
 * An engine with no pipeline under it, which records what it is asked and pushes what a test tells it to.
 *
 * Loads are held until [answerLast] answers them, the way a real engine answers on a later turn.
 */
internal class FakePlayerEngine : PlayerEngine {
  val listeners: MutableList<EngineListener> = mutableListOf()
  val requests: MutableList<SetCompositionRequest> = mutableListOf()
  val seeks: MutableList<Pair<Duration, SeekAccuracy>> = mutableListOf()
  val calls: MutableList<String> = mutableListOf()
  var cancelledRequests: Int = 0
    private set
  var disposed: Boolean = false
    private set

  private val callbacks: MutableList<SetCompositionCallback> = mutableListOf()

  override val id: String = "fake"

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  fun push(event: EngineListener.() -> Unit) {
    listeners.toList().forEach(event)
  }

  fun answerLast(result: SetCompositionResult) {
    callbacks.last().onResult(result)
  }

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    requests += request
    callbacks += callback
    return Cancellable { cancelledRequests++ }
  }

  override fun addListener(listener: EngineListener): Cancellable {
    listeners += listener
    return Cancellable { listeners -= listener }
  }

  override fun play() {
    calls += "play"
  }

  override fun pause() {
    calls += "pause"
  }

  override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ) {
    seeks += position to accuracy
  }

  override fun stepFrames(frames: Int) {
    calls += "step $frames"
  }

  override fun beginScrub() {
    calls += "begin scrub"
  }

  override fun endScrub() {
    calls += "end scrub"
  }

  override fun beginShuttle(rate: Float) {
    calls += "shuttle $rate"
  }

  override fun endShuttle() {
    calls += "endShuttle"
  }

  override fun setVolume(volume: Float) {
    calls += "volume $volume"
  }

  override fun setLoopRange(range: TimeRange?) {
    calls += "loop $range"
  }

  override fun setPlaybackRange(range: TimeRange?) {
    calls += "range $range"
  }

  override fun setQualityPolicy(policy: PreviewQualityPolicy) {
    calls += "quality $policy"
  }

  override fun setExportSpec(spec: ExportSpec) {
    calls += "export ${spec.hdr}"
  }

  override fun dispose() {
    disposed = true
  }
}
