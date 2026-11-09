package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.ffmpeg.internal.lowering.SdColor
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeAnswer
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.media.MediaSource

/**
 * What the video sources of an edit say about their colour, or why one of them could not be read.
 */
internal sealed interface SourceColorRead {
  /**
   * @property sdColors The sources on SD primaries, and the colour each decodes with.
   * @property bt709Sources The sources tagged BT.709.
   * @property tailFormatSources The sources that decode to the plain 4:2:0 a clip's tail ends on.
   */
  class Read(
    val sdColors: Map<MediaSource, SdColor>,
    val bt709Sources: Set<MediaSource>,
    val tailFormatSources: Set<MediaSource>,
  ) : SourceColorRead

  class Refused(
    val error: FilmstripError,
  ) : SourceColorRead
}

/**
 * Reads the colour of every video source [composition] names through [FfmpegRuntime.colorRead].
 *
 * Audio has no colour to read, and a source [pathOf] finds no path for, or whose read finds nothing, is left out,
 * since a source a clip cannot read from is refused before these are looked at. A read stopped at its deadline refuses
 * the whole edit as [FilmstripError.TimedOut].
 *
 * @param pathOf The path ffprobe reads a source from, or null when it has none.
 */
internal suspend fun FfmpegRuntime.sourceColorsOf(
  toolchain: Toolchain,
  composition: Composition,
  pathOf: suspend (MediaSource) -> String?,
): SourceColorRead {
  val reads = mutableMapOf<MediaSource, FfmpegRuntime.ColorRead>()
  val sources =
    composition.tracks
      .filter { it.content != TrackContent.Audio }
      .flatMap { it.clips }
      .map { it.source }
      .distinct()
  for (source in sources) {
    val path = pathOf(source) ?: continue
    when (val read = colorRead(toolchain, path)) {
      is ProbeAnswer.Expired -> {
        return SourceColorRead.Refused(read.timedOut())
      }
      is ProbeAnswer.Answered -> {
        read.value?.let { reads[source] = it }
      }
    }
  }
  return SourceColorRead.Read(
    sdColors = reads.mapNotNull { (source, read) -> read.sdColor?.let { source to it } }.toMap(),
    bt709Sources = reads.filterValues { it.isTaggedBt709 }.keys,
    tailFormatSources = reads.filterValues { it.decodesToTailFormat }.keys,
  )
}
