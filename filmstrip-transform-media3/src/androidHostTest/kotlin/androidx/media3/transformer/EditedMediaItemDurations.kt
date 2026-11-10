package androidx.media3.transformer

/**
 * How long media3 lays this item for, given [renderedDurationUs] of decoded input.
 *
 * This is the figure a sequence places the next item after, read through media3's own package so a test checks the
 * number media3 works out rather than a copy of it.
 */
internal fun EditedMediaItem.durationAfterEffectsUs(renderedDurationUs: Long): Long =
  getDurationAfterEffectsApplied(renderedDurationUs)
