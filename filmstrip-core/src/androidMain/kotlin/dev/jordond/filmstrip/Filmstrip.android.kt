package dev.jordond.filmstrip

import android.content.Context

/**
 * Creates a [Filmstrip] against an explicit [Context].
 *
 * For an app that disables App Startup, or a test running outside it. Retains the application
 * context for the whole process, so every [Filmstrip] built afterwards sees it too.
 *
 * @param context Any context. Its application context is what gets retained.
 * @param block Registers components on the builder.
 * @return The configured instance.
 */
public fun Filmstrip(
  context: Context,
  block: FilmstripBuilder.() -> Unit = {},
): Filmstrip = FilmstripBuilder(context).apply(block).build()

/**
 * Assembles a [Filmstrip] against an explicit [Context].
 *
 * @param context Any context. Its application context is what gets retained.
 * @return A builder, with [context] installed for the process.
 */
@OptIn(InternalFilmstripApi::class)
public fun FilmstripBuilder(context: Context): FilmstripBuilder {
  FilmstripContext.install(context)
  return FilmstripBuilder()
}
