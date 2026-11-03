package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi

internal actual val BARE_NAME_REFUSAL: String? = SINK_NEEDS_FULL_PATH

/**
 * The message [sinkParentRefusal] refuses a bare file name with on Android, where an app cannot write to the working
 * directory a relative path would land in.
 */
@InternalFilmstripApi
public const val SINK_NEEDS_FULL_PATH: String =
  "Android apps cannot write to the working directory, so the destination needs a full path."
