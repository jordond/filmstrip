package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.copy.CopySupport
import dev.jordond.filmstrip.transform.internal.copy.copyBlockers
import dev.jordond.filmstrip.transform.internal.copy.copyOpening
import dev.jordond.filmstrip.transform.internal.copy.exportPath
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr

/**
 * Whether the streams copy across and what happens to an HDR grade, settled before the codec,
 * because a grade that survives to the encoder decides which codecs are eligible at all.
 */
internal fun gradeOf(
  edit: EditComposition,
  ask: Ask,
  framing: Framing,
  copySupport: CopySupport,
  canToneMap: Boolean,
  toneMapLimit: (List<MediaSource>) -> String?,
): Phase<Grade> {
  val sourceTransfers =
    edit.tracks
      .flatMap { it.clips }
      .mapNotNull { ask.infos[it.source]?.video }
      .mapTo(mutableSetOf()) { it.hdrTransfer }
  val sourceIsHdr = sourceTransfers.any { it != null }
  // Sources that disagree, an HDR clip sat beside an SDR one included, have no one transfer that
  // describes the output, so there is no grade to keep and the export tone-maps or refuses.
  val gradesAgree = sourceTransfers.size == 1
  // The one clip a copy could ever cover, since a copy needs a single track carrying a single
  // clip. Where that copy would open is settled here and nowhere else, so a backend lays the
  // window this resolves to.
  val onlyClip =
    edit.tracks
      .singleOrNull()
      ?.clips
      ?.singleOrNull()
  val copyOpening = onlyClip?.let { copyOpening(it, ask.openings[it.source]) }
  // Measured against the requested frame, since a copy runs no encoder and so has no ceiling to
  // clamp to.
  val blockers =
    copyBlockers(
      support = copySupport,
      edit = edit,
      spec = ask.spec,
      heightPx = framing.heightPx,
      sourceSize = framing.firstVideo.displaySize,
      requestedSize = framing.requestedSize,
      copyOpening = copyOpening,
      info = ask.infos.getValue(framing.firstClip.source),
    )
  val copyKeepsHdr = blockers.isEmpty()
  val sdrForcedBy = if (sourceIsHdr) edit.effectsThatRequireSdr() else emptyList()
  val hdr =
    resolveHdr(
      ask.spec.hdr,
      sourceIsHdr,
      gradesAgree,
      ask.device.supportsHdrEncoding,
      canToneMap,
      copyKeepsHdr,
      needsSdr = sdrForcedBy.isNotEmpty(),
    )
      ?: return Phase.Refused(
        invalid(
          when {
            ask.spec.hdr == HdrMode.KeepHdr && sdrForcedBy.isNotEmpty() -> keepRefusedBy(sdrForcedBy)
            !canToneMap -> NO_HDR_PATH
            !gradesAgree -> MIXED_GRADES
            else -> NO_HDR_ENCODER
          },
        ),
      )
  if (hdr == ResolvedHdr.ToneMap) {
    toneMapLimit(drawnSdrSources(edit, ask))?.let { return Phase.Refused(invalid(it)) }
  }
  val path = exportPath(copyKeepsHdr, hdr)

  return Phase.Next(
    Grade(
      sourceIsHdr = sourceIsHdr,
      gradesAgree = gradesAgree,
      hdr = hdr,
      // A source that is HLG must not be written as PQ, so the transfer travels with the grade.
      // Keeping a grade already means the sources agree on one, so there is exactly one to take.
      hdrTransfer = if (hdr == ResolvedHdr.Keep) sourceTransfers.singleOrNull() else null,
      // A copy that already keeps the grade needs nothing from the ladder, so it does not pin the
      // codec to HEVC for a stream nobody is going to encode.
      encodesHdr = sourceIsHdr && hdr == ResolvedHdr.Keep && !copyKeepsHdr,
      path = path,
      // A copy the grade alone ruled out has nothing else wrong with it, so that is the only term
      // left to report.
      copyBlockedBy =
        if (copyKeepsHdr && path != ExportPath.Copy) listOf(CopyBlocker.GradeMustToneMap) else blockers,
      // Non-null only on a copy, and then it is the window every backend lays.
      copyOpensAt = copyOpening.takeIf { path == ExportPath.Copy },
      sdrForcedBy = sdrForcedBy,
    ),
  )
}

private fun drawnSdrSources(
  edit: EditComposition,
  ask: Ask,
): List<MediaSource> =
  edit.tracks
    .filter { it.content != TrackContent.Audio }
    .flatMap { it.clips }
    .map { it.source }
    .distinct()
    .filter { source ->
      source !is MediaSource.Image && ask.infos[source]?.video?.let { it.hdrTransfer == null } == true
    }

/**
 * The id of every effect anywhere in this edit that needs SDR frames, once each, in the order they are written.
 *
 * An inert effect is taken out of the edit before it draws, so it needs nothing.
 */
@InternalFilmstripApi
public fun EditComposition.effectsThatRequireSdr(): List<String> =
  (effects + tracks.flatMap { track -> track.effects + track.clips.flatMap { it.effects } })
    .filter { it.requiresSdr && !it.isInert }
    .map { it.id }
    .distinct()

private fun keepRefusedBy(ids: List<String>): String =
  "HdrMode.KeepHdr cannot keep this grade, since ${ids.joinToString()} needs SDR frames. Use HdrMode.Auto or " +
    "HdrMode.ToneMapToSdr to bring it down to SDR, or take the effect out."

private const val NO_HDR_ENCODER =
  "HDR was required and this device cannot encode it. Use HdrMode.Auto to tone-map instead."

private const val NO_HDR_PATH =
  "This source carries an HDR grade and this engine can neither encode it nor tone-map it, so " +
    "there is nowhere to put it."

private const val MIXED_GRADES =
  "These sources do not share one HDR transfer, so no single grade describes the output. Use " +
    "HdrMode.Auto or HdrMode.ToneMapToSdr to bring them down to SDR together."
