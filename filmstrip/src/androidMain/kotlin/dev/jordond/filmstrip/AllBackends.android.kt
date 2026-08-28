package dev.jordond.filmstrip

import android.content.Context

/**
 * Creates a fully-registered [Filmstrip] against an explicit [Context].
 *
 * For an app that disables App Startup, or a test running outside it. Prefer `Filmstrip.create()`
 * wherever App Startup supplies the context.
 *
 * @param context Any context. Its application context is what gets retained.
 * @param block Registers anything extra on the builder.
 * @return The configured instance.
 */
public fun Filmstrip.Companion.create(
  context: Context,
  block: FilmstripBuilder.() -> Unit = {},
): Filmstrip = FilmstripBuilder(context).allBackends().apply(block).build()
