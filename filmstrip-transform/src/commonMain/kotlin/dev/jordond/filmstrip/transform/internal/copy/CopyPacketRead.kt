package dev.jordond.filmstrip.transform.internal.copy

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.negotiation.Ask

/**
 * What a backend's read of a copy's video packets over a [CopyPacketWindow] found.
 */
@InternalFilmstripApi
public sealed interface CopyPacketRead {
  /**
   * The packets in decode order.
   */
  public class Read(
    public val packets: List<CopyPacket>,
  ) : CopyPacketRead

  /**
   * The packets could not be listed, and [reason] says why in words a caller reads.
   */
  public class Failed(
    public val reason: String,
  ) : CopyPacketRead
}

/**
 * What [withCopyPackets] hands back: the ask to negotiate, or why the export is refused before it is negotiated.
 */
@InternalFilmstripApi
public sealed interface CopyAsk {
  /**
   * The ask to negotiate, with the copy's packets read in when it has a window to read them over.
   */
  public class Asked(
    public val ask: Ask,
  ) : CopyAsk

  /**
   * The copy's packets could not be read, so the export is refused the way an unreadable source is.
   */
  public class Refused(
    public val error: FilmstripError,
  ) : CopyAsk
}

/**
 * This ask with the video packets [read] lists over [window] in [Ask.copyPackets], or the ask itself when there is no
 * window or [read] reads none.
 *
 * [window] is what [dev.jordond.filmstrip.transform.internal.ExportPlanner.copyPacketWindowOf] names, and every plan,
 * preview grade and export of an engine reads its packets this way, so all three negotiate the same copy. A read that
 * fails refuses the export as [FilmstripError.SourceUnreadable] with the read's reason.
 */
@InternalFilmstripApi
public suspend fun Ask.withCopyPackets(
  window: CopyPacketWindow?,
  read: suspend (CopyPacketWindow) -> CopyPacketRead?,
): CopyAsk {
  if (window == null) return CopyAsk.Asked(this)
  return when (val listed = read(window)) {
    null -> {
      CopyAsk.Asked(this)
    }
    is CopyPacketRead.Failed -> {
      CopyAsk.Refused(FilmstripError.SourceUnreadable(window.source.describe(), listed.reason))
    }
    is CopyPacketRead.Read -> {
      CopyAsk.Asked(Ask(spec, device, infos, openings, layoutSize, grade, listed.packets))
    }
  }
}
