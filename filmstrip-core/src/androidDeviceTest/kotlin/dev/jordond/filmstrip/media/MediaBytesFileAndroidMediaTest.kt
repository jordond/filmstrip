package dev.jordond.filmstrip.media

import android.content.Context
import android.os.Process
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.internal.AndroidScratch
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The shared file cache on the Android platform calls, which a host test can only stub.
 */
class MediaBytesFileAndroidMediaTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
  private val root = File(context.cacheDir, "media-bytes-device-test-${System.nanoTime()}")

  @AfterTest
  fun removeRoot() {
    root.deleteRecursively()
  }

  @Test
  fun theProcessOwnsTheDirectoryAFileLandsIn() {
    val file = File(MediaBytesFile.fileFor(root.path, BYTES, "mp4"))

    file.parentFile?.name shouldBe Process.myPid().toString()
    file.readBytes().toList() shouldBe BYTES.toList()
  }

  @Test
  fun callsRacingToMakeTheDirectoriesAllLand() {
    runBlocking(Dispatchers.Default) {
      repeat(DIRECTORY_RACES) { race ->
        val nested = File(root, "race-$race/first/second").path

        val paths = List(CONCURRENT_CALLS) { async { MediaBytesFile.fileFor(nested, BYTES, "mp4") } }.awaitAll()

        paths.toSet().size shouldBe 1
      }
    }
  }

  @Test
  fun aProcessThatIsGoneIsToldApartFromOneThatIsRunning() {
    isProcessRunning(Process.myPid()) shouldBe true
    isProcessRunning(1) shouldBe true
    isProcessRunning(Int.MAX_VALUE) shouldBe false
  }

  @Test
  fun aLinkNamedForAProcessThatIsGoneGoesAndWhatItPointsAtStays() {
    val kept = File(root, "kept/precious.mp4").also { it.parentFile?.mkdirs() }
    kept.writeBytes(BYTES)
    val link = File(root, "filmstrip-media-bytes/${Int.MAX_VALUE - 1}").also { it.parentFile?.mkdirs() }
    Os.symlink(kept.parent, link.path)

    MediaBytesFile.fileFor(root.path, BYTES, "mp4")

    kept.readBytes().toList() shouldBe BYTES.toList()
    entryAt(Path(link.path)) shouldBe null
  }

  @Test
  fun aLinkAtTheFilesNameIsReplacedRatherThanReadThrough() {
    val file = File(MediaBytesFile.fileFor(root.path, BYTES, "mp4"))
    val planted = File(root, "planted.mp4").also { it.writeBytes(BYTES.reversedArray()) }
    file.delete()
    Os.symlink(planted.path, file.path)

    MediaBytesFile.fileFor(root.path, BYTES, "mp4")

    entryAt(Path(file.path))?.kind shouldBe MediaBytesEntryKind.File
    file.readBytes().toList() shouldBe BYTES.toList()
    planted.readBytes().toList() shouldBe BYTES.reversedArray().toList()
  }

  @Test
  fun aFileOfTheAppsOwnReadsAsItsOwnerAndSystemFilesDoNot() {
    val file = File(MediaBytesFile.fileFor(root.path, BYTES, "mp4"))

    entryAt(Path(file.path))?.ownedByUser shouldBe true
    entryAt(Path("/system"))?.ownedByUser shouldBe false
  }

  @Test
  fun aSourceLandsUnderTheAppsCache() {
    val source = MediaSource.Bytes(BYTES)

    val file = AndroidScratch.fileFor(context, source)

    file.path.startsWith(context.cacheDir.path) shouldBe true
    file.extension shouldBe containerExtensionOf(BYTES)
  }

  private companion object {
    val BYTES = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
    const val DIRECTORY_RACES = 32
    const val CONCURRENT_CALLS = 8
  }
}
