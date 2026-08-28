package dev.jordond.filmstrip

import android.content.Context

/**
 * Creates a [Filmstrip] against an explicit [Context].
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
 * @return A builder bound to [context].
 */
public fun FilmstripBuilder(context: Context): FilmstripBuilder = FilmstripBuilder(context.asPlatformContext())
