package dev.jordond.filmstrip.convention.testmedia

/**
 * The fixtures several modules publish, written once here so every module rolls the same clip.
 */
object SharedFixtures {
  /**
   * Three audio tracks at three tones with the middle one flagged as the default, so a test can hear which track a
   * clip played and tell the default rule apart from taking the first track.
   */
  val ThreeAudio: FixtureSpec =
    FixtureSpec(
      "three_audio",
      320,
      240,
      30,
      2.0,
      48000,
      2,
      bitrateKbps = 500,
      audioTracks =
        listOf(
          AudioTrackSpec(toneHz = 440, language = "eng"),
          AudioTrackSpec(toneHz = 660, language = "fra", isDefault = true),
          AudioTrackSpec(toneHz = 990, language = "deu"),
        ),
    )

  /**
   * [ThreeAudio] written as Matroska, so a test can check what a prober reads off a container with no MP4 track
   * headers, the default flag above all.
   */
  val ThreeAudioMatroska: FixtureSpec =
    ThreeAudio.copy(name = "three_audio_matroska", container = FixtureContainer.Matroska)

  /**
   * [ThreeAudio] written as an MPEG transport stream, so a test can check that a container numbering its streams by
   * packet id names its tracks by their place. A transport stream carries no default flag, so none is flagged.
   */
  val ThreeAudioTs: FixtureSpec =
    ThreeAudio.copy(
      name = "three_audio_ts",
      container = FixtureContainer.MpegTs,
      audioTracks = ThreeAudio.audioTracks.map { it.copy(isDefault = false) },
    )
}
