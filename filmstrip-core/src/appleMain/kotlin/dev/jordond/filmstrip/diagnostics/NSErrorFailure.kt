package dev.jordond.filmstrip.diagnostics

import dev.jordond.filmstrip.InternalFilmstripApi
import platform.Foundation.NSError
import platform.Foundation.NSUnderlyingErrorKey

/**
 * What this error says about a failure, for the failure event a player or an export reports.
 *
 * The code and domain are this error's own. The causes walk [NSUnderlyingErrorKey] down from this error, one
 * `<domain> <code>: <description>` line per level, which is where AVFoundation puts the status that says what went
 * wrong.
 *
 * @param backend The backend that failed, matching its [BackendInfo.name].
 * @param role Which side of a codec failed, or null when the error does not come from one.
 */
@InternalFilmstripApi
public fun NSError.platformFailure(
  backend: String,
  role: CodecRole? = null,
): PlatformFailure =
  PlatformFailure(
    backend = backend,
    code = code.toInt(),
    domain = domain,
    codecRole = role,
    causes =
      chainOf(this) { it.userInfo[NSUnderlyingErrorKey] as? NSError }.map {
        "${it.domain} ${it.code}: ${it.localizedDescription}"
      },
  )
