@file:OptIn(InternalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Every track and clip of the edit as the report writes them.
 */
class CompositionReportTest {
  private val state =
    SampleAppState(
      filmstrip = Filmstrip { autoRegister(false) },
      scope = CoroutineScope(Dispatchers.Unconfined),
    )

  @Test
  fun `a two clip edit under a music track reports every clip field`() {
    edit()
    val built = state.composition().shouldNotBeNull()

    val tracks = reported().array("tracks")

    tracks shouldHaveSize 2
    val main = tracks[0].jsonObject
    main.text("content") shouldBe TrackContent.AudioAndVideo.name
    main.long("startMs") shouldBe 0
    main.flag("looping") shouldBe false
    main.array("clips") shouldHaveSize 2

    val first = main.array("clips")[0].jsonObject
    first.text("source") shouldBe FIRST.redactedName()
    first.getValue("trim").jsonObject.long("startMs") shouldBe 1000
    first.getValue("trim").jsonObject.long("endMs") shouldBe 5000
    first.getValue("speed").jsonPrimitive.double shouldBe 2.0
    first.long("fadeInMs") shouldBe 500
    first.long("fadeOutMs") shouldBe 1250
    first.getValue("level").jsonObject.text("kind") shouldBe "Volume"
    first
      .getValue("level")
      .jsonObject
      .getValue("gain")
      .jsonPrimitive.float shouldBe 0.5f
    first.long("durationMs") shouldBe built.tracks[0].clips[0].millis()
    first.array("effects").map { it.jsonPrimitive.content } shouldContainExactly
      built.tracks[0]
        .clips[0]
        .effects
        .map { it.id.value }
    first.array("effects") shouldHaveSize 1

    val second = main.array("clips")[1].jsonObject
    second.text("source") shouldBe SECOND.redactedName()
    second.getValue("trim") shouldBe JsonNull
    second.getValue("speed").jsonPrimitive.double shouldBe 1.0
    second.getValue("level").jsonObject.text("kind") shouldBe "Inherit"
    second.getValue("durationMs") shouldBe JsonNull
  }

  @Test
  fun `a music track reports where it starts, its loop, its fades and its level`() {
    edit()

    val music = reported().array("tracks")[1].jsonObject

    music.text("content") shouldBe TrackContent.Audio.name
    music.long("startMs") shouldBe 1500
    music.flag("looping") shouldBe true
    music.long("fadeInMs") shouldBe 500
    music.long("fadeOutMs") shouldBe 750
    music
      .getValue("level")
      .jsonObject
      .getValue("gain")
      .jsonPrimitive.float shouldBe 0.25f
    music.getValue("durationMs") shouldBe JsonNull

    val clip = music.array("clips").single().jsonObject
    clip.text("source") shouldBe MUSIC.redactedName()
    clip.getValue("trim") shouldBe JsonNull
    clip.getValue("durationMs") shouldBe JsonNull
  }

  @Test
  fun `the report a human reads lists each clip under its track`() {
    edit()

    val markdown = state.diagnosticsReport().markdown

    markdown shouldContain "Track 1 (AudioAndVideo)"
    markdown shouldContain "${FIRST.redactedName()}, 0:01.0 to 0:05.0, 2x, fade in 500ms, fade out 1.25s, volume 0.5"
    markdown shouldContain "Track 2 (Audio), from 1.5s, looping, fade in 500ms, fade out 750ms, volume 0.25"
    markdown shouldNotContain "first.mp4"
    markdown shouldNotContain "music.m4a"
  }

  @Test
  fun `the sources list a track's clips after the edit's`() {
    edit()

    val sources =
      Json
        .parseToJsonElement(state.diagnosticsReport().json)
        .jsonObject
        .array("sources")
        .map { it.jsonPrimitive.content }

    sources shouldHaveSize 3
    sources.last() shouldContain MUSIC.redactedName()
  }

  @Test
  fun `the parity list names an effect on one clip and names an effect used twice once`() {
    edit()
    state.sequence.clips[1]
      .grade.saturation = 0.6f
    val built = state.composition().shouldNotBeNull()

    val ids = state.parity().map { (id, _) -> id }

    ids shouldContainExactly built.allEffects().map { it.id }.distinct()
    ids shouldHaveSize 1
  }

  @Test
  fun `the clips run end to end for the same time whatever a track beside them does`() {
    edit()
    val alone = state.sequence.clipsDuration
    val track = state.sequence.tracks.single()

    track.looping = false
    track.startSeconds = 9.5f
    track.clips.single().probed(ProbeResult.Success(MediaInfo(8.seconds, null, emptyList(), true)))

    state.sequence.clipsDuration shouldBe alone
    state.sequence.duration.shouldNotBeNull() shouldBeGreaterThan alone
  }

  @Test
  fun `an edit with nothing to play reports no composition`() {
    Json.parseToJsonElement(state.diagnosticsReport().json).jsonObject["composition"] shouldBe JsonNull
  }

  private fun edit() {
    state.sequence.addProbed(FIRST, 10.seconds).apply {
      trimEnabled = true
      trimStartSeconds = 1f
      trimEndSeconds = 5f
      speed = 2.0
      fadeInSeconds = 0.5f
      fadeOutSeconds = 1.25f
      audio = AudioLevel.Volume(0.5f)
      grade.saturation = 1.4f
    }
    state.sequence.addProbed(SECOND, 6.seconds)
    val track =
      state.sequence.addTrack("music").apply {
        startSeconds = 1.5f
        looping = true
        gain = 0.25f
        fadeInSeconds = 0.5f
        fadeOutSeconds = 0.75f
      }
    state.sequence.addTo(track, ClipContent.Video(MUSIC), "music")
  }

  private fun reported(): JsonObject =
    Json
      .parseToJsonElement(state.diagnosticsReport().json)
      .jsonObject
      .getValue("composition")
      .jsonObject

  private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

  private fun JsonObject.text(key: String): String = (getValue(key) as JsonPrimitive).content

  private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

  private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

  private fun Clip.millis(): Long = duration.shouldNotBeNull().inWholeMilliseconds

  private companion object {
    val FIRST = MediaSource.of("/Users/someone/Movies/first.mp4")
    val SECOND = MediaSource.of("/Users/someone/Movies/second.mov")
    val MUSIC = MediaSource.of("/Users/someone/Music/music.m4a")
  }
}
