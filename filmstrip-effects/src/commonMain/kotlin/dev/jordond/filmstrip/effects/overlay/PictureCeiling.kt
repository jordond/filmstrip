package dev.jordond.filmstrip.effects.overlay

/**
 * The most bytes reading or drawing one overlay picture may hold at its peak, 1 GiB.
 *
 * Apple counts its reads and its draws at their colour against it, and a browser counts decoding a PNG's samples
 * beside the bitmap drawn from it. A picture whose count passes it is refused with a reason naming this figure, since
 * iOS ends an app past its memory limit without malloc ever failing and a browser tab runs out the same way.
 */
internal const val PICTURE_PEAK_CEILING: Long = 1L shl 30
