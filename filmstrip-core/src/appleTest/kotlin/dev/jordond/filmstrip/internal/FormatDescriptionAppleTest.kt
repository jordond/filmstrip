package dev.jordond.filmstrip.internal

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import kotlin.test.Test

/**
 * The format description read off a track that carries none, which crosses the toll-free bridge as no object at all.
 *
 * A track that carries one needs real media, so that half is read off a fixture clip by the AVFoundation backend's
 * tests.
 */
@OptIn(ExperimentalForeignApi::class)
class FormatDescriptionAppleTest {
  @Test
  fun anEmptyTrackHandsBackNoFormatDescription() {
    val composition = AVMutableComposition()

    for (mediaType in listOf(AVMediaTypeVideo, AVMediaTypeAudio)) {
      val track = composition.addMutableTrackWithMediaType(mediaType, kCMPersistentTrackID_Invalid).shouldNotBeNull()

      withClue("$mediaType") { track.copyFormatDescription().shouldBeNull() }
    }
  }
}
