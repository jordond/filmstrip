package dev.jordond.filmstrip.ffmpeg.internal.probe

import dev.jordond.filmstrip.media.movieLengthOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.time.Duration

internal actual suspend fun movieLengthAt(path: String): Duration? =
  withContext(Dispatchers.IO) {
    try {
      RandomAccessFile(path, "r").use { file ->
        val channel = file.channel
        movieLengthOf(channel.size()) { start, count ->
          val buffer = ByteBuffer.allocate(count)
          while (buffer.hasRemaining() && channel.read(buffer, start + buffer.position()) >= 0) Unit
          buffer.array().copyOf(buffer.position())
        }
      }
    } catch (unreadable: IOException) {
      null
    }
  }
