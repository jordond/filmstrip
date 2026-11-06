package dev.jordond.filmstrip.media

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The file a buffer lands on, and what keeps a path handed out good for the rest of the process.
 */
@OptIn(ExperimentalAtomicApi::class)
class MediaBytesFileTest {
  private val root = Path(SystemTemporaryDirectory, "media-bytes-test-${Random.nextLong().toULong()}")

  init {
    ensurePrivateDirectory(root)
  }

  @AfterTest
  fun removeRoot() {
    deleteRecursively(root)
  }

  private fun deleteRecursively(path: Path) {
    if (entryAt(path)?.kind == MediaBytesEntryKind.Directory) {
      SystemFileSystem.list(path).forEach(::deleteRecursively)
    }
    removeEntry(path)
  }

  private fun deadDirectory(name: String): Path = Path(root, "filmstrip-media-bytes", name)

  private fun read(path: String): ByteArray = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

  private fun plant(
    path: Path,
    bytes: ByteArray,
  ) {
    path.parent?.let(::ensurePrivateDirectory)
    SystemFileSystem.sink(path).buffered().use { it.write(bytes) }
  }

  @Test
  fun `a directory is made along with any parents that are missing`() {
    val nested = Path(root, "first", "second", "third")

    ensurePrivateDirectory(nested)
    ensurePrivateDirectory(nested)

    entryAt(nested)?.kind shouldBe MediaBytesEntryKind.Directory
    SystemFileSystem.exists(Path(MediaBytesFile.fileFor(nested.toString(), MediaSource.Bytes(MOV)))) shouldBe true
  }

  @Test
  fun `a source lands under the extension the shared function names`() {
    val hinted = MediaSource.Bytes(MOV, FormatHint.ThreeGp)
    val unhinted = MediaSource.Bytes(MOV)
    val neutral = MediaSource.Bytes(OTHER)

    MediaBytesFile.fileFor(root.toString(), hinted) shouldEndWith ".${containerExtensionOf(MOV, FormatHint.ThreeGp)}"
    MediaBytesFile.fileFor(root.toString(), unhinted) shouldEndWith ".${containerExtensionOf(MOV)}"
    MediaBytesFile.fileFor(root.toString(), neutral) shouldEndWith ".${containerExtensionOf(OTHER)}"
  }

  @Test
  fun `the same bytes give one file across sources and calls`() {
    val first = MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV))
    val second = MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV.copyOf()))
    val third = MediaBytesFile.fileFor(root.toString(), MOV.copyOf(), containerExtensionOf(MOV))

    second shouldBe first
    third shouldBe first
    read(first).toList() shouldBe MOV.toList()
  }

  @Test
  fun `the same bytes are written once`() {
    val first = MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV))
    val sentinel = MOV.copyOf().also { it[it.lastIndex] = it[it.lastIndex].inc() }
    plant(Path(first), sentinel)

    MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV.copyOf())) shouldBe first

    read(first).toList() shouldBe sentinel.toList()
  }

  @Test
  fun `different bytes land apart`() {
    val first = MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV))
    val second = MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(OTHER))

    second shouldNotBe first
    read(first).toList() shouldBe MOV.toList()
    read(second).toList() shouldBe OTHER.toList()
  }

  @Test
  fun `an empty buffer lands on an empty file`() {
    val file = MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(ByteArray(0)))

    read(file).size shouldBe 0
  }

  @Test
  fun `one source digests its bytes once`() {
    val source = MediaSource.Bytes(MOV.copyOf())
    val first = MediaBytesFile.fileFor(root.toString(), source)

    source.bytes[source.bytes.lastIndex] = source.bytes[source.bytes.lastIndex].inc()

    MediaBytesFile.fileFor(root.toString(), source) shouldBe first
  }

  @Test
  fun `nothing partial is left beside a written file`() {
    val file = Path(MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV)))

    SystemFileSystem.list(file.parent!!).filter { it.name.endsWith(".partial") }.shouldBeEmpty()
  }

  @Test
  fun `a deleted file is written again`() {
    val source = MediaSource.Bytes(MOV)
    val file = MediaBytesFile.fileFor(root.toString(), source)
    SystemFileSystem.delete(Path(file))

    MediaBytesFile.fileFor(root.toString(), source) shouldBe file

    read(file).toList() shouldBe MOV.toList()
  }

  @Test
  fun `a file cut short is written again`() {
    val source = MediaSource.Bytes(MOV)
    val file = MediaBytesFile.fileFor(root.toString(), source)
    plant(Path(file), MOV.copyOf(MOV.size / 2))

    MediaBytesFile.fileFor(root.toString(), source)

    read(file).toList() shouldBe MOV.toList()
  }

  @Test
  fun `first use removes what a process that is gone left and nothing else`() {
    val parent = Path(root, "filmstrip-media-bytes")
    val gone = Path(parent, Int.MAX_VALUE.toString(), "left.mp4")
    val running = Path(parent, RUNNING_PROCESS.toString(), "held.mp4")
    val stray = Path(parent, "stray.partial")
    plant(gone, MOV)
    plant(running, MOV)
    plant(stray, MOV)

    MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV))

    SystemFileSystem.exists(gone) shouldBe false
    SystemFileSystem.exists(gone.parent!!) shouldBe false
    SystemFileSystem.exists(stray) shouldBe false
    SystemFileSystem.exists(running) shouldBe true
  }

  @Test
  fun `a link named for a process that is gone goes and what it points at stays`() {
    val kept = Path(root, "kept", "precious.mp4")
    plant(kept, MOV)
    val link = deadDirectory(GONE_PROCESS.toString())
    ensurePrivateDirectory(link.parent!!)
    plantSymlink(link, kept.parent!!)

    MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV))

    read(kept.toString()).toList() shouldBe MOV.toList()
    entryAt(link) shouldBe null
  }

  @Test
  fun `a link inside a dead directory goes and what it points at stays`() {
    val kept = Path(root, "kept", "precious.mp4")
    plant(kept, MOV)
    val dead = deadDirectory(GONE_PROCESS.toString())
    ensurePrivateDirectory(dead)
    plantSymlink(Path(dead, "to-directory"), kept.parent!!)
    plantSymlink(Path(dead, "to-file.mp4"), kept)
    plantSymlink(Path(dead, "to-nothing.mp4"), Path(root, "nothing"))

    MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV))

    read(kept.toString()).toList() shouldBe MOV.toList()
    entryAt(dead) shouldBe null
  }

  @Test
  fun `a dead directory holding a directory goes and so does every other dead directory`() {
    val dead = (0 until DEAD_DIRECTORIES).map { deadDirectory((GONE_PROCESS - it).toString()) }
    dead.forEach { plant(Path(it, "nested", "deeper", "left.mp4"), MOV) }

    MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV))

    dead.forEach { entryAt(it) shouldBe null }
  }

  @Test
  fun `a name no process can hold is swept`() {
    val names = listOf("0", "-1").map(::deadDirectory)
    names.forEach { plant(Path(it, "left.mp4"), MOV) }

    MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV))

    names.forEach { entryAt(it) shouldBe null }
  }

  @Test
  fun `a link at the file's name is replaced rather than read through`() {
    val source = MediaSource.Bytes(MOV)
    val file = Path(MediaBytesFile.fileFor(root.toString(), source))
    val planted = Path(root, "planted.mp4")
    val sameSize = MOV.copyOf().also { it[it.lastIndex] = it[it.lastIndex].inc() }
    plant(planted, sameSize)
    removeEntry(file)
    plantSymlink(file, planted)

    MediaBytesFile.fileFor(root.toString(), source) shouldBe file.toString()

    entryAt(file)?.kind shouldBe MediaBytesEntryKind.File
    read(file.toString()).toList() shouldBe MOV.toList()
    read(planted.toString()).toList() shouldBe sameSize.toList()
  }

  @Test
  fun `only a whole file of the user's own is taken as already written`() {
    val own = MediaBytesEntry(MediaBytesEntryKind.File, size = 8, ownedByUser = true)

    own.isWholeFile(8) shouldBe true
    own.isWholeFile(9) shouldBe false
    MediaBytesEntry(MediaBytesEntryKind.File, size = 8, ownedByUser = false).isWholeFile(8) shouldBe false
    MediaBytesEntry(MediaBytesEntryKind.Other, size = 8, ownedByUser = true).isWholeFile(8) shouldBe false
    MediaBytesEntry(MediaBytesEntryKind.Directory, size = 8, ownedByUser = true).isWholeFile(8) shouldBe false
  }

  @Test
  fun `calls racing to make the directories all land`() =
    runTest {
      withContext(Dispatchers.Default) {
        repeat(DIRECTORY_RACES) { race ->
          val nested = Path(root, "race-$race", "first", "second").toString()

          val paths =
            List(CONCURRENT_CALLS) { async { MediaBytesFile.fileFor(nested, MOV, "mov") } }.awaitAll()

          paths.toSet().size shouldBe 1
        }
      }
    }

  @Test
  fun `two first calls on one buffer write one copy`() =
    runTest {
      val writes = AtomicInt(0)
      val slowWrite: (ByteArray, Path) -> Unit = { bytes, file ->
        writes.incrementAndFetch()
        // Long enough for the other call to arrive at the check while this one is still writing.
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < WRITE_WINDOW) {
          // Spinning, since no sleep is common to every target this runs on.
        }
        plant(file, bytes)
      }

      val paths =
        withContext(Dispatchers.Default) {
          List(CONCURRENT_CALLS) {
            async { MediaBytesFile.write(root.toString(), MOV, "digest", "mov", slowWrite) }
          }.awaitAll()
        }

      writes.load() shouldBe 1
      paths.toSet().size shouldBe 1
    }

  @Test
  fun `a key's lock is gone once nobody holds it`() {
    val locks = KeyedLocks()

    locks.withLock("key") { locks.size shouldBe 1 }

    locks.size shouldBe 0
  }

  @Test
  fun `a path handed out survives the next call`() {
    val first = MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(MOV))
    val other = MediaBytesFile.fileFor(root.toString(), MediaSource.Bytes(OTHER))

    SystemFileSystem.exists(Path(first)) shouldBe true
    SystemFileSystem.exists(Path(other)) shouldBe true
  }

  @Test
  fun `a root that cannot hold a directory fails naming the reason`() {
    val blocked = Path(root, "blocked")
    plant(blocked, MOV)

    val failure =
      shouldThrow<MediaBytesFileFailure> { MediaBytesFile.fileFor(blocked.toString(), MediaSource.Bytes(MOV)) }

    failure.message shouldContain "temporary file"
  }

  private companion object {
    val MOV = ByteArray(32).also { "ftypqt  ".encodeToByteArray().copyInto(it, destinationOffset = 4) }
    val OTHER = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)

    // Process 1 is init or launchd on every target, and this process may not always signal it, which still counts.
    const val RUNNING_PROCESS = 1

    // Above any pid a real system hands out, but not so high that a neighbour overflows the name.
    const val GONE_PROCESS = Int.MAX_VALUE - 1
    const val DEAD_DIRECTORIES = 8
    const val CONCURRENT_CALLS = 4
    const val DIRECTORY_RACES = 32
    val WRITE_WINDOW = 200.milliseconds
  }
}
