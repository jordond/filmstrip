package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.EngineUnderTest
import dev.jordond.filmstrip.player.contract.FailingItem
import dev.jordond.filmstrip.player.contract.PlayerEngineContractTest
import dev.jordond.filmstrip.player.internal.FfmpegPlayerEngine
import dev.jordond.filmstrip.player.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.player.internal.PreviewPlan
import kotlinx.coroutines.CoroutineScope
import java.io.File
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
class JvmEngineContractMediaTest : PlayerEngineContractTest() {
  override fun createEngine(scope: CoroutineScope): EngineUnderTest = JvmEngineUnderTest(scope)

  override fun createFailingItem(scope: CoroutineScope): FailingItem {
    val source = File.createTempFile("filmstrip-vanishing", ".mp4").also { it.deleteOnExit() }
    val clip = File(jvmFixtureClip())
    clip.copyTo(source, overwrite = true)
    return FailingItem(
      subject = JvmEngineUnderTest(scope, VanishingSourcePlanner(source)),
      composition = compositionOf(source),
      restoreSource = { clip.copyTo(source, overwrite = true) },
    )
  }

  override val fixture: EditComposition = jvmFixtureComposition()

  override val bytesFixture: EditComposition = jvmFixtureBytesComposition()

  override val claimsLiveParameterRedraw: Boolean = false

  override val fixtureFrameRate: Int = FIXTURE_RATE
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
  planner: FfmpegPreviewPlanner = FfmpegPreviewPlanner(CONTRACT_COMPONENTS),
) : EngineUnderTest {
  override val engine: FfmpegPlayerEngine =
    FfmpegPlayerEngine(
      parent = scope,
      planner = planner,
      config = PlayerConfig(),
    )

  override val platformLoads: Int get() = engine.platformLoads

  override val reportsPlatformDuration: Boolean = false

  override val platformDuration: Duration? = null

  override suspend fun awaitQuiet() {
    engine.awaitQuiet()
  }
}

/**
 * Plans an edit as usual and takes [source] away before the first process opens, so ffmpeg itself fails on the plan.
 *
 * Only the first one, so a source put back afterwards stays.
 */
private class VanishingSourcePlanner(
  private val source: File,
) : FfmpegPreviewPlanner(CONTRACT_COMPONENTS) {
  private var vanished = false

  override suspend fun open(
    plan: PreviewPlan,
    composition: EditComposition,
    at: Duration,
  ): PreviewStreamResult {
    if (!vanished) source.delete()
    vanished = true
    return super.open(plan, composition, at)
  }
}

private fun compositionOf(source: File): EditComposition =
  EditComposition(
    tracks = listOf(Track(listOf(Clip(MediaSource.of(source.absolutePath), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))),
  )
