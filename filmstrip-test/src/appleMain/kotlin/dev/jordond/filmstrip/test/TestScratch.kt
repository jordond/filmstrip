package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.AppleScratch
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.timeIntervalSinceDate
import kotlin.time.Duration.Companion.days

/**
 * The directory an Apple test process writes its files under, so two processes on one machine never share a name.
 *
 * Every process gets a directory of its own under [AppleScratch.defaultRoot]. Directories other processes left behind
 * are removed once they have gone a day without a change.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public object TestScratch {
  /**
   * This process's directory, with a trailing slash, created on the first read.
   *
   * Reading it also sweeps stale sibling directories from [AppleScratch.defaultRoot].
   */
  public val directory: String by lazy {
    val root = AppleScratch.defaultRoot
    sweepStale(root, NSDate())
    val path = root + PREFIX + NSProcessInfo.processInfo.globallyUniqueString + "/"
    val created =
      NSFileManager.defaultManager.createDirectoryAtPath(
        path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
      )
    check(created) { "Could not create the test scratch directory $path." }
    path
  }

  /**
   * Removes every directory under [root] that carries the test prefix and was last modified more than a day before
   * [now].
   *
   * Anything else under [root] is left alone, and a directory that can't be read or removed is skipped.
   */
  internal fun sweepStale(
    root: String,
    now: NSDate,
  ) {
    val files = NSFileManager.defaultManager
    val names = files.contentsOfDirectoryAtPath(root, null).orEmpty().map { it as String }
    for (name in names.filter { it.startsWith(PREFIX) }) {
      val path = root.trimEnd('/') + "/" + name
      val modified = files.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate ?: continue
      if (now.timeIntervalSinceDate(modified) > STALE_AFTER.inWholeSeconds) files.removeItemAtPath(path, null)
    }
  }

  private const val PREFIX = "filmstrip-test-"
  private val STALE_AFTER = 1.days
}
