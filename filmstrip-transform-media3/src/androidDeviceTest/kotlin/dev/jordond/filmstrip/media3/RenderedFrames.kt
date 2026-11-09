package dev.jordond.filmstrip.media3

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.inspector.frame.FrameExtractor
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media3.internal.bounds.readClips
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Draws a frame of an edit through the chain an export of it runs, and hands it back before any encoder sees it.
 *
 * The edit is lowered by the same engine an export goes through, with the built in and filter resolvers, and the
 * frame is drawn by media3's `FrameExtractor` over the lowered clip and composition chain. A read here differs from
 * one off the written file only by what the encoder moved.
 *
 * @param context The context the extractor's player is built in.
 */
internal class RenderedFrames(
  private val context: Context,
) {
  private val engine =
    media3ExportEngine(
      ComponentRegistry
        .Builder()
        .addEffectResolver(BuiltInEffectResolver())
        .addEffectResolver(FilterEffectResolver())
        .build(),
    )

  /**
   * The frame [composition] draws at [at] when exported at [spec], as 8-bit RGBA.
   *
   * The bitmap is a copy the caller owns. A frame that is not back within [FRAME_TIMEOUT] fails the read.
   */
  suspend fun frameAt(
    composition: Composition,
    spec: ExportSpec = ExportSpec(),
    at: Duration = Duration.ZERO,
  ): Bitmap {
    val resolved =
      when (val result = engine.resolve(composition, spec)) {
        is ResolveResult.Resolved -> result.composition
        is ResolveResult.Refused -> throw AssertionError("the edit would not lower: ${result.error.message}")
      }
    val readback = resolved.toMedia3Preview(resolved.readClips()).readbackAt(at, REVISION).shouldNotBeNull()

    // The extractor builds a player, which binds to the looper of the thread it is built on. It is only ever touched
    // on that thread, so a close on the way out queues behind whatever the extractor is still doing.
    val thread = HandlerThread("rendered-frames").apply { start() }
    val handler = Handler(thread.looper)
    val frame = CompletableDeferred<Bitmap>()
    var extractor: FrameExtractor? = null

    fun closeExtractor() {
      extractor?.close()
      extractor = null
    }

    try {
      handler.post {
        try {
          val opened = FrameExtractor.Builder(context, readback.span.item).setEffects(readback.effects).build()
          extractor = opened
          val future = opened.getFrame(readback.span.positionIn(at).inWholeMilliseconds)
          future.addListener(
            {
              // Copied and closed before the caller resumes, since media3 shares the bitmap between extractors and
              // one still open can answer the next read with the frame it already made.
              val copied = runCatching { checkNotNull(future.get().bitmap.copy(Bitmap.Config.ARGB_8888, false)) }
              closeExtractor()
              copied.onSuccess { frame.complete(it) }.onFailure { frame.completeExceptionally(it) }
            },
            handler::post,
          )
        } catch (
          @Suppress("TooGenericExceptionCaught") broken: Throwable,
        ) {
          closeExtractor()
          frame.completeExceptionally(broken)
        }
      }
      // The timeout runs off the test's virtual clock, which would otherwise skip straight past it.
      return withContext(Dispatchers.Default) { withTimeout(FRAME_TIMEOUT) { frame.await() } }
    } finally {
      handler.post { closeExtractor() }
      thread.quitSafely()
    }
  }

  private companion object {
    const val REVISION = 0L
    val FRAME_TIMEOUT = 30.seconds
  }
}
