package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip

/**
 * Builds the sample's [Filmstrip] from the bundle artifact and the opt-in filters, which register themselves.
 *
 * @param recorder Receives what the backends learn while they run, such as the ffmpeg banner and
 *   the command line it was spawned with.
 */
fun createSampleFilmstrip(recorder: DiagnosticsRecorder): Filmstrip =
  Filmstrip {
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
