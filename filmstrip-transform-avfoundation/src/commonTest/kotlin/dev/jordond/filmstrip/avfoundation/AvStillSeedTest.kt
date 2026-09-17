package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.FrameAppend
import dev.jordond.filmstrip.avfoundation.internal.STILL_SEED_LENGTH
import dev.jordond.filmstrip.avfoundation.internal.STILL_SEED_WRITE_BUDGET
import dev.jordond.filmstrip.avfoundation.internal.SeedAttempt
import dev.jordond.filmstrip.avfoundation.internal.SeedWrite
import dev.jordond.filmstrip.avfoundation.internal.seedFinishFor
import dev.jordond.filmstrip.avfoundation.internal.seedLockFor
import dev.jordond.filmstrip.avfoundation.internal.seedWriteFor
import dev.jordond.filmstrip.avfoundation.internal.stillSeed
import dev.jordond.filmstrip.avfoundation.internal.stillSeedAsset
import dev.jordond.filmstrip.avfoundation.internal.stillSeedPath
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import dev.jordond.filmstrip.avfoundation.internal.waitsForSeedInput
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAssetWriterStatusCancelled
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusFailed
import platform.AVFoundation.AVAssetWriterStatusUnknown
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What the still seed leaves behind in the temporary directory, and how long it waits to write one.
 *
 * The seed has to outlive the lowering that wrote it, since the asset it is opened as is read again
 * by every later one, so it cannot be deleted once the write is done. What it can do is live at one
 * path per frame rate, which is what these pin: a run that finds a usable seed reads it instead of
 * writing another, and a run that writes one adds that file and nothing else.
 *
 * The wait is read off the state a poll sees rather than run for real, since an encoder slow enough
 * to be worth waiting on is slower than any test should be. The one case that does take a lock takes
 * it at a rate of its own and opens at another, which is the whole of what a lock a rate buys.
 *
 * Every rate here is one nothing else encodes at, so a case never reads a seed another test left.
 */
@OptIn(ExperimentalForeignApi::class)
class AvStillSeedTest {
  @BeforeTest
  fun clearSeeds() = SEEDED.forEach { remove(stillSeedPath(it)) }

  @AfterTest
  fun dropSeeds() = SEEDED.forEach { remove(stillSeedPath(it)) }

  // The whole of the leak: a name carrying a fresh UUID left one file per rate per run of the
  // process, none of which anything ever deleted.
  @Test
  fun `writes one file per frame rate however many times it is asked`() {
    val before = seedFiles()

    RATES.forEach { rate ->
      assertNotNull(openedSeed(rate), "no seed was written at $rate fps")
      assertNotNull(openedSeed(rate), "no seed came back on a second ask at $rate fps")
    }

    seedFiles() - before shouldBe RATES.map { stillSeedPath(it).fileName() }.toSet()
  }

  @Test
  fun `reads back the seed an earlier run left behind`() {
    val path = stillSeedPath(RATE)
    assertNotNull(openedSeed(RATE), "no seed was written at $RATE fps")
    age(path)

    assertNotNull(openedSeed(RATE), "the seed an earlier run wrote was not read back")

    modified(path) shouldBe AGED
  }

  // A movie is only readable once its writer closes it, so a run killed part way through leaves one
  // that opens carrying nothing. Reading it back would leave the still holding no time at all.
  @Test
  fun `rewrites a seed no writer ever finished`() {
    val path = stillSeedPath(RATE)
    NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)

    val asset = assertNotNull(openedSeed(RATE), "an unfinished seed was not rewritten")

    assertNotNull(asset.videoTrack(), "the rewritten seed carries no video track")
    assertTrue(
      (asset.duration.toDuration() - STILL_SEED_LENGTH).absoluteValue <= TOLERANCE,
      "the rewritten seed runs for ${asset.duration.toDuration()}, not $STILL_SEED_LENGTH",
    )
  }

  // Everything but the cadence matches, so a seed cut at the wrong rate is the one case the path,
  // the length and the frame size all read as good.
  @Test
  fun `rewrites a seed carrying another rate's frames`() {
    val other = stillSeedPath(OTHER_RATE)
    assertNotNull(openedSeed(RATE), "no seed was written at $RATE fps")
    NSFileManager.defaultManager.copyItemAtPath(stillSeedPath(RATE), toPath = other, error = null)
    age(other)

    assertNotNull(openedSeed(OTHER_RATE), "a seed cut at another rate was not rewritten")

    assertTrue(modified(other) != AGED, "the seed at $OTHER_RATE fps was read back at $RATE fps")
  }

  // An input says it has room through one flag and nothing else, so what a poll reads is that flag
  // and what the writer behind it is doing.
  @Test
  fun `waits on an input its writer has not drained yet`() {
    waitsForSeedInput(AVAssetWriterStatusWriting, ready = false) shouldBe true
  }

  @Test
  fun `stops waiting once the input has room`() {
    waitsForSeedInput(AVAssetWriterStatusWriting, ready = true) shouldBe false
  }

  // Only a writer that is still writing ever drains its input, so waiting the ceiling out on any of
  // these costs the whole timeout and can never end in a frame.
  @Test
  fun `gives up on a writer that is not writing`() {
    STOPPED.forEach { status ->
      assertFalse(waitsForSeedInput(status, ready = false), "waited on a writer at status $status")
      assertFalse(
        waitsForSeedInput(status, ready = true),
        "waited on a writer at status $status whose input had room",
      )
    }
  }

  // The split the cache turns on. A frame refused for any reason but the wait is refused the same
  // way on every later lowering, so remembering it costs nothing and asking again costs a write.
  @Test
  fun `counts only a wait that ran out as worth asking again`() {
    seedWriteFor(FrameAppend.Appended) shouldBe SeedWrite.Written
    seedWriteFor(FrameAppend.TimedOut) shouldBe SeedWrite.Slow
    seedWriteFor(FrameAppend.Refused) shouldBe SeedWrite.Failed
    FrameAppend.entries.filter { seedWriteFor(it) == SeedWrite.Slow } shouldBe listOf(FrameAppend.TimedOut)
  }

  // The first VideoToolbox session a process opens can take half a minute to come up on a cold
  // simulator, and every frame of the seed queues behind it. The ceiling has to clear that, or a
  // cold lowering gives up on a write that was only slow.
  @Test
  fun `waits longer than a cold encoder takes to come up`() {
    assertTrue(
      STILL_SEED_WRITE_BUDGET > COLD_START,
      "a seed write gives up after $STILL_SEED_WRITE_BUDGET, inside the $COLD_START a cold encoder has taken",
    )
  }

  // A finish is the same wait as an append, so running the budget out on one leaves the rate worth
  // asking for again rather than remembered as refused.
  @Test
  fun `counts a finish that ran the budget out as worth asking again`() {
    seedFinishFor(timedOut = true, status = AVAssetWriterStatusCancelled) shouldBe SeedWrite.Slow
    seedFinishFor(timedOut = true, status = AVAssetWriterStatusWriting) shouldBe SeedWrite.Slow
  }

  // The boundary the cancel would otherwise land on. A handler that fired as the budget ran out
  // closed a movie there is nothing wrong with, and rewriting it costs a cold encoder again.
  @Test
  fun `keeps a movie the writer closed as the budget ran out`() {
    seedFinishFor(timedOut = true, status = AVAssetWriterStatusCompleted) shouldBe SeedWrite.Written
  }

  // Nothing can be cut from the file until the writer closes it, so a finish that ended anywhere
  // else wrote no seed.
  @Test
  fun `counts a finish by the state the writer closed in`() {
    seedFinishFor(timedOut = false, status = AVAssetWriterStatusCompleted) shouldBe SeedWrite.Written
    UNFINISHED.forEach { status ->
      seedFinishFor(timedOut = false, status = status) shouldBe SeedWrite.Failed
    }
  }

  @Test
  fun `opens one lock a rate`() {
    assertSame(seedLockFor(RATE), seedLockFor(RATE), "two asks at $RATE fps took locks of their own")
    assertNotSame(seedLockFor(RATE), seedLockFor(OTHER_RATE), "$RATE and $OTHER_RATE fps share a lock")
  }

  // What a lock a rate is for. A write runs for as long as the encoder behind it takes, and under
  // one lock over every rate that is every lowering in the process waiting on one cold encoder.
  @Test
  fun `opens a seed at one rate while another rate is held`() {
    assertNotNull(stillSeed(FREE_RATE), "no seed was written at $FREE_RATE fps")
    val held = seedLockFor(HELD_RATE)
    held.lock()

    try {
      assertTrue(
        ranOffThread { stillSeed(FREE_RATE) },
        "a lowering at $FREE_RATE fps waited on the lock held at $HELD_RATE fps",
      )
    } finally {
      held.unlock()
    }
  }

  /**
   * Runs [block] on a thread of its own and says whether it finished inside [OFF_THREAD_BUDGET].
   */
  private fun ranOffThread(block: () -> Unit): Boolean {
    val done = dispatch_semaphore_create(0)
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), flags = 0uL)) {
      block()
      dispatch_semaphore_signal(done)
    }
    val budget = dispatch_time(DISPATCH_TIME_NOW, OFF_THREAD_BUDGET.inWholeNanoseconds)
    return dispatch_semaphore_wait(done, budget) == 0L
  }

  /**
   * The asset the seed at [frameRate] opened as, or null when the attempt opened nothing.
   */
  private fun openedSeed(frameRate: Int): AVURLAsset? = (stillSeedAsset(frameRate) as? SeedAttempt.Opened)?.asset

  private fun AVURLAsset.videoTrack() = tracksWithMediaType(AVMediaTypeVideo).firstOrNull()

  /**
   * Every file the seed writer could have left in the temporary directory, staging names included.
   */
  private fun seedFiles(): Set<String> =
    NSFileManager.defaultManager
      .contentsOfDirectoryAtPath(NSTemporaryDirectory(), error = null)
      .orEmpty()
      .filterIsInstance<String>()
      .filter { it.startsWith(PREFIX) }
      .toSet()

  /**
   * Stamps [path] with a date no write could produce, so a rewrite of it is visible.
   */
  private fun age(path: String) {
    NSFileManager.defaultManager.setAttributes(
      attributes = mapOf<Any?, Any?>(NSFileModificationDate to NSDate(timeIntervalSinceReferenceDate = AGED)),
      ofItemAtPath = path,
      error = null,
    )
  }

  private fun modified(path: String): Double {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    val date = attributes?.get(NSFileModificationDate) as? NSDate
    return assertNotNull(date, "nothing at $path to read a date from").timeIntervalSinceReferenceDate
  }

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private fun String.fileName(): String = substringAfterLast('/')

  private companion object {
    const val PREFIX = "filmstrip-still-seed-"

    // Rates no export encodes at, so no case here shares a seed with one that exports for real.
    const val RATE = 7
    const val OTHER_RATE = 11
    val RATES = setOf(RATE, OTHER_RATE)

    // The rate the lock is held at while a seed opens at the other one.
    const val HELD_RATE = 13
    const val FREE_RATE = 17

    // Every rate a case here can leave a seed at.
    val SEEDED = RATES + setOf(HELD_RATE, FREE_RATE)

    // Long enough ago that no write could land on it, and after the reference date so no filesystem
    // clamps it.
    const val AGED = 1_000_000.0

    // A container reports the length it was closed at rounded onto its own timescale.
    val TOLERANCE = STILL_SEED_LENGTH / RATE

    // Every state but writing. Only a writer that is writing drains its input, whether it has yet
    // to start or has already stopped.
    val STOPPED =
      listOf(
        AVAssetWriterStatusUnknown,
        AVAssetWriterStatusFailed,
        AVAssetWriterStatusCancelled,
        AVAssetWriterStatusCompleted,
      )

    // The slowest first session seen on a cold simulator in CI.
    val COLD_START = 32.seconds

    // Every state but the one that means the file was closed, a writer still going included.
    val UNFINISHED = STOPPED - AVAssetWriterStatusCompleted + AVAssetWriterStatusWriting

    // Long enough for a thread to start and read a seed already in hand, short enough that a rate
    // waiting on another rate's lock fails rather than hangs.
    val OFF_THREAD_BUDGET = 5.seconds
  }
}
