package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.player.PlaybackError

/**
 * What the size chip over the stage reads: the frame the composition outputs.
 *
 * The preview's own report where there is one. Before that, or with no preview at all, the size the edit works out to
 * from the source, or null with no source to work from.
 */
internal fun SampleAppState.compositionFrameLabel(): String? {
  previewOutput?.let { return "${it.width} x ${it.height}" }
  val height = edit.scaleHeight.takeIf { edit.scaleEnabled } ?: info?.video?.displaySize?.height ?: return null
  return "${(height * edit.outputAspect(sourceAspect)).toInt()} x $height"
}

/**
 * The sentence the stage shows for a preview that stopped.
 *
 * What a platform says for itself is written for a bug report, so it goes to the session log and stays off the stage.
 */
internal fun PlaybackError.stageSentence(): String =
  when (this) {
    is PlaybackError.BackendMissing -> "No preview backend here. The schematic stands in for it."
    is PlaybackError.DecoderUnavailable -> "This device has no decoder free for this clip. $SEE_DIAGNOSTICS"
    is PlaybackError.SourceNotExportable -> "This clip can't be previewed or exported."
    is PlaybackError.SourceUnreadable -> "The preview can't read this clip. $SEE_DIAGNOSTICS"
    is PlaybackError.Underlying -> "The preview stopped on a platform error. $SEE_DIAGNOSTICS"
    is PlaybackError.UnsupportedFormat -> "This device can't play this clip's format. $SEE_DIAGNOSTICS"
  }

private const val SEE_DIAGNOSTICS = "Diagnostics has the details."
