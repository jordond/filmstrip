package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.internal.AppleScratch
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSProcessInfo
import platform.Foundation.dateByAddingTimeInterval
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalForeignApi::class)
class TestScratchTest {
  private val files = NSFileManager.defaultManager

  @Test
  fun `the directory sits under the default root and exists once read`() {
    val directory = TestScratch.directory

    directory shouldStartWith AppleScratch.defaultRoot
    files.fileExistsAtPath(directory) shouldBe true
    TestScratch.directory shouldBe directory
  }

  @Test
  fun `the directory is its own and carries the test prefix`() {
    val directory = TestScratch.directory

    directory shouldNotBe AppleScratch.defaultRoot
    directory.removePrefix(AppleScratch.defaultRoot) shouldStartWith "filmstrip-test-"
  }

  @Test
  fun `only test directories older than a day are swept`() {
    val root = TestScratch.directory + "sweep-" + NSProcessInfo.processInfo.globallyUniqueString + "/"
    val now = NSDate()
    directoryAged(root, "filmstrip-test-hour", 1.hours, now)
    directoryAged(root, "filmstrip-test-nearly-a-day", 23.hours, now)
    directoryAged(root, "filmstrip-test-past-a-day", 25.hours, now)
    directoryAged(root, "filmstrip-test-three-days", 3.days, now)
    directoryAged(root, "filmstrip-other-three-days", 3.days, now)

    TestScratch.sweepStale(root, now)

    files.contentsOfDirectoryAtPath(root, null).orEmpty().map { it as String } shouldContainExactlyInAnyOrder
      listOf("filmstrip-test-hour", "filmstrip-test-nearly-a-day", "filmstrip-other-three-days")
    files.removeItemAtPath(root, null)
  }

  private fun directoryAged(
    root: String,
    name: String,
    age: Duration,
    now: NSDate,
  ) {
    val path = root + name
    files.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null) shouldBe true
    val modified = now.dateByAddingTimeInterval(-age.inWholeSeconds.toDouble())
    files.setAttributes(
      mapOf<Any?, Any?>(NSFileModificationDate to modified),
      ofItemAtPath = path,
      error = null,
    ) shouldBe
      true
  }
}
