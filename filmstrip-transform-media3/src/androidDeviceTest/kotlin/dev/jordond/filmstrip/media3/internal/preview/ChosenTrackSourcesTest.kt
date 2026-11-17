package dev.jordond.filmstrip.media3.internal.preview

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.CompositionPlayer
import com.google.common.base.Supplier
import dev.jordond.filmstrip.media3.Media3ExportProbe
import dev.jordond.filmstrip.media3.internal.bounds.ChosenTracks
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The media source factory a preview hands `CompositionPlayer`, checked against the one the player builds for itself.
 *
 * Camel case test names, because dex below version 040, which needs API 30, rejects a space in a method name.
 */
@UnstableApi
class ChosenTrackSourcesTest {
  private val probe = Media3ExportProbe()
  private val context: Context = probe.context

  // Read off the player's builder, so a media3 release that builds its default differently fails here.
  @Test
  fun anItemWithNoChosenTrackIsBuiltTheWayCompositionPlayerBuildsItsOwn() {
    val supplier =
      CompositionPlayer.Builder::class.java
        .getDeclaredField("mediaSourceFactorySupplier")
        .apply { isAccessible = true }
        .get(CompositionPlayer.Builder(context)) as Supplier<*>

    val own = settingsOf(supplier.get()!!)

    own.shouldNotBeEmpty()
    settingsOf(playerFactory(context, null)) shouldBe own
  }

  // The provider is asked while the source is built, and stops the build there so nothing past it is needed.
  @Test
  fun aSettingHandedToTheFactoryReachesTheSourceOfAnItemWithAChosenTrack() {
    val factory = ChosenTrackMediaSourceFactory(context)
    factory.setDrmSessionManagerProvider { item -> throw ProviderAsked(item.localConfiguration?.uri) }
    val uri = Uri.fromFile(probe.fixtureFile())
    val item =
      MediaItem
        .Builder()
        .setUri(uri)
        .setTag(ChosenTracks(audioOrdinal = 0))
        .build()

    assertFailsWith<ProviderAsked> { factory.createMediaSource(item) }.uri shouldBe uri
  }

  @Test
  fun everyFrameReaderIsHandedTheSameFactory() {
    readbackSourceFactory(context) shouldBeSameInstanceAs readbackSourceFactory(context.applicationContext)
  }

  // media3 only lets a source take another trim in place when the trim is cut by the time each frame is shown at.
  @Test
  fun aFrameReadersSourceCutsATrimByTheTimeAFrameIsShownAt() {
    val trimmed = trimmedItem(TRIM_END_MS)

    val source = readbackSourceFactory(context).createMediaSource(trimmed)

    source.canUpdateMediaItem(trimmedItem(TRIM_END_MS * 2)) shouldBe true
    DefaultMediaSourceFactory(context)
      .createMediaSource(trimmed)
      .canUpdateMediaItem(trimmedItem(TRIM_END_MS * 2)) shouldBe false
  }

  private fun trimmedItem(endMs: Long): MediaItem =
    MediaItem
      .Builder()
      .setUri(Uri.fromFile(probe.fixtureFile()))
      .setTag(ChosenTracks(audioOrdinal = 0))
      .setClippingConfiguration(
        MediaItem.ClippingConfiguration
          .Builder()
          .setEndPositionMs(endMs)
          .build(),
      ).build()

  private class ProviderAsked(
    val uri: Uri?,
  ) : RuntimeException()

  // Every field reachable from [root] through media3's own classes, as a primitive value or the class it holds.
  private fun settingsOf(root: Any): Map<String, String> {
    val settings = sortedMapOf<String, String>()
    val seen = IdentityHashMap<Any, Unit>()

    fun walk(
      value: Any?,
      path: String,
      depth: Int,
    ) {
      when {
        value == null -> {
          settings[path] = "null"
        }
        value is Number || value is Boolean || value is String || value is Enum<*> -> {
          settings[path] = value.toString()
        }
        value is Map<*, *> -> {
          settings[path] = "map of ${value.size}"
        }
        !value.javaClass.name.startsWith(MEDIA3) || depth == MAX_DEPTH || seen.put(value, Unit) != null -> {
          settings[path] = value.javaClass.name.substringBefore("$$")
        }
        else -> {
          var type: Class<*>? = value.javaClass
          while (type != null && type.name.startsWith(MEDIA3)) {
            type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
              field.isAccessible = true
              walk(field.get(value), "$path.${field.name}", depth + 1)
            }
            type = type.superclass
          }
        }
      }
    }

    walk(root, root.javaClass.simpleName, 0)
    return settings
  }

  private companion object {
    const val MEDIA3 = "androidx.media3."
    const val MAX_DEPTH = 4
    const val TRIM_END_MS = 500L
  }
}
