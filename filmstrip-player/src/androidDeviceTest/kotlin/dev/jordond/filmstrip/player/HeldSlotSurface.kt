package dev.jordond.filmstrip.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.media3.transformer.CompositionPlayer
import androidx.test.core.app.ActivityScenario
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.internal.engineFor
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

// A clip and the source it is cut from, with the slots of it the output holds a sample for. A source at another rate
// than the output's follows a clip at the output's, which is what sets the rate.
internal enum class Case(
  internal val speed: Double,
  internal val sourceRate: Int?,
) {
  Full(1.0, null),
  Half(0.5, null),
  Quarter(0.25, null),
  Slower(1.0, SLOWER_RATE),
  ;

  internal val lead: Duration get() = if (sourceRate == null) Duration.ZERO else LEAD

  suspend fun source(): MediaSource = sourceRate?.let { AndroidShortClips.sourceAt(it) } ?: androidFixtureClip()

  fun edit(
    source: MediaSource,
    effects: List<Brightness> = emptyList(),
  ): Composition {
    val clip = Clip(source, TimeRange.of(Duration.ZERO, CLIP_LENGTH), effects = listOf(FIXTURE_PAN), speed = speed)
    val opening = Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, lead))
    return Composition(
      tracks = listOf(Track(if (sourceRate == null) listOf(clip) else listOf(opening, clip))),
      effects = effects,
    )
  }

  /**
   * The source frame the shared rule shows on each slot, where the slots of the opening each show a frame of their
   * own.
   */
  fun frames(): List<Int> {
    val rate = sourceRate ?: FIXTURE_FRAME_RATE
    val samples = (0..SOURCE_FRAMES).map { FIXTURE_FRAME_STEP * FIXTURE_FRAME_RATE / rate * it }
    val leadSlots = outputSlotOf(lead, FIXTURE_FRAME_RATE).toInt()
    val slots = outputSlotOf(lead + CLIP_LENGTH / speed, FIXTURE_FRAME_RATE).toInt()
    return (0 until slots).map { slot ->
      if (slot < leadSlots) {
        -slot - 1
      } else {
        val time = sourceTimeOfSlot(Duration.ZERO, lead, speed, slot.toLong(), FIXTURE_FRAME_RATE, Duration.ZERO)
        checkNotNull(nearestSourceFrame(samples, time))
      }
    }
  }
}

internal fun onHeldSurface(
  case: Case,
  body: suspend CoroutineScope.(HeldRig) -> Unit,
) = runTest(timeout = TIMEOUT) {
  withContext(Dispatchers.Default) {
    val source = case.source()
    val edit = case.edit(source)
    ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
      var host: SurfaceHostActivity? = null
      scenario.onActivity { activity -> host = activity }
      val activity = withClue("the host activity never started") { host.shouldNotBeNull() }
      withClue("the host window produced no surface") { activity.awaitSurface().shouldBeTrue() }
      activity.resizeSurface(WIDE.width, WIDE.height)
      awaitOrFail("the view to take its shape") { activity.surfaceView.width == WIDE.width }

      val player = Filmstrip(contractContext()) { playerBackend() }.preview(edit)
      val events = CopyOnWriteArrayList<PlaybackEvent>()
      val collector = launch { player.events.collect { events += it } }
      try {
        body(HeldRig(activity.surfaceView, player, events, case, edit) { effects -> case.edit(source, effects) })
      } finally {
        collector.cancel()
        player.close()
      }
    }
  }
}

internal class HeldRig(
  val view: SurfaceView,
  val player: VideoPlayer,
  internal val events: List<PlaybackEvent>,
  case: Case,
  internal val edit: Composition,
  internal val editWith: (List<Brightness>) -> Composition,
) {
  internal val frames = case.frames()

  // The slot the second file of the edit opens on, or -1 where there is one file.
  private val leadSlots: Int =
    if (case.lead == Duration.ZERO) -1 else outputSlotOf(case.lead, FIXTURE_FRAME_RATE).toInt()

  fun brighter(): Composition = editWith(listOf(Brightness(BRIGHTER)))

  val engine
    get() = withClue("no engine") { engineFor(player.nativePlayer as CompositionPlayer).shouldNotBeNull() }
  internal val seeksCompleted: Int get() = events.count { it is PlaybackEvent.SeekCompleted }

  fun slotTime(slot: Int): Duration = outputSlotTime(slot.toLong(), FIXTURE_FRAME_RATE)

  // The first slot after the opening whose sample the slot before it shows too.
  fun repeatSlot(): Int = (FIRST_SLOT + 1..frames.lastIndex).first { frames[it] == frames[it - 1] }

  // The slot the sample of the first repeat is written on.
  fun firstSlot(): Int = repeatSlot() - 1

  fun lastSlot(): Int = frames.lastIndex

  // What lets go of the surface the preview was attached to, or null before it was.
  var attachment: Cancellable? = null
    private set

  suspend fun ready() {
    if (player.state.value.status == PlaybackStatus.Ready && events.any { it is PlaybackEvent.FirstFrameRendered }) {
      return
    }
    attachment = player.attachPreviewSurface(view)
    awaitOrFail("the preview to become ready") { player.state.value.status == PlaybackStatus.Ready }
    player.play()
    awaitOrFail("the composition to render") { events.any { it is PlaybackEvent.FirstFrameRendered } }
    player.pause()
    delay(SETTLE)
  }

  /**
   * Lets go of the surface and hands the preview the same one again, as a view that is torn down and rebuilt does.
   *
   * @param whileDetached Runs once the surface has gone and before it is handed back.
   */
  suspend fun reattach(whileDetached: () -> Unit = {}) {
    attachment?.cancel()
    delay(SETTLE)
    whileDetached()
    attachment = player.attachPreviewSurface(view)
    delay(SETTLE)
  }

  suspend fun settle(slot: Int) {
    awaitOrFail("the playhead to reach slot $slot, which reads ${player.currentPosition()}") {
      outputSlotOf(player.currentPosition(), FIXTURE_FRAME_RATE) == slot.toLong() && !player.state.value.isStalled
    }
    delay(SETTLE)
  }

  /**
   * Plays from the start until the playhead has passed slot [past], pauses, and lets the surface settle.
   *
   * @return the slot the playhead reports once it has settled.
   */
  suspend fun playThenPause(past: Int): Int {
    playFromStart()
    pausedAfter(past)
    return reportedSlot()
  }

  /**
   * Plays from the start until the playhead has passed slot [past], and leaves the player playing.
   */
  suspend fun playPast(past: Int) {
    playFromStart()
    awaitOrFail("the playhead to pass slot $past, which reads ${player.currentPosition()}") {
      player.currentPosition() >= slotTime(past)
    }
  }

  private suspend fun playFromStart() {
    ready()
    player.seekTo(Duration.ZERO, SeekAccuracy.Exact)
    awaitOrFail("the seek back to the start") { player.currentPosition() == Duration.ZERO }
    delay(SETTLE)
    player.play()
  }

  /**
   * Waits for the playhead to pass slot [past], then pauses and lets the surface settle.
   */
  suspend fun pausedAfter(past: Int) {
    awaitOrFail("the playhead to pass slot $past, which reads ${player.currentPosition()}") {
      player.currentPosition() >= slotTime(past)
    }
    player.pause()
    delay(SETTLE)
  }

  fun reportedSlot(): Int = outputSlotOf(player.currentPosition(), FIXTURE_FRAME_RATE).toInt()

  suspend fun seekAndShow(slot: Int) {
    ready()
    seekAndSettle(slot)
    shouldShow(slot)
  }

  /**
   * Seeks to [slot] and waits for the seek to complete and the surface to settle.
   *
   * The playhead reads a seek's target from the moment it is asked for, so the completion is what says a frame was
   * drawn for it.
   */
  suspend fun seekAndSettle(
    slot: Int,
    accuracy: SeekAccuracy = SeekAccuracy.Exact,
  ) {
    val seeks = seeksCompleted
    player.seekTo(slotTime(slot), accuracy)
    awaitOrFail("the seek to slot $slot to land") { seeksCompleted > seeks }
    settle(slot)
  }

  suspend fun stepAndShow(
    slot: Int,
    forward: Boolean = true,
  ) {
    player.stepFrames(if (forward) 1 else -1)
    settle(slot)
    shouldShow(slot)
  }

  /**
   * Loads [of], waits for the engine to report the frame it drew for it, and holds the surface to [slot] of it.
   *
   * The surface is read once the report has come and a short settle has passed, so a redraw that is late or lost fails
   * on its own wait and not behind a retry.
   */
  @OptIn(InternalFilmstripApi::class)
  suspend fun redrawnShows(
    slot: Int,
    of: Composition,
  ) {
    val before = player.frameRevision().first()
    engine.awaitComposition(of).shouldBeInstanceOf<SetCompositionResult.Success>()
    awaitOrFail("the engine to report the frame it redrew") { player.frameRevision().first() > before }
    delay(SETTLE)
    shouldShow(slot, of)
  }

  /**
   * The slots of the hold [slot] is in: the first slot its sample is written on to the last slot that shows it.
   */
  fun holdOf(slot: Int): IntRange {
    var first = slot
    while (first > 0 && frames[first - 1] == frames[slot]) first--
    var last = slot
    while (last < frames.lastIndex && frames[last + 1] == frames[slot]) last++
    return first..last
  }

  /**
   * Plays on from here until the playhead passes slot [past], pauses, and returns each picture the surface showed
   * meanwhile. See [picturesDuring].
   */
  suspend fun picturesPlayingUntilPast(past: Int): List<TestFrame> =
    picturesDuring {
      player.play()
      pausedAfter(past)
    }

  /**
   * Runs [block] and returns each picture the surface showed meanwhile, in order, with the one it settled on last.
   *
   * The surface is copied as fast as it can be, and a picture that is the one before it is not listed again. The one
   * standing when [block] began is not listed either, since nothing [block] did drew it. A picture drawn and replaced
   * between two copies is not seen, so this lists what was drawn at most.
   */
  suspend fun picturesDuring(block: suspend () -> Unit): List<TestFrame> =
    coroutineScope {
      val seen = mutableListOf<TestFrame>()
      val standing = view.copyPixels(FIXTURE_FRAME)

      fun note(frame: TestFrame) {
        if (seen.lastOrNull()?.pixels?.contentEquals(frame.pixels) != true) seen += frame
      }
      val sampler =
        launch(Dispatchers.Default) {
          while (isActive) note(view.copyPixels(FIXTURE_FRAME))
        }
      try {
        block()
      } finally {
        sampler.cancelAndJoin()
      }
      note(view.copyPixels(FIXTURE_FRAME))
      if (seen.firstOrNull()?.pixels?.contentEquals(standing.pixels) == true) seen.drop(1) else seen
    }

  /**
   * Holds [pictures], what the surface showed after a play from slot [from] that was paused, to being the clip's own
   * slots, drawn whole, in the order they are played.
   *
   * Each picture is matched to the export of the slot nearest it among the slots from the first of the hold [from] is
   * in to [reported]. That window is the whole of what a play can show: the hold's sample is drawn again from its first
   * slot, and a pause lands the surface on the slot the playhead reads, so nothing past it is drawn. A picture no slot
   * in it resembles is a half-drawn one, or one of the sample before the hold. The first picture has to be a slot of
   * that hold, which is the sample being drawn again and not the play carrying on from the slot after it. The matched
   * slots then have to run forward, never back to an earlier one, and the last has to be [reported], a slot after
   * [from]. A slot missing from the run is not held against the play, since the copies can miss a frame.
   *
   * @return the slot the surface settled on.
   */
  suspend fun shouldHavePlayedOn(
    pictures: List<TestFrame>,
    from: Int,
    reported: Int,
  ): Int {
    val hold = holdOf(from)
    val window = hold.first..reported
    withClue("the play drew no picture") { pictures.isNotEmpty().shouldBeTrue() }
    val matched =
      pictures.map { picture ->
        window.maxBy { slot -> compareFrames(cachedExport(slot), picture).psnrDb }.also { slot ->
          assertFramesSimilar(
            cachedExport(slot),
            picture,
            MIN_PSNR_DB,
            MIN_SSIM,
            "a picture drawn after the play is no slot of $window, its nearest being slot $slot",
          )
        }
      }
    withClue("the slots drawn after the play, in order, were $matched") {
      withClue("the first is no slot of the hold $hold") { (matched.first() in hold).shouldBeTrue() }
      matched.zipWithNext().all { (before, after) -> after >= before }.shouldBeTrue()
      matched.last() shouldBe reported
      (reported > from).shouldBeTrue()
    }
    return matched.last()
  }

  private val exports = mutableMapOf<Int, TestFrame>()

  private suspend fun cachedExport(slot: Int): TestFrame = exports.getOrPut(slot) { exportAt(slot) }

  /**
   * The offset from [slot] of the slot whose export the surface is nearest, within two slots either side.
   */
  suspend fun nearestOffset(slot: Int): Int {
    val shown = view.copyPixels(FIXTURE_FRAME)
    return (-2..2).maxBy { compareFrames(exportAt((slot + it).coerceAtLeast(0)), shown).psnrDb }
  }

  suspend fun exportAt(
    slot: Int,
    of: Composition = edit,
  ) = androidExportFrame(of, slotTime(slot))

  /**
   * Holds the surface to the export of [of] at [slot], and to being nearer it than the export of any slot within two
   * either side.
   *
   * The floors are [surfaceFloorsAt]'s, which lets a slot the encoder opens a file or a cut with sit further off.
   * Neither floor tells a slot from the one beside it: a neighbour reads up to 35 dB where the clip pans and 39.9 dB
   * and 0.977 in the lead of the slower case, which does not, where the slot's own export reads 43 dB. So the nearest
   * export being the slot's own is what holds off a surface a slot behind or ahead of the playhead.
   */
  suspend fun shouldShow(
    slot: Int,
    of: Composition = edit,
  ) {
    val expected = exportAt(slot, of)
    val shown = view.copyPixels(FIXTURE_FRAME)
    val nearby =
      (-2..2).filter { slot + it in frames.indices }.map { offset ->
        offset to compareFrames(exportAt(slot + offset, of), shown).psnrDb
      }
    val cuts = listOfNotNull(leadSlots.toLong().takeIf { it > 0 })
    val (minPsnr, minSsim) = surfaceFloorsAt(slot.toLong(), cuts, MIN_PSNR_DB, MIN_SSIM)
    try {
      assertFramesSimilar(expected, shown, minPsnr, minSsim, "the surface does not show slot $slot")
    } catch (failed: AssertionError) {
      throw AssertionError("${failed.message}. PSNR against the export at each slot offset from it: $nearby")
    }
    withClue("the surface is nearest slot ${slot + nearby.maxBy { it.second }.first}, not slot $slot: $nearby") {
      nearby.maxBy { it.second }.first shouldBe 0
    }
  }

  /**
   * How many different pictures the surface shows over [PLAY_WINDOW] of playing from the start.
   */
  suspend fun picturesWhilePlaying(): Int {
    player.attachPreviewSurface(view)
    awaitOrFail("the preview to become ready") { player.state.value.status == PlaybackStatus.Ready }
    val bitmap = Bitmap.createBitmap(PATCH.width, PATCH.height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(PATCH.width * PATCH.height)
    val copier = HandlerThread("held-slot-copier").apply { start() }
    try {
      player.play()
      awaitOrFail("the composition to render") { events.any { it is PlaybackEvent.FirstFrameRendered } }
      var last = 0
      var changes = 0
      val until = TimeSource.Monotonic.markNow() + PLAY_WINDOW
      while (until.hasNotPassedNow()) {
        val status =
          suspendCancellableCoroutine { continuation ->
            PixelCopy.request(view, bitmap, { continuation.resume(it) }, Handler(copier.looper))
          }
        if (status == PixelCopy.SUCCESS) {
          bitmap.getPixels(pixels, 0, PATCH.width, 0, 0, PATCH.width, PATCH.height)
          val hash = pixels.contentHashCode()
          if (hash != last) changes++
          last = hash
        }
      }
      player.pause()
      return changes
    } finally {
      copier.quitSafely()
    }
  }
}

internal val TIMEOUT: Duration = 8.minutes
internal val SETTLE: Duration = 700.milliseconds
internal const val FIRST_SLOT = 8
internal const val HOLD_PAST = 4
internal const val STEPS = 3
internal const val SLOWER_RATE = 24
internal val LEAD: Duration = 500.milliseconds
internal const val SOURCE_FRAMES = 60
internal const val BRIGHTER = 0.3f
internal val WIDE = Size(960, 540)
internal val PATCH = Size(160, 90)
internal val PLAY_WINDOW: Duration = 1200.milliseconds
internal const val MIN_SHARE = 0.8
internal const val MIN_PSNR_DB = 32.0

internal const val MIN_SSIM = 0.975
