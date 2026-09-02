package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.STILL_SEED_LENGTH
import dev.jordond.filmstrip.avfoundation.internal.stillSeedAsset
import dev.jordond.filmstrip.avfoundation.internal.stillSeedPath
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the still seed leaves behind in the temporary directory.
 *
 * The seed has to outlive the lowering that wrote it, since the asset it is opened as is read again
 * by every later one, so it cannot be deleted once the write is done. What it can do is live at one
 * path per frame rate, which is what these pin: a run that finds a usable seed reads it instead of
 * writing another, and a run that writes one adds that file and nothing else.
 *
 * Both rates here are ones nothing else encodes at, so a case never reads a seed another test left.
 */
@OptIn(ExperimentalForeignApi::class)
class AvStillSeedTest {
  @BeforeTest
  fun clearSeeds() = RATES.forEach { remove(stillSeedPath(it)) }

  @AfterTest
  fun dropSeeds() = RATES.forEach { remove(stillSeedPath(it)) }

  // The whole of the leak: a name carrying a fresh UUID left one file per rate per run of the
  // process, none of which anything ever deleted.
  @Test
  fun `writes one file per frame rate however many times it is asked`() {
    val before = seedFiles()

    RATES.forEach { rate ->
      assertNotNull(stillSeedAsset(rate), "no seed was written at $rate fps")
      assertNotNull(stillSeedAsset(rate), "no seed came back on a second ask at $rate fps")
    }

    seedFiles() - before shouldBe RATES.map { stillSeedPath(it).fileName() }.toSet()
  }

  @Test
  fun `reads back the seed an earlier run left behind`() {
    val path = stillSeedPath(RATE)
    assertNotNull(stillSeedAsset(RATE), "no seed was written at $RATE fps")
    age(path)

    assertNotNull(stillSeedAsset(RATE), "the seed an earlier run wrote was not read back")

    modified(path) shouldBe AGED
  }

  // A movie is only readable once its writer closes it, so a run killed part way through leaves one
  // that opens carrying nothing. Reading it back would leave the still holding no time at all.
  @Test
  fun `rewrites a seed no writer ever finished`() {
    val path = stillSeedPath(RATE)
    NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null)

    val asset = assertNotNull(stillSeedAsset(RATE), "an unfinished seed was not rewritten")

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
    assertNotNull(stillSeedAsset(RATE), "no seed was written at $RATE fps")
    NSFileManager.defaultManager.copyItemAtPath(stillSeedPath(RATE), toPath = other, error = null)
    age(other)

    assertNotNull(stillSeedAsset(OTHER_RATE), "a seed cut at another rate was not rewritten")

    assertTrue(modified(other) != AGED, "the seed at $OTHER_RATE fps was read back at $RATE fps")
  }

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

    // Long enough ago that no write could land on it, and after the reference date so no filesystem
    // clamps it.
    const val AGED = 1_000_000.0

    // A container reports the length it was closed at rounded onto its own timescale.
    val TOLERANCE = STILL_SEED_LENGTH / RATE
  }
}
