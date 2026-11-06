package dev.jordond.filmstrip.media

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.io.files.Path
import org.junit.Assume.assumeFalse
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * What a shared temporary directory demands of the cache, and the size half of what keeps a path good, which takes a
 * sparse file to reach without writing the data for real.
 */
class MediaBytesFileJvmTest {
  private val scratch =
    File(System.getProperty("java.io.tmpdir"), "media-bytes-jvm-test-${System.nanoTime()}").also { it.mkdirs() }
  private val root = File(scratch, "root")
  private val parent = File(root, "filmstrip-media-bytes")
  private val own = File(parent, currentProcessId().toString())

  @AfterTest
  fun removeRoot() {
    scratch.deleteRecursively()
  }

  private fun modeOf(directory: File): String {
    val options = arrayOf(LinkOption.NOFOLLOW_LINKS)
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.toPath(), *options))
  }

  private fun setMode(
    directory: File,
    mode: String,
  ) {
    Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString(mode))
  }

  private fun fileFor(bytes: ByteArray = BYTES): File = File(MediaBytesFile.fileFor(root.path, bytes, "mp4"))

  private fun planted(): File = File(scratch, "elsewhere").also { it.mkdirs() }

  @Test
  fun `buffers past any size budget all stay on disk`() {
    val first = fileFor(byteArrayOf(1, 2, 3))
    val large = File(first.parentFile, "large.mp4")
    RandomAccessFile(large, "rw").use { it.setLength(SPARSE_BYTES) }

    val second = fileFor(byteArrayOf(4, 5, 6))

    first.exists().shouldBeTrue()
    large.exists().shouldBeTrue()
    second.exists().shouldBeTrue()
  }

  @Test
  fun `every directory the cache makes is for the owner alone`() {
    fileFor()

    modeOf(root) shouldBe OWNER_ONLY
    modeOf(parent) shouldBe OWNER_ONLY
    modeOf(own) shouldBe OWNER_ONLY
  }

  @Test
  fun `a link planted at any directory is refused and nothing lands behind it`() {
    listOf(root, parent, own).forEach { directory ->
      val elsewhere = planted()
      directory.parentFile.mkdirs()
      listOf(directory.parentFile, parent, root).filter(File::isDirectory).forEach { setMode(it, OWNER_ONLY) }
      Files.createSymbolicLink(directory.toPath(), elsewhere.toPath())

      val failure = shouldThrow<MediaBytesFileFailure> { fileFor() }

      failure.message shouldContain "not a real directory"
      elsewhere
        .list()
        .orEmpty()
        .toList()
        .shouldBeEmpty()
      directory.delete()
    }
  }

  @Test
  fun `a file planted at any directory is refused`() {
    listOf(root, parent, own).forEach { directory ->
      directory.parentFile.mkdirs()
      listOf(directory.parentFile, parent, root).filter(File::isDirectory).forEach { setMode(it, OWNER_ONLY) }
      directory.writeBytes(BYTES)

      shouldThrow<MediaBytesFileFailure> { fileFor() }.message shouldContain "not a real directory"

      directory.delete()
    }
  }

  @Test
  fun `missing parents are made for the owner alone`() {
    val nested = File(root, "first/second")

    File(MediaBytesFile.fileFor(nested.path, BYTES, "mp4")).exists().shouldBeTrue()

    listOf(root, nested.parentFile, nested).forEach { modeOf(it) shouldBe OWNER_ONLY }
  }

  @Test
  fun `a junction reads as other and never as a real directory`() {
    val junction = entryKindOf(FakeAttributes(directory = true, other = true))
    val link = entryKindOf(FakeAttributes(link = true))

    junction shouldBe MediaBytesEntryKind.Other
    link shouldBe MediaBytesEntryKind.Other
    entryKindOf(FakeAttributes(directory = true)) shouldBe MediaBytesEntryKind.Directory
    entryKindOf(FakeAttributes(file = true)) shouldBe MediaBytesEntryKind.File
    privateDirectoryRefusal(MediaBytesEntry(junction, size = 0, ownedByUser = true), permissions = null) shouldNotBe
      null
  }

  @Test
  fun `a directory anybody else can reach is refused and left as it was`() {
    fileFor()

    listOf(root, parent, own).forEach { directory ->
      listOf("rwxr-xr-x", "rwx-w----", "rwx-----x").forEach { mode ->
        setMode(directory, mode)

        shouldThrow<MediaBytesFileFailure> { fileFor() }.message shouldContain "other users"

        modeOf(directory) shouldBe mode
      }
      setMode(directory, OWNER_ONLY)
    }
  }

  @Test
  fun `a directory of the owner's own stays good across calls`() {
    fileFor() shouldBe fileFor()
    modeOf(own) shouldBe OWNER_ONLY
  }

  @Test
  fun `another user's directory or a missing one is refused and no permissions means no mode check`() {
    val directory = MediaBytesEntry(MediaBytesEntryKind.Directory, size = 0, ownedByUser = true)
    val reachable = PosixFilePermissions.fromString("rwxr-xr-x")

    privateDirectoryRefusal(directory, permissions = null) shouldBe null
    privateDirectoryRefusal(directory, reachable) shouldNotBe null
    privateDirectoryRefusal(null, permissions = null) shouldNotBe null
    privateDirectoryRefusal(
      MediaBytesEntry(MediaBytesEntryKind.Directory, size = 0, ownedByUser = false),
      permissions = null,
    ) shouldNotBe null
    privateDirectoryRefusal(
      MediaBytesEntry(MediaBytesEntryKind.Other, size = 0, ownedByUser = true),
      permissions = null,
    ) shouldNotBe null
  }

  @Test
  fun `the owner read off a path is the owner the OS reports`() {
    val mine = fileFor()
    val slash = File("/").toPath()

    entryAt(Path(mine.path))?.ownedByUser shouldBe true
    entryAt(Path(slash.toString()))?.ownedByUser shouldBe (Files.getOwner(slash) == Files.getOwner(mine.toPath()))
  }

  @Test
  fun `one entry that cannot be removed leaves the rest of the sweep running`() {
    val stuck = File(parent, "2147483646").also { it.mkdirs() }
    val held = File(stuck, "left.mp4").also { it.writeBytes(BYTES) }
    val others = (1..DEAD_DIRECTORIES).map { File(parent, (2147483646 - it).toString()) }
    others.forEach { dead ->
      dead.mkdirs()
      File(dead, "left.mp4").writeBytes(BYTES)
    }
    listOf(root, parent).forEach { setMode(it, OWNER_ONLY) }
    setMode(stuck, "r-x------")
    // Root ignores a directory's mode, so nothing here can refuse a delete.
    assumeFalse("this user can write through r-x------", Files.isWritable(stuck.toPath()))

    try {
      fileFor()

      others.forEach { it.exists() shouldBe false }
      held.exists() shouldBe true
    } finally {
      setMode(stuck, OWNER_ONLY)
    }
  }

  private class FakeAttributes(
    private val directory: Boolean = false,
    private val file: Boolean = false,
    private val link: Boolean = false,
    private val other: Boolean = false,
  ) : BasicFileAttributes {
    override fun isDirectory(): Boolean = directory

    override fun isRegularFile(): Boolean = file

    override fun isSymbolicLink(): Boolean = link

    override fun isOther(): Boolean = other

    override fun size(): Long = 0

    override fun fileKey(): Any? = null

    override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)

    override fun lastAccessTime(): FileTime = FileTime.fromMillis(0)

    override fun creationTime(): FileTime = FileTime.fromMillis(0)
  }

  private companion object {
    const val SPARSE_BYTES = 1024L * 1024 * 1024
    const val OWNER_ONLY = "rwx------"
    const val DEAD_DIRECTORIES = 8
    val BYTES = byteArrayOf(7, 8, 9)
  }
}
