package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.net.Uri
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.internal.AndroidScratch
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media3.internal.failure.Media3LoweringFailure
import java.io.File

internal fun MediaSource.toAndroidUri(): Uri =
  when (this) {
    is MediaSource.Path -> {
      Uri.fromFile(File(path))
    }
    is MediaSource.Uri -> {
      Uri.parse(uri)
    }
    is MediaSource.Bytes -> {
      // The probe already wrote this to the cache under a name taken from the bytes themselves, so
      // this finds the file rather than writing a second copy of it.
      cached { context -> AndroidScratch.fileFor(context, this) }
    }
    is MediaSource.Image -> {
      // Lowered by imageItem, which reaches the still through ImageSource.toAndroidUri instead.
      throw Media3LoweringFailure("${image.describe()} is a still, which is not lowered as a plain URI.")
    }
  }

/**
 * The URI media3 opens a still through, written to the cache first when the still is bytes.
 *
 * @param format The still format the probe named, used as the extension a cached copy is written
 *   under.
 */
internal fun ImageSource.toAndroidUri(format: String?): Uri =
  when (this) {
    is ImageSource.Path -> {
      Uri.fromFile(File(path))
    }
    is ImageSource.Uri -> {
      Uri.parse(uri)
    }
    is ImageSource.Bytes -> {
      val extension =
        format
          ?: throw Media3LoweringFailure(
            "A still handed over as bytes is written to a temporary file before it is encoded, " +
              "and nothing that read it named the format to write it under.",
          )
      cached { context -> AndroidScratch.fileFor(context, bytes, extension) }
    }
  }

/**
 * The URI of the file [write] put the bytes in.
 */
private fun cached(write: (Context) -> File): Uri {
  val context =
    FilmstripContext.get()
      ?: throw Media3LoweringFailure(
        "An in-memory source is written to a temporary file before it is encoded, and " +
          FilmstripContext.MISSING_CONTEXT,
      )

  return try {
    Uri.fromFile(write(context))
  } catch (failure: MediaBytesFileFailure) {
    throw Media3LoweringFailure(failure.message)
  }
}
