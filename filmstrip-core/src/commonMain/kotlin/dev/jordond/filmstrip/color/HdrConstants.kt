package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.HdrTransfer

// The reference levels and transfer coefficients every HDR conversion reads, in HdrSignalDouble.kt and HdrSignal.kt
// alike.

/**
 * The luminance a diffuse white sits at on an HDR export, in cd/m2.
 *
 * Graphics authored for SDR carry no brightness of their own, so they need one chosen for them.
 * This is the level broadcast practice puts captions and titles at, low enough that white bars
 * beside a video do not glare against it.
 */
@InternalFilmstripApi
public const val HDR_REFERENCE_WHITE_NITS: Float = 203f

/**
 * The nominal peak an HLG signal is graded against, in cd/m2.
 *
 * HLG carries no absolute brightness, so its system gamma is a function of the display it is shown
 * on. This is the peak the gamma below was picked for.
 */
@InternalFilmstripApi
public const val HLG_NOMINAL_PEAK_NITS: Float = 1000f

/**
 * The peak an ST 2084 signal is defined against, in cd/m2.
 */
@InternalFilmstripApi
public const val PQ_PEAK_NITS: Float = 10_000f

/**
 * The gamma an SDR display applies to the signal it is fed.
 *
 * An effect defined as a multiply on an encoded SDR value moves light by this power, so it is what
 * turns such a factor into a figure an HDR pipeline can use.
 */
@InternalFilmstripApi
public const val SDR_DISPLAY_GAMMA: Double = 2.2

/**
 * The opto-optical gamma sitting between HLG's scene light and a display's, at
 * [HLG_NOMINAL_PEAK_NITS].
 */
@InternalFilmstripApi
public const val HLG_SYSTEM_GAMMA: Double = 1.2

/**
 * The brightest display light this transfer carries, in cd/m2.
 */
@InternalFilmstripApi
public val HdrTransfer.peakNits: Float
  get() =
    when (this) {
      HdrTransfer.Pq -> PQ_PEAK_NITS
      HdrTransfer.Hlg -> HLG_NOMINAL_PEAK_NITS
    }

/**
 * The `m1` exponent of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_M1: Double = 2610.0 / 16384.0

/**
 * The `m2` exponent of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_M2: Double = (2523.0 / 4096.0) * 128.0

/**
 * The `c1` coefficient of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_C1: Double = 3424.0 / 4096.0

/**
 * The `c2` coefficient of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_C2: Double = (2413.0 / 4096.0) * 32.0

/**
 * The `c3` coefficient of the ST 2084 transfer function.
 */
@InternalFilmstripApi
public const val PQ_C3: Double = (2392.0 / 4096.0) * 32.0

/**
 * The `a` coefficient of the BT.2100 HLG transfer function.
 */
@InternalFilmstripApi
public const val HLG_A: Double = 0.17883277

/**
 * The `b` coefficient of the BT.2100 HLG transfer function.
 */
@InternalFilmstripApi
public const val HLG_B: Double = 0.28466892

/**
 * The `c` coefficient of the BT.2100 HLG transfer function.
 */
@InternalFilmstripApi
public const val HLG_C: Double = 0.55991073
