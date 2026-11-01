package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The opaque text colours a glyph ink test draws ordinary text in, as ARGB.
 *
 * They run from black and white through greys, the primaries and secondaries, dark saturated colours and mid colours
 * apart in every channel. `0xFF172E1B` is the ink Chrome's premultiply was measured drifting furthest on.
 */
@InternalFilmstripApi
public val GLYPH_INKS: List<Int> =
  listOf(
    0xFF000000,
    0xFFFFFFFF,
    0xFF404040,
    0xFF808080,
    0xFFC0C0C0,
    0xFFFF0000,
    0xFF00FF00,
    0xFF0000FF,
    0xFFFFFF00,
    0xFF00FFFF,
    0xFFFF00FF,
    0xFF172E1B,
    0xFF800000,
    0xFF004000,
    0xFF000080,
    0xFF3A0A4F,
    0xFF1B2A6B,
    0xFF4B1D0E,
    0xFF0B3D3A,
    0xFF3060C0,
    0xFFC03060,
    0xFFFF4000,
    0xFF0040FF,
    0xFFE0FF10,
    0xFF60A040,
    0xFFA0522D,
    0xFF2E8B57,
    0xFF7F7FFF,
    0xFFD2B48C,
    0xFFF0E68C,
  ).map { it.toInt() }
