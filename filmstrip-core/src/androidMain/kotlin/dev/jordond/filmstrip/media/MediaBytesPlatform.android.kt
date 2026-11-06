package dev.jordond.filmstrip.media

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

internal actual fun sha256Of(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal actual fun currentProcessId(): Int = Process.myPid()

internal actual fun isProcessRunning(id: Int): Boolean =
  try {
    Os.kill(id, 0)
    true
  } catch (failure: ErrnoException) {
    failure.errno != OsConstants.ESRCH
  }

internal actual fun entryAt(path: Path): MediaBytesEntry? {
  val status =
    try {
      Os.lstat(path.toString())
    } catch (failure: ErrnoException) {
      if (failure.errno == OsConstants.ENOENT) return null
      throw failure
    }
  val kind =
    when {
      OsConstants.S_ISDIR(status.st_mode) -> MediaBytesEntryKind.Directory
      OsConstants.S_ISREG(status.st_mode) -> MediaBytesEntryKind.File
      else -> MediaBytesEntryKind.Other
    }
  return MediaBytesEntry(kind, status.st_size, status.st_uid == Os.geteuid())
}

internal actual fun removeEntry(path: Path) {
  Os.remove(path.toString())
}

// The cache directory is the app's own, and no other user can reach it.
internal actual fun ensurePrivateDirectory(directory: Path) {
  creatingThroughRaces(directory) { SystemFileSystem.createDirectories(directory) }
}

internal actual typealias MediaBytesLock = ReentrantLock
