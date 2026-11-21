package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.sampleTableOverrunOf
import dev.jordond.filmstrip.internal.undecodableSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The refusal for a source whose picture data is damaged, and the garbled case every backend is checked against.
 */
@OptIn(InternalFilmstripApi::class)
class GarbledMdatTest {
  @Test
  fun theRefusalNamesTheSource() {
    undecodableSource("clip.mp4").source shouldBe "clip.mp4"
  }

  @Test
  fun aGarbledMdatKeepsItsLengthAndPassesTheSampleTableCheck() =
    runTest {
      val whole = PrimedAudio.mp4()
      val garbled = checkNotNull(UnreadableCase.GarbledMdat.bytesFrom(whole))

      garbled.size shouldBe whole.size
      garbled.contentEquals(whole) shouldBe false
      UnreadableCase.GarbledMdat.bytesFrom(whole).contentEquals(garbled) shouldBe true
      sampleTableOverrunOf(garbled.size.toLong()) { start, count ->
        garbled.copyOfRange(start.toInt(), minOf(garbled.size, start.toInt() + count))
      }.shouldBeNull()
    }

  @Test
  fun aGarbledMdatIsRefusedAsUndecodable() =
    runTest {
      val source = MediaSource.of("garbled.mp4")
      val garbled = UnreadableCase.GarbledMdat.bytesFrom(PrimedAudio.mp4())

      UnreadableCase.GarbledMdat.refusalAt(source, garbled) shouldBe undecodableSource(source.describe()).message
    }
}
