package dev.jordond.filmstrip.avfoundation.internal.still

import dev.jordond.filmstrip.avfoundation.internal.chain.CoreImageChain
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.naturalSize
import platform.AVFoundation.naturalTimeScale
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.tracksWithMediaType
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.posix.rename
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * A seed track and how much of it there is to cut from.
 *
 * @property track The track a still's time is cut from.
 * @property length How long that track runs, which is the most one cut can take.
 */
internal class StillSeed(
  val track: AVAssetTrack,
  val length: Duration,
)

/**
 * What came of asking for the seed at one frame rate.
 *
 * [Slow] and [Failed] both open nothing. They are told apart because only one of them makes asking
 * again pointless: an encoder that was still working when the wait ran out is usually warm by the
 * next lowering, while a writer that stopped refuses every write after it.
 */
internal sealed interface SeedAttempt {
  class Opened(
    val asset: AVURLAsset,
  ) : SeedAttempt

  data object Slow : SeedAttempt

  data object Failed : SeedAttempt
}

/**
 * The seed an image clip's time is cut from at [frameRate], or null when none could be written.
 *
 * AVFoundation discards a composition track's trailing empty range and gives a track holding
 * nothing but empty ranges no duration at all, so a still taking its slot as empty time either
 * falls off the end of the timeline or leaves a composition that cannot be opened. Every image clip
 * occupies a real segment instead, cut from a movie of black frames written once per rate and read
 * back by every later lowering, whichever process wrote it. Those pixels never reach the output,
 * because [CoreImageChain] draws the still over the whole frame before anything measures or grades
 * it.
 *
 * The seed carries a frame for every one the output does, because the reader an export pulls
 * through composites what the source hands it rather than rendering on a grid of its own. A sparser
 * seed writes a sparser span.
 *
 * The asset is what is kept, not the track. A track holds no reference back to the asset it belongs
 * to, so one cached on its own outlives it and every insert from it is refused.
 *
 * Opened on whichever thread lowered first and read from every one after, so the open is behind a
 * lock. The lock is the rate's own, because a write runs for as long as the encoder behind it takes
 * and a lowering at any other rate has nothing to gain by waiting that out. A rate whose writer refused the write is
 * remembered as having no seed, because the write blocks on VideoToolbox while holding that lock and retrying a
 * refusal once per lowering turns it into a stall on every later request. A write that only ran out of patience is
 * not remembered, so the next lowering asks for it again.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun stillSeed(frameRate: Int): StillSeed? {
  val lock = seedLockFor(frameRate)
  lock.lock()
  try {
    val asset = seedAsset(frameRate) ?: return null
    val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: return null
    val length = asset.duration.toDuration()
    return if (length <= Duration.ZERO) null else StillSeed(track, length)
  } finally {
    lock.unlock()
  }
}

/**
 * The asset the seed at [frameRate] opened as, opening it when nothing has yet.
 *
 * Called with the rate's own lock held. A rate [seeds] already answers for is answered from there,
 * including one it holds no seed for.
 */
@OptIn(ExperimentalForeignApi::class)
private fun seedAsset(frameRate: Int): AVURLAsset? {
  withSeeds { if (seeds.containsKey(frameRate)) return seeds[frameRate] }
  return openSeed(frameRate)
}

/**
 * Opens the seed for [frameRate] and records in [seeds] what came back.
 *
 * Called with the lock [seedLockFor] hands out for that rate, so one writer at a rate runs at a time
 * and a second lowering at it reads what the first wrote.
 *
 * Only an attempt nothing will ever come of is recorded. A slow one leaves the map untouched, which
 * is what makes the rate an open question again rather than a refusal for the rest of the process.
 */
@OptIn(ExperimentalForeignApi::class)
private fun openSeed(frameRate: Int): AVURLAsset? {
  val asset =
    when (val attempt = stillSeedAsset(frameRate)) {
      is SeedAttempt.Opened -> attempt.asset
      SeedAttempt.Slow -> return null
      SeedAttempt.Failed -> null
    }
  withSeeds { seeds[frameRate] = asset }
  return asset
}

/**
 * Where the seed for [frameRate] is kept.
 *
 * One name per rate, so the temporary directory holds at most one seed per rate no matter how often
 * the library runs.
 */
internal fun stillSeedPath(frameRate: Int): String = NSTemporaryDirectory() + SEED_NAME + frameRate + SEED_EXTENSION

/**
 * The seed movie for [frameRate], read from [stillSeedPath] and written there first when what is
 * already there cannot be cut from.
 *
 * The file stays in the temporary directory for as long as anything might cut from the asset opened
 * over it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun stillSeedAsset(frameRate: Int): SeedAttempt {
  val path = stillSeedPath(frameRate)
  return finishedSeed(path, frameRate)?.let(SeedAttempt::Opened) ?: writeStillSeed(path, frameRate)
}

/**
 * The lock the seed at [frameRate] is opened under, made on the first ask for that rate.
 *
 * One lock a rate, so a write spending its whole budget on a cold encoder holds up nothing but
 * later lowerings at that same rate.
 */
internal fun seedLockFor(frameRate: Int): NSLock = withSeeds { seedLocks.getOrPut(frameRate) { NSLock() } }

/**
 * Runs [block] holding the lock that every read and write of [seeds] and [seedLocks] goes through.
 *
 * Nothing but those two maps is touched under it, so it is only ever held for as long as a lookup
 * takes. The open it hands a lock out for runs under that lock instead.
 */
private inline fun <T> withSeeds(block: () -> T): T {
  tableLock.lock()
  try {
    return block()
  } finally {
    tableLock.unlock()
  }
}

private val tableLock = NSLock()

private val seeds = mutableMapOf<Int, AVURLAsset?>()

private val seedLocks = mutableMapOf<Int, NSLock>()

/**
 * The asset at [path] when it is a finished seed for [frameRate], or null when it is not.
 *
 * A movie only becomes readable once its writer has closed it, so one left by a process that died
 * part way through opens carrying no track at all. The shape, length, cadence and timescale are read
 * as well, so a file left by a build that cut stills differently is rewritten rather than cut from.
 */
@OptIn(ExperimentalForeignApi::class)
private fun finishedSeed(
  path: String,
  frameRate: Int,
): AVURLAsset? {
  if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null

  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
  val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: return null
  val frames = seedFrames(frameRate)

  val sized = track.naturalSize.useContents { width == SEED_SIDE.toDouble() && height == SEED_SIDE.toDouble() }
  val held = (asset.duration.toDuration() - STILL_SEED_LENGTH).absoluteValue <= STILL_SEED_LENGTH / frames
  val paced = abs(track.nominalFrameRate - frames / STILL_SEED_LENGTH.toDouble(DurationUnit.SECONDS)) < HALF_FRAME
  val timed = track.naturalTimeScale == MEDIA_TIMESCALE

  return asset.takeIf { sized && held && paced && timed }
}

/**
 * Writes a seed movie carrying [frameRate] frames a second onto [path] and opens it, leaving
 * nothing behind where the write did not finish.
 *
 * The write lands on a name of its own and moves onto [path] in a single step, so nothing ever
 * reads a file still being written. Two processes racing each write the same frames, which leaves
 * whichever move landed last a seed they can both cut from.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeStillSeed(
  path: String,
  frameRate: Int,
): SeedAttempt {
  val staging = NSTemporaryDirectory() + STAGING_NAME + NSUUID().UUIDString() + SEED_EXTENSION
  val write = writeSeedMovie(staging, frameRate)
  if (write != SeedWrite.Written || rename(staging, path) != 0) {
    NSFileManager.defaultManager.removeItemAtPath(staging, error = null)
    return if (write == SeedWrite.Slow) SeedAttempt.Slow else SeedAttempt.Failed
  }
  return SeedAttempt.Opened(AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null))
}

private const val SEED_NAME = "filmstrip-still-seed-"
private const val STAGING_NAME = SEED_NAME + "staging-"
private const val SEED_EXTENSION = ".mov"

// A rate is a whole number of frames a second, so a seed reading further than this from the cadence
// its name claims was cut at some other rate.
private const val HALF_FRAME = 0.5
