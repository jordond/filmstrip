@file:OptIn(ExperimentalWasmJsInterop::class, InternalFilmstripApi::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A picture whose server never answers is refused by name once the wait runs out, and the same images still read the
 * pictures asked for after it.
 */
class WebOverlayFetchTimeoutTest {
  @Test
  fun aFetchThatNeverAnswersIsAbortedAndRefusedByNameAndTheNextPreloadStillWorks() =
    runTest {
      val uri = "https://filmstrip.test/never-answers.png"
      val fetchState = hangFetch(uri)
      val answering = "data:image/png;base64," + Base64.encode(pngOf(PICTURE))
      val resolver = BuiltInEffectResolver(OverlayImages(patience = PATIENCE))

      val refusal = resolver.prepareAndResolve(uri).shouldBeInstanceOf<EffectResolution.Unsupported>()
      refusal.message shouldContain uri
      fetchState.aborted shouldBe true

      resolver.prepareAndResolve(answering).shouldBeInstanceOf<EffectResolution.Resolved>()
    }

  @Test
  fun aFaceThatNeverLoadsIsLeftToTheFallbackOnceTheWaitRunsOut() =
    runTest {
      val fonts = hangFontLoads()
      try {
        val text = TextOverlay("never loads")
        val clip = Clip(MediaSource.ofUri("blob:clip"), effects = listOf(text))

        preloadOverlays(Composition(listOf(Track(listOf(clip)))), OverlayImages(patience = PATIENCE))

        fonts.loads shouldBe 1
      } finally {
        restoreFontLoads()
      }
    }

  @Test
  fun aFetchedPicturePastTheCeilingIsRefusedByNameAndOneAtItDecodes() =
    runTest {
      val png = pngOf(PICTURE)
      val source = ImageSource.ofUri("data:image/png;base64," + Base64.encode(png))
      val peak = png.size.toLong() + PICTURE.width * PICTURE.height * 16L

      val past = OverlayImages(ceiling = png.size - 1L).apply { preload(source) }[source]
      val fits = OverlayImages(ceiling = peak).apply { preload(source) }[source]

      past.shouldBeInstanceOf<OverlayDecode.Refused>().message shouldBe pictureTooLargeToRead(source, png.size - 1L)
      fits.shouldBeInstanceOf<OverlayDecode.Decoded>()
    }

  private suspend fun BuiltInEffectResolver.prepareAndResolve(uri: String): EffectResolution? {
    val overlay = ImageOverlay(ImageSource.ofUri(uri), Corner.TopStart)
    prepare(Composition(listOf(Track(listOf(Clip(MediaSource.ofUri("blob:clip"), effects = listOf(overlay)))))))
    return resolve(overlay, CAPABILITIES, ATTRIBUTES)
  }

  private companion object {
    val PATIENCE: Duration = 50.milliseconds
    val PICTURE = Size(5, 3)
    val FRAME = Size(64, 64)
    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 1.seconds),
      )
    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.WebGl,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 4_096,
        features = setOf(RenderFeature.TextRendering),
      )
  }
}

private external interface HungFetch : JsAny {
  val aborted: Boolean
}

private external interface HungFonts : JsAny {
  val loads: Int
}

private external fun <T : JsAny> eval(code: String): T

// Makes `fetch` of [uri] never settle, whatever signal it was handed, and every other URI go to the real one. The
// answer says whether the request was aborted.
private fun hangFetch(uri: String): HungFetch =
  eval(
    """
    (function () {
      var uri = '$uri';
      var state = { aborted: false };
      var previous = globalThis.fetch.bind(globalThis);
      globalThis.fetch = function (input, init) {
        if (input !== uri) return previous(input, init);
        if (init && init.signal) init.signal.addEventListener('abort', function () { state.aborted = true; });
        return new Promise(function () {});
      };
      return state;
    })()
    """,
  )

// Makes the fonts of the page never finish loading, and counts the loads asked for.
private fun hangFontLoads(): HungFonts =
  eval(
    """
    (function () {
      var state = { loads: 0 };
      document.fonts.load = function () {
        state.loads++;
        return new Promise(function () {});
      };
      return state;
    })()
    """,
  )

private fun restoreFontLoads() {
  eval<HungFonts>("(delete document.fonts.load, ({ loads: 0 }))")
}
