package dev.jordond.filmstrip.media

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The one check every backend with a filesystem runs on a destination before it writes, read against real directories.
 *
 * A relative destination is created under the working directory, wherever the test runs from, so the bare name and the
 * nested path are both read the way a backend reads them.
 */
class SinkParentJvmTest {
  private val root: File = createTempDirectory("filmstrip-sink-parent").toFile()
  private val relative = File("filmstrip-sink-parent-${Random.nextLong(0, Long.MAX_VALUE)}")

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
    relative.deleteRecursively()
  }

  @Test
  fun `takes a destination whose parent is a directory`() {
    sinkParentRefusal(File(root, "out.mp4").path) shouldBe null
  }

  @Test
  fun `refuses a destination whose parent is missing`() {
    val path = File(root, "missing/out.mp4").path

    val refusal = sinkParentRefusal(path).shouldNotBeNull()

    refusal.sink shouldBe path
    refusal.message shouldBe SINK_PARENT_MISSING
  }

  @Test
  fun `refuses a destination whose parent is a file`() {
    val file = File(root, "taken").apply { writeText("not a directory") }
    val path = File(file, "out.mp4").path

    val refusal = sinkParentRefusal(path).shouldNotBeNull()

    refusal.sink shouldBe path
    refusal.message shouldBe SINK_PARENT_MISSING
  }

  @Test
  fun `takes a bare name as a file in the working directory`() {
    sinkParentRefusal("filmstrip-out.mp4") shouldBe null
  }

  @Test
  fun `reads a nested relative path against the working directory`() {
    File(relative, "inner").mkdirs()

    sinkParentRefusal("${relative.path}/inner/out.mp4") shouldBe null
    sinkParentRefusal("${relative.path}/missing/out.mp4").shouldNotBeNull().message shouldBe SINK_PARENT_MISSING
  }
}
