package dev.jordond.filmstrip.media

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import java.nio.file.Path as NioPath

internal actual fun sha256Of(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal actual fun currentProcessId(): Int = ProcessHandle.current().pid().toInt()

internal actual fun isProcessRunning(id: Int): Boolean = ProcessHandle.of(id.toLong()).isPresent

private fun nioPath(path: Path): NioPath = NioPath.of(path.toString())

private fun views(path: NioPath): Set<String> = path.fileSystem.supportedFileAttributeViews()

// Read off a file this process just made, because the user.name property can be overridden and a uid with no passwd
// entry has no name to look up.
private val processUser: UserPrincipal by lazy {
  val probe = Files.createTempFile("filmstrip-owner", null)
  try {
    Files.getOwner(probe)
  } finally {
    Files.deleteIfExists(probe)
  }
}

internal actual fun entryAt(path: Path): MediaBytesEntry? {
  val nio = nioPath(path)
  val attributes =
    try {
      Files.readAttributes(nio, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
      return null
    }
  return MediaBytesEntry(
    kind = entryKindOf(attributes),
    size = attributes.size(),
    // A file system with no notion of an owner, like FAT on Windows, has nobody else to be owned by.
    ownedByUser = OWNER_VIEW !in views(nio) || Files.getOwner(nio, LinkOption.NOFOLLOW_LINKS) == processUser,
  )
}

/**
 * What [attributes], read without following a link, say sits at a path.
 *
 * A Windows junction reads as a directory and as other at once, so a link or other is ruled out first and a junction
 * never counts as a real directory.
 */
internal fun entryKindOf(attributes: BasicFileAttributes): MediaBytesEntryKind =
  when {
    attributes.isSymbolicLink || attributes.isOther -> MediaBytesEntryKind.Other
    attributes.isDirectory -> MediaBytesEntryKind.Directory
    attributes.isRegularFile -> MediaBytesEntryKind.File
    else -> MediaBytesEntryKind.Other
  }

internal actual fun removeEntry(path: Path) {
  Files.deleteIfExists(nioPath(path))
}

internal actual fun moveOnto(
  source: Path,
  target: Path,
) {
  SystemFileSystem.atomicMove(source, target)
}

/**
 * Creates [directory] and any missing parents for their owner alone, and refuses it unless what is there is safe to
 * read and write through.
 *
 * The temporary directory is shared on Linux and macOS and the name is easy to guess, so another user can get there
 * first. On a file system with no POSIX permissions, which is Windows, a directory is created with whatever access its
 * parent passes down and only its kind and its owner can be checked, so the privacy of the per-user temporary directory
 * is what is left to rely on.
 */
internal actual fun ensurePrivateDirectory(directory: Path) {
  val nio = nioPath(directory)
  val posix = POSIX_VIEW in views(nio)
  try {
    creatingThroughRaces(directory) {
      if (posix) {
        Files.createDirectories(nio, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
      } else {
        Files.createDirectories(nio)
      }
    }
  } catch (_: FileAlreadyExistsException) {
    // Whoever made it, it is judged below just the same.
  }
  val permissions =
    if (posix) {
      Files.readAttributes(nio, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).permissions()
    } else {
      null
    }
  privateDirectoryRefusal(entryAt(directory), permissions)?.let { reason -> throw IOException("$directory $reason") }
}

/**
 * Why a directory can't hold a buffer, or null when it can.
 *
 * @param entry What is at the path, read without following a link.
 * @param permissions The directory's POSIX permissions, or null on a file system that has none.
 */
internal fun privateDirectoryRefusal(
  entry: MediaBytesEntry?,
  permissions: Set<PosixFilePermission>?,
): String? =
  when {
    entry?.kind != MediaBytesEntryKind.Directory -> "is not a real directory."
    !entry.ownedByUser -> "belongs to another user."
    permissions != null && !OWNER_ONLY.containsAll(permissions) -> "can be reached by other users."
    else -> null
  }

internal actual typealias MediaBytesLock = ReentrantLock

private const val POSIX_VIEW = "posix"
private const val OWNER_VIEW = "owner"

private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
