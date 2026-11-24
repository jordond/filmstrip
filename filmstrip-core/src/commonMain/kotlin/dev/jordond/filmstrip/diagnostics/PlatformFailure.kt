package dev.jordond.filmstrip.diagnostics

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.EngineComponents
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What a platform said about a failure, beyond the error filmstrip mapped it to.
 *
 * A backend builds one where it turns a platform error into a [dev.jordond.filmstrip.FilmstripError] or a
 * [dev.jordond.filmstrip.player.PlaybackError], and hands both to [attachFailure]. Each property lands under the
 * [DiagnosticEvent.Detail] key of the same name, and a null one is left out of the event.
 *
 * @property backend The backend that failed, matching its [BackendInfo.name].
 * @property code The platform's numeric code.
 * @property codeName The name of [code].
 * @property domain The error domain, on platforms that have one.
 * @property codecName The codec component that was running.
 * @property codecRole `decoder` or `encoder`.
 * @property codecFormat The format the codec was configured with, as the platform prints it.
 * @property inputMime The sample MIME type of the input being decoded.
 * @property inputCodecs The RFC 6381 codecs string of the input being decoded.
 * @property inputSize The input's size as `<width>x<height>`.
 * @property inputFrameRate The input's frame rate.
 * @property causes One line per level of the platform's cause chain, outermost first.
 */
@InternalFilmstripApi
public class PlatformFailure(
  public val backend: String,
  public val code: Int? = null,
  public val codeName: String? = null,
  public val domain: String? = null,
  public val codecName: String? = null,
  public val codecRole: CodecRole? = null,
  public val codecFormat: String? = null,
  public val inputMime: String? = null,
  public val inputCodecs: String? = null,
  public val inputSize: String? = null,
  public val inputFrameRate: Float? = null,
  public val causes: List<String> = emptyList(),
)

/**
 * Which side of a codec failed.
 *
 * @property key How it is written under [DiagnosticEvent.Detail.CODEC_ROLE].
 */
@InternalFilmstripApi
public enum class CodecRole(
  public val key: String,
) {
  Decoder("decoder"),
  Encoder("encoder"),
}

/**
 * Attaches what the platform said to [error], the instance the backend is about to report.
 *
 * The failure event is emitted where the error reaches the caller, and it reads this back by identity there. Attach
 * before the error is published, from any thread. An error that never reaches the caller is forgotten once enough
 * newer ones are attached.
 *
 * @param error The exact [dev.jordond.filmstrip.FilmstripError] or [dev.jordond.filmstrip.player.PlaybackError]
 * instance the backend reports.
 * @param failure What the platform said about it.
 */
@InternalFilmstripApi
public fun EngineComponents.attachFailure(
  error: Any,
  failure: PlatformFailure,
) {
  failureNotes.attach(error, failure)
}

/**
 * The [PlatformFailure]s backends attached and the boundary has not reported yet, matched by identity.
 *
 * One per [EngineComponents], so a failure only reaches the listeners of the instance it happened in.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class FailureNotes {
  private val notes = AtomicReference(emptyList<Pair<Any, PlatformFailure>>())

  fun attach(
    error: Any,
    failure: PlatformFailure,
  ) {
    while (true) {
      val current = notes.load()
      val next = (current.filterNot { it.first === error } + (error to failure)).takeLast(KEPT)
      if (notes.compareAndSet(current, next)) return
    }
  }

  fun take(error: Any): PlatformFailure? {
    while (true) {
      val current = notes.load()
      val found = current.firstOrNull { it.first === error } ?: return null
      if (notes.compareAndSet(current, current.filterNot { it === found })) return found.second
    }
  }

  private companion object {
    const val KEPT = 16
  }
}
