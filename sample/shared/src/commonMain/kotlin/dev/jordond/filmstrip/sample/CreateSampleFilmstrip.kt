package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.create

/**
 * Builds the sample's [Filmstrip] through the bundle artifact, which registers every backend.
 *
 * @param recorder Receives what the backends learn while they run, such as the ffmpeg banner and
 *   the command line it was spawned with.
 */
fun createSampleFilmstrip(recorder: DiagnosticsRecorder): Filmstrip =
  Filmstrip.create {
    addDiagnosticListener(recorder.asListener())
  }

/**
 * Builds the session, with the recorder wired to both halves of it.
 *
 * The recorder has to exist before the [Filmstrip] does, because a listener is registered at build
 * time and the toolchain banner is emitted the first time anything asks the backend a question.
 */
fun createSampleAppState(): SampleAppState {
  val recorder = DiagnosticsRecorder()
  return SampleAppState(createSampleFilmstrip(recorder), recorder)
}
