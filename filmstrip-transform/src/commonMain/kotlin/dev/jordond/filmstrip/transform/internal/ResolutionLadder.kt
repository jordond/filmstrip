package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * The sizes an encoder is probed at, largest first.
 *
 * A backend whose platform publishes no ceiling asks for each rung in turn and reports the first
 * that opens as that encoder's largest size, so these are the sizes filmstrip is willing to claim
 * rather than a measurement of any one device. A backend that stops short of a rung clamps against
 * a limit of its own and names whose it is.
 */
@InternalFilmstripApi
public val RESOLUTION_LADDER: List<Size> =
  listOf(
    Size(7680, 4320),
    Size(3840, 2160),
    Size(1920, 1080),
    Size(1280, 720),
    Size(640, 480),
  )

/**
 * The size the HDR probe opens its encoder at.
 *
 * Whether an encoder takes a ten-bit profile does not depend on the size it opens at, so every
 * backend asks it at one rung and at the cheapest. An encoder that lands the profile here is
 * trusted to land it at whatever size the plan goes on to ask for.
 */
@InternalFilmstripApi
public val HDR_PROBE_SIZE: Size = RESOLUTION_LADDER.last()

/**
 * The square sizes an encoder's smallest frame is probed at, smallest first.
 *
 * A backend whose platform publishes no floor asks for each rung in turn through [smallestAccepted]. A square reads
 * a floor that holds per side. An encoder whose floor is an area reports the next square up, which is stricter than
 * it needs and never a size it refuses.
 */
@InternalFilmstripApi
public val FLOOR_LADDER: List<Size> = listOf(2, 4, 8, 16, 32, 64, 96, 128, 144, 176, 256).map { Size(it, it) }

/**
 * The first rung of [ladder] that [opens] answers true for, or null when none does.
 *
 * @param ladder The sizes to try, smallest first.
 * @param opens Whether the encoder takes a frame of this size.
 * @return The smallest size the encoder takes.
 */
@InternalFilmstripApi
public inline fun smallestAccepted(
  ladder: List<Size> = FLOOR_LADDER,
  opens: (Size) -> Boolean,
): Size? = ladder.firstOrNull { opens(it) }
