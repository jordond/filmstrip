package dev.jordond.filmstrip.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.VideoPlayer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class FilmstripPlayerConfigTest {
  @Test
  fun `a changed config reaches the same player and leaves the playhead alone`() =
    runTest {
      val engine = RecordingEngine()
      val filmstrip = filmstripWith(engine)
      val composition = testComposition("first.mp4")
      val toneMapped = ExportSpec(hdr = HdrMode.ToneMapToSdr)
      var config by mutableStateOf(PlayerConfig())
      val players = mutableListOf<VideoPlayer>()

      val runtime = ComposeRuntime(this)
      runtime.setContent {
        players += rememberFilmstripPlayer(filmstrip, composition, config)
      }
      val loads = engine.loads.size

      config = PlayerConfig(PreviewQualityPolicy.CapHeight(360), toneMapped)
      runtime.settle()

      players.last() shouldBeSameInstanceAs players.first()
      engine.exportSpecs.last() shouldBe toneMapped
      engine.policies.last() shouldBe PreviewQualityPolicy.CapHeight(360)
      engine.loads.size shouldBe loads
      engine.seeks.shouldBeEmpty()

      runtime.dispose()
    }
}
