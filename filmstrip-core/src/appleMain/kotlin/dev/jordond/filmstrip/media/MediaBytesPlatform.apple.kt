package dev.jordond.filmstrip.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSLock
import platform.posix.ENOENT
import platform.posix.ESRCH
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.geteuid
import platform.posix.getpid
import platform.posix.kill
import platform.posix.lstat
import platform.posix.remove
import platform.posix.stat
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256Of(bytes: ByteArray): ByteArray {
  val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
  // Pinning an empty array has no element to take the address of.
  val input = if (bytes.isEmpty()) ByteArray(1) else bytes
  input.usePinned { pinnedInput ->
    digest.usePinned { pinnedDigest ->
      CC_SHA256(pinnedInput.addressOf(0), bytes.size.convert(), pinnedDigest.addressOf(0).reinterpret<UByteVar>())
    }
  }
  return digest
}

internal actual fun currentProcessId(): Int = getpid()

@OptIn(ExperimentalForeignApi::class)
internal actual fun isProcessRunning(id: Int): Boolean = kill(id, 0) == 0 || errno != ESRCH

@OptIn(ExperimentalForeignApi::class)
internal actual fun entryAt(path: Path): MediaBytesEntry? =
  memScoped {
    val status = alloc<stat>()
    if (lstat(path.toString(), status.ptr) != 0) {
      if (errno == ENOENT) return null
      throw IOException("$path can't be read: ${strerror(errno)?.toKString()}")
    }
    val kind =
      when (status.st_mode.toInt() and S_IFMT) {
        S_IFDIR -> MediaBytesEntryKind.Directory
        S_IFREG -> MediaBytesEntryKind.File
        else -> MediaBytesEntryKind.Other
      }
    MediaBytesEntry(kind, status.st_size, status.st_uid == geteuid())
  }

// A failed remove only means another sweep got there first, or that the next one tries again.
internal actual fun removeEntry(path: Path) {
  remove(path.toString())
}

internal actual fun moveOnto(
  source: Path,
  target: Path,
) {
  SystemFileSystem.atomicMove(source, target)
}

// The temporary directory is the app's own on iOS and the user's own on macOS.
internal actual fun ensurePrivateDirectory(directory: Path) {
  creatingThroughRaces(directory) { SystemFileSystem.createDirectories(directory) }
}

internal actual class MediaBytesLock actual constructor() {
  private val lock = NSLock()

  actual fun lock() = lock.lock()

  actual fun unlock() = lock.unlock()
}
