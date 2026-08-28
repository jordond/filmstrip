package dev.jordond.filmstrip

import android.content.Context

/**
 * The Android form: an application [Context].
 *
 * @property context The application context filmstrip's platform code runs against. Null where App
 *   Startup is disabled and the caller passed none, which each operation reports as a typed failure
 *   worded with [MISSING_CONTEXT]. Read by filmstrip's backends.
 */
public actual class PlatformContext(
  @property:InternalFilmstripApi public val context: Context?,
) {
  public companion object {
    /**
     * The message an operation reports when it needed a [Context] and had none.
     */
    @InternalFilmstripApi
    public const val MISSING_CONTEXT: String =
      "filmstrip could not reach an Application Context. Either keep androidx.startup's " +
        "InitializationProvider in the merged manifest, or build with Filmstrip(context)."
  }
}

/**
 * Wraps this context for use with [Filmstrip].
 *
 * Retains `applicationContext`, because a [Filmstrip] is scoped to a component graph and outlives
 * any single Activity.
 */
public fun Context.asPlatformContext(): PlatformContext = PlatformContext(applicationContext)

internal actual fun platformContext(): PlatformContext = PlatformContext(ApplicationContextHolder.get())
