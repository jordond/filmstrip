package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.playback.contract.EngineUnderTest
import dev.jordond.filmstrip.playback.contract.PlayerEngineContractTest
import dev.jordond.filmstrip.playback.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.playback.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.player.PlayerConfig
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * The shared engine contracts, run against a real ffmpeg on the desktop host.
 *
 * What is not covered here, named rather than left out:
 * - [Interruption.AudioFocusLost] and [Interruption.OutputRouteChanged], which need an audio
 *   session. This backend opens no audio device at all, so neither occasion exists for it to watch:
 *   the interruption table records the desktop column as empty for both.
 * - [Interruption.AppBackgrounded], which needs an application lifecycle. A headless JVM has none,
 *   and nothing in the JDK reports a desktop process being put down.
 * - [Interruption.AutoplayBlocked], which is the browser's alone.
 * - [Interruption.IncomingCall] and [Interruption.MediaServicesReset], which need real telephony and
 *   a dead media daemon and so are unreachable on every platform.
 *
 * The list is empty rather than faked. Every occasion this backend could report would have to be
 * invented here first, and a suite that stages an occasion the engine does not watch is testing the
 * test.
 */
class JvmEngineContractTest : PlayerEngineContractTest() {
  override fun createEngine(scope: CoroutineScope): EngineUnderTest = JvmEngineUnderTest(scope)

  override val fixture: EditComposition = jvmFixtureComposition()
}

/**
 * The desktop engine wrapped so a contract suite can count the processes it spawned.
 *
 * No platform duration, and none is claimed. ffmpeg is driven here as a frame pump: the stream it
 * opens carries a start position and frames, and nothing on it reports how long the render runs.
 * The only figure a process could produce is the one the graph was built from, which would make the
 * suite's cross check compare filmstrip's answer with itself.
 */
internal class JvmEngineUnderTest(
  scope: CoroutineScope,
) : EngineUnderTest {
  override val engine: FfmpegPlayerEngine =
    FfmpegPlayerEngine(
      parent = scope,
      planner = FfmpegPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
    )

  override val platformLoads: Int get() = engine.platformLoads

  override val reportsPlatformDuration: Boolean = false

  override val platformDuration: Duration? = null
}
