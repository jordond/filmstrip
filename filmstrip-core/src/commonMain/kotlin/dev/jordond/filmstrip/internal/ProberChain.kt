package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.solidMediaInfoOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds the prober an export backend should read its sources with.
 *
 * Registered probers answer first and [last] answers after them. By default that is core's own platform prober, so a
 * backend learns exactly what [dev.jordond.filmstrip.Filmstrip.probe] would. A prober that declines does not stop
 * the next one, and the reported failure is the first one, which comes from the prober registered last and therefore
 * knows the most. An answer that read no video and no audio track counts as a decline that the source is unreadable,
 * since there is nothing in it to edit. A source whose video has no decoder is a decline too, and its read keeps what
 * the prober learned so a planner can still use the audio, unless nothing else is left in it. A prober that turns up
 * twice in the chain is only asked once, and one a registered [MediaProberDecorator] decorates is not asked at all,
 * since the decorator has already asked it. A [MediaSource.Solid] is answered before any of them, since there is
 * nothing in one to read.
 *
 * @param components The components registered on the owning [dev.jordond.filmstrip.Filmstrip].
 * @param last The prober asked after every registered one, for a backend that reads its sources its own way.
 * @return A prober that walks the whole chain.
 */
@InternalFilmstripApi
public fun chainedProber(
  components: EngineComponents,
  last: MediaProber = platformProber(),
): SourceProber {
  val registered = components.mediaProberFactories.mapNotNull { it.create() }
  val decorated = registered.filterIsInstance<MediaProberDecorator>().map { it.decorated }.toSet()
  val chain = (registered + last).distinct().filterNot { it in decorated }

  return object : SourceProber {
    override suspend fun read(source: MediaSource): SourceRead =
      if (source is MediaSource.Solid) SourceRead.Read(solidMediaInfoOf(source)) else chain.firstAnswerFor(source)
  }
}

private suspend fun List<MediaProber>.firstAnswerFor(source: MediaSource): SourceRead {
  var firstFailure: SourceRead? = null
  for (prober in this) {
    currentCoroutineContext().ensureActive()

    when (val result = prober.readSource(source).requireTrack(source)) {
      is SourceRead.Read -> return result
      is SourceRead.NoVideoDecoder, is SourceRead.Failed -> if (firstFailure == null) firstFailure = result
    }
  }
  return checkNotNull(firstFailure) { "core's own prober is always last in the chain" }
}

private fun SourceRead.requireTrack(source: MediaSource): SourceRead {
  val info =
    when (this) {
      is SourceRead.Read -> info
      is SourceRead.NoVideoDecoder -> info
      is SourceRead.Failed -> return this
    }
  return when {
    info.video == null && info.audioTracks.isEmpty() && this is SourceRead.NoVideoDecoder -> {
      SourceRead.Failed(error)
    }
    info.video == null && info.audioTracks.isEmpty() -> {
      SourceRead.Failed(FilmstripError.SourceUnreadable(source = source.describe(), message = NO_READABLE_TRACK))
    }
    else -> {
      this
    }
  }
}

/**
 * Why a probe that answered with no video and no audio track is refused. A media framework that cannot read a file can
 * hand back empty metadata rather than an error, so an empty answer is read as a failed read.
 */
internal const val NO_READABLE_TRACK: String = "No video or audio track in the source could be read."

/**
 * A [MediaProber] that asks [decorated] first and builds its answer on top of what that one read.
 *
 * Its answer already holds [decorated]'s, failure included, so [chainedProber] does not ask [decorated] again.
 */
@InternalFilmstripApi
public interface MediaProberDecorator : MediaProber {
  /**
   * The prober this one asks before answering.
   */
  public val decorated: MediaProber
}

/**
 * Core's own prober for this platform, the one [chainedProber] asks last.
 *
 * A backend that reads part of a source better than the platform can decorates this one rather than probing the whole
 * source again. The JVM and the browser have none, so there it reports that no backend is registered. Every call
 * returns a prober equal to the others, so the chain can tell a decorator wraps the one it would ask last.
 *
 * @return A prober over this platform's own media framework.
 */
@InternalFilmstripApi
public fun platformProber(): MediaProber = PlatformMediaProber()

private class PlatformMediaProber : SourceProber {
  private val platform = PlatformProber()

  override suspend fun read(source: MediaSource): SourceRead = platform.read(source)

  override fun equals(other: Any?): Boolean = other is PlatformMediaProber

  override fun hashCode(): Int = PlatformMediaProber::class.hashCode()
}
