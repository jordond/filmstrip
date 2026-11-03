package dev.jordond.filmstrip

import android.content.Context
import dev.jordond.filmstrip.FilmstripContext.MISSING_CONTEXT

/**
 * The application [Context] filmstrip's Android code runs against.
 *
 * App Startup installs it before `Application.onCreate` through [FilmstripInitializer]. An app that
 * disables App Startup installs it by building with `Filmstrip(context)` instead. Filmstrip's
 * Android backends read it, and each reports [MISSING_CONTEXT] as a typed failure when nothing
 * installed one. Previewing, rendering a frame and exporting always need it. Probing and planning
 * need it only for a uri or an in-memory video, and writing a still only for a `content://` uri.
 * A still written to a temporary sink lands in the system temporary directory without one.
 */
@InternalFilmstripApi
public object FilmstripContext {
  @Volatile
  private var applicationContext: Context? = null

  /**
   * The message an operation reports when it needed a [Context] and had none.
   */
  public const val MISSING_CONTEXT: String =
    "filmstrip could not reach an Application Context. Either keep androidx.startup's " +
      "InitializationProvider in the merged manifest, or build with Filmstrip(context)."

  /**
   * Retains [context]'s application context.
   *
   * The application context is one object per process, so installing it twice is harmless.
   */
  public fun install(context: Context) {
    applicationContext = context.applicationContext
  }

  /**
   * The installed context, or null when nothing installed one.
   */
  public fun get(): Context? = applicationContext

  /**
   * Forgets the installed context, as if nothing had installed one.
   *
   * Tests call this to cover the paths that run without a context. Nothing else clears it, so a
   * process that installed one keeps it.
   */
  public fun clear() {
    applicationContext = null
  }
}
