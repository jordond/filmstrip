@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.failure

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.diagnostics.CodecRole
import dev.jordond.filmstrip.diagnostics.PlatformFailure
import dev.jordond.filmstrip.diagnostics.causeChainOf
import dev.jordond.filmstrip.diagnostics.chainOf
import dev.jordond.filmstrip.webcodecs.internal.interop.InputAudioTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.InputVideoTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.asString
import dev.jordond.filmstrip.webcodecs.internal.interop.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The name WebCodecs registers its [dev.jordond.filmstrip.diagnostics.BackendInfo] under.
 *
 * A failure the player or the export engine reports names its backend with this, so a listener can match the event
 * to the backend info.
 */
@InternalFilmstripApi
public const val WEBCODECS_BACKEND: String = "webcodecs"

/**
 * What the browser said about [error], for the failure event a player or an export reports.
 *
 * The name is that of the first `DOMException` in what the browser threw, like `EncodingError`. A failure from a
 * decoder or an encoder also names which one and the codec string it was opened with. The causes are the JavaScript
 * `cause` chain, or the Kotlin one when nothing in JavaScript threw. WebCodecs gives no numeric code, no component name
 * and no input format.
 *
 * @param error What the backend caught.
 */
@InternalFilmstripApi
public fun webCodecsFailure(error: Throwable): PlatformFailure {
  val site = error as? BrowserCodecFailure
  val thrown = site?.cause ?: error
  val chain = jsValueOf(thrown)?.let { chainOf(it, ::jsCauseOf) }
  return PlatformFailure(
    backend = WEBCODECS_BACKEND,
    codeName = chain?.firstNotNullOfOrNull(::domExceptionNameOf),
    codecRole = site?.role,
    codecFormat = site?.format,
    causes = chain?.map(::describeJs) ?: causeChainOf(thrown),
  )
}

/**
 * A failure raised by a decoder or an encoder, carrying which one it was.
 *
 * Its message is the message of what was thrown, so wrapping it changes nothing a caller reads.
 *
 * @property role Whether a decoder or an encoder threw.
 * @property format The codec string it was opened with, when known.
 */
internal class BrowserCodecFailure(
  val role: CodecRole,
  val format: String?,
  override val cause: Throwable,
) : Exception(cause.message, cause)

/**
 * A decoder or an encoder, named so a failure raised while it runs says which one it was.
 *
 * @property role Whether it decodes or encodes.
 * @property format The codec string it was opened with, when known.
 */
internal class CodecSite(
  val role: CodecRole,
  val format: String?,
)

/**
 * Runs [block] and rethrows anything it throws as a [BrowserCodecFailure] naming this site. A cancellation, and a
 * failure that already names its site, pass through as they are.
 */
internal inline fun <T> CodecSite?.running(block: () -> T): T {
  if (this == null) return block()
  return try {
    block()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (named: BrowserCodecFailure) {
    throw named
  } catch (
    @Suppress("TooGenericExceptionCaught") failure: Throwable,
  ) {
    throw BrowserCodecFailure(role, format, failure)
  }
}

/**
 * This track's decoder, named by the codec string it decodes.
 */
internal suspend fun InputVideoTrack.decoderSite(): CodecSite =
  CodecSite(CodecRole.Decoder, getCodecParameterString().await()?.asString())

/**
 * This track's decoder, named by the codec string it decodes.
 */
internal suspend fun InputAudioTrack.decoderSite(): CodecSite =
  CodecSite(CodecRole.Decoder, getCodecParameterString().await()?.asString())

/**
 * The JavaScript value [error] carries, or null when it is a Kotlin exception with none.
 */
internal expect fun jsValueOf(error: Throwable): JsAny?

/**
 * The `name` of [value] when it is a `DOMException`, or null for anything else.
 */
internal expect fun domExceptionNameOf(value: JsAny): String?

/**
 * The `cause` of [value], or null when it has none or the cause is not an object.
 */
internal expect fun jsCauseOf(value: JsAny): JsAny?

/**
 * [value] as JavaScript's `String()` writes it, which is `name: message` for an error.
 */
internal expect fun describeJs(value: JsAny): String
