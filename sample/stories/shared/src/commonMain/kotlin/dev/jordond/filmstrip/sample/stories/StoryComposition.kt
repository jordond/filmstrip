package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.MediaSource

/**
 * The edit a story plans, previews and exports: [source] filling a 9:16 frame under [look].
 *
 * The clip is cropped to the frame about its centre, so a landscape clip loses its sides and a portrait one nothing.
 * The elements are not here. They are drawn over the stage live and added as overlays only for an export.
 */
internal fun storyComposition(
  source: MediaSource,
  look: StoryLook,
): Composition =
  compositionOf {
    clip(source) { effects { crop(STORY_ASPECT, Fit.Crop) } }
    addEffects(look.effects)
  }

/**
 * The shape of a story, upright 9:16.
 */
internal val STORY_ASPECT: AspectRatio = AspectRatio(9, 16)

/**
 * The tallest a story is exported, which a 1080 by 1920 frame reaches. A smaller clip is not scaled up to it.
 */
internal const val STORY_MAX_HEIGHT: Int = 1920
