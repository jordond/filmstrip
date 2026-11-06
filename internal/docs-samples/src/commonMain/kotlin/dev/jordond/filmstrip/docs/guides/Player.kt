package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.VideoPlayer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun openPlayer(
  filmstrip: Filmstrip,
  composition: EditComposition,
): VideoPlayer {
  // region open
  val player = filmstrip.preview(composition)
  player.play()
  // endregion
  return player
}

internal fun cappedPlayer(
  filmstrip: Filmstrip,
  composition: EditComposition,
): VideoPlayer {
  // region quality
  val player =
    filmstrip.preview(
      composition,
      PlayerConfig(qualityPolicy = PreviewQualityPolicy.CapHeight(720)),
    )
  // endregion
  return player
}

internal suspend fun observeState(player: VideoPlayer) {
  // region state
  player.state.collect { state ->
    when (val status = state.status) {
      is PlaybackStatus.Error -> println(status.error.message)
      PlaybackStatus.Ended -> println("Finished")
      else -> println("Playing: ${state.isPlaying}, busy: ${state.isBusy}")
    }
  }
  // endregion
}

internal suspend fun observeEvents(player: VideoPlayer) {
  // region events
  player.events.collect { event ->
    when (event) {
      is PlaybackEvent.SeekCompleted -> println("Landed on ${event.position}")
      is PlaybackEvent.RangeLooped -> println("Wrapped in ${event.range}")
      is PlaybackEvent.Failed -> println(event.error.message)
      else -> Unit
    }
  }
  // endregion
}

internal suspend fun observePlayhead(player: VideoPlayer) {
  // region playhead
  player.positionFlow(tick = 100.milliseconds).collect { position ->
    println(position)
  }
  // endregion
}

internal fun transport(player: VideoPlayer) {
  // region transport
  player.seekTo(12.seconds)
  player.seekTo(30.seconds, SeekAccuracy.Nearest)
  player.setLoopRange(TimeRange(10.seconds, 20.seconds))
  player.setVolume(0.5f)

  if (player.features.supports(PlayerFeature.FrameStepping)) {
    player.stepFrames(1)
  }
  // endregion
}

internal suspend fun swapComposition(
  player: VideoPlayer,
  edited: EditComposition,
) {
  // region swap
  when (val result = player.setComposition(edited)) {
    is SetCompositionResult.Success -> println("Now showing the edit")
    is SetCompositionResult.Failure -> println(result.error.message)
    SetCompositionResult.Superseded -> Unit
  }
  // endregion
}
