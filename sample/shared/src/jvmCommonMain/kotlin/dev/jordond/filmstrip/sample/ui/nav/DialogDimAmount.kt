package dev.jordond.filmstrip.sample.ui.nav

import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * How far an Android dialog window dims what is behind it, the `backdrop` token's alpha.
 *
 * Android dims with black at an amount and takes no colour, which is a limit of the platform, so the token's colour
 * does not reach the window.
 */
internal fun dialogDimAmount(): Float = Tokens.backdrop.alpha
