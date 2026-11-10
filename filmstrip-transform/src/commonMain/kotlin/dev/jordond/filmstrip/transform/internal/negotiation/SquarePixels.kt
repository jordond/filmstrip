package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.displaySizeOf

/**
 * Whether an effect at this stage is handed a clip's frame with square pixels.
 *
 * Color and spatial effects run on a frame with non-square pixels as it is stored. Before the first geometry or
 * composite effect of a clip, a backend that hands effects the stored frame widens it to the size it shows, and
 * [dev.jordond.filmstrip.effect.Attributes.storedSize] is the frame's own size from that effect on.
 */
@InternalFilmstripApi
public val EffectStage.takesSquarePixels: Boolean
  get() = this == EffectStage.Geometry || this == EffectStage.Composite

/**
 * The size of [video]'s frame as its pixels are stored, turned by its rotation but not yet widened by its pixel aspect.
 *
 * It is [VideoTrackInfo.displaySize] for a frame of square pixels.
 */
@InternalFilmstripApi
public fun storedSizeOf(video: VideoTrackInfo): Size = displaySizeOf(video.codedSize, video.rotationDegrees, 1f)
