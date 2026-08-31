package dev.jordond.filmstrip.playback

import android.content.Intent
import android.media.AudioManager
import dev.jordond.filmstrip.playback.contract.EngineUnderTest
import dev.jordond.filmstrip.playback.contract.Interruption
import dev.jordond.filmstrip.playback.internal.Media3PlayerEngine
import dev.jordond.filmstrip.playback.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * The media3 engine wrapped so a contract suite can count what CompositionPlayer underneath it did.
 *
 * @param scope The dispatcher everything a listener sees is confined to.
 * @param planner Lowers an edit, or refuses it. Replaced by a suite that needs one to misbehave.
 */
internal class AndroidEngineUnderTest(
  scope: CoroutineScope,
  planner: Media3PreviewPlanner = Media3PreviewPlanner(CONTRACT_COMPONENTS),
) : EngineUnderTest {
  override val engine: Media3PlayerEngine =
    Media3PlayerEngine(
      parent = scope,
      context = contractContext(),
      planner = planner,
      config = PlayerConfig(),
    )

  override val platformLoads: Int get() = engine.platformLoads

  override val platformDuration: Duration? get() = engine.platformDuration

  override val reportsPlatformDuration: Boolean = true

  /**
   * Hands the engine's receiver the intent the system sends as an output route goes away.
   *
   * The delivery ahead of it belongs to Android and no test can run it: the action is a protected
   * broadcast, so a process that is not the system is refused when it tries to send one.
   */
  override fun stage(interruption: Interruption) {
    when (interruption) {
      Interruption.OutputRouteChanged -> {
        engine.interruptions.receiver.onReceive(
          contractContext(),
          Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
      }
      else -> {
        throw NotImplementedError("Android raises nothing for $interruption.")
      }
    }
  }
}
