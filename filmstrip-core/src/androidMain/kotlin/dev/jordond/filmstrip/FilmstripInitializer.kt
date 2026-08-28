package dev.jordond.filmstrip

import android.content.Context
import androidx.startup.Initializer

/**
 * Captures the application [Context] for [Filmstrip], before `Application.onCreate` runs.
 *
 * Registered through App Startup's `InitializationProvider` by filmstrip's own manifest, which
 * merges into the consuming app.
 */
public class FilmstripInitializer : Initializer<Unit> {
  override fun create(context: Context) {
    ApplicationContextHolder.install(context)
  }

  override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

internal object ApplicationContextHolder {
  @Volatile
  private var applicationContext: Context? = null

  fun install(context: Context) {
    applicationContext = context.applicationContext
  }

  fun get(): Context? = applicationContext
}
