# filmstrip

[![Kotlin](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fjordond%2Ffilmstrip%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.kotlin&label=Kotlin&color=7F52FF)](https://kotlinlang.org)
[![Media3](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fjordond%2Ffilmstrip%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.media3&label=Media3&color=3DDC84)](https://developer.android.com/media/media3)

A Kotlin Multiplatform library for encoding, editing and previewing video. It targets Android, iOS,
macOS, the JVM and the browser, and uses whatever encoder the platform already has: Media3
Transformer on Android, AVFoundation and VideoToolbox on Apple, WebCodecs in the browser. The
published artifacts carry no native binaries. The JVM backend is the exception, and it shells out to
an ffmpeg you installed yourself, 8.0 or later.

> **Status: pre-alpha.** Things are subject to break or change.

Read the docs at [filmstrip.jordond.dev](https://filmstrip.jordond.dev).

## Modules

| Artifact | What's in it |
|---|---|
| `filmstrip` | The bundle: core, effects, player, and the right backend for each target, plus `allBackends()` to register them. Deliberately not Compose. |
| `filmstrip-core` | Model, `MediaSource`/`MediaSink`, `probe()`, `frame()`, `frames()`, the `VideoPlayer` API and `PlayerEngine` SPI, and the effect SPI. No runtime, no effect catalogue. |
| `filmstrip-effects` | The built-in effect catalogue. GL effects on Android, Core Image on Apple, ffmpeg filter graphs on the JVM, and canvas drawing in the browser. |
| `filmstrip-effects-filters` | Opt-in looks on all four backends, starting with VHS. Add it and call `filters()` on the builder. |
| `filmstrip-transform` | The export planner: shared policy every backend builds on. Add a backend artifact alongside it to actually export. |
| `filmstrip-transform-media3` | The Media3 backend, Android. Lowers an edit for media3-transformer export and for the player's preview. |
| `filmstrip-transform-avfoundation` | The AVFoundation backend, iOS and macOS. Lowers an edit for export and for the player's preview. |
| `filmstrip-transform-webcodecs` | The WebCodecs backend, browser. Lowers an edit for export and for the player's preview. |
| `filmstrip-transform-ffmpeg` | The ffmpeg backend, JVM. Lowers an edit for export and for the player's preview, and shells out to an `ffmpeg` and `ffprobe` 8.0 or later already on the machine. |
| `filmstrip-player` | Preview playback over each target's backend. media3-exoplayer on Android, AVPlayer on Apple, an ffmpeg frame stream on the JVM, and WebCodecs with a canvas in the browser. |
| `filmstrip-compose` | Compose Multiplatform bindings: player state, the frame strip's cache, and bare surfaces. |
| `filmstrip-compose-ui` | The drawn timeline: scrolling strip, ruler, playhead, trim handles, scrub gestures. Foundation only, no Material. |
| `filmstrip-test` | Test fixtures and the frame-similarity harness. |
| `filmstrip-io-filekit` | FileKit bindings: a picked `PlatformFile` as a source or sink, and an export result back as one. |
| `filmstrip-io-filekit-compose` | FileKit dialog launchers that hand back a `MediaSource`, `ImageSource` or `MediaSink`: pick, save, share. |
| `filmstrip-io-okio` | Okio bindings: `Path`, `BufferedSource` and `ByteString` as sources, `Path` as a sink. |
| `filmstrip-io-kotlinx` | kotlinx-io bindings: `Path` and `Source` as sources, `Path` as a sink. |

Layering is one ladder, `core -> effects -> transform -> backends -> player -> compose -> compose-ui`, and a module
may depend on any rung below its own. `filmstrip` sits over the player. The io adapters and `filmstrip-test` branch
off core instead: `core -> {io-filekit -> io-filekit-compose, io-okio, io-kotlinx, test}`. The build enforces it:
`check` runs a resolved-dependency guard on every module, plus an import guard where one is needed.

Each backend lowers an edit for both export and preview. The player owns playback and drives that preview lowering
through the backend's `@InternalFilmstripApi` declarations under a named set of its `internal` packages, since a
backend may not take a playback runtime such as media3-exoplayer. `internal.preview` holds what only the player reads,
and a few leaf packages hold what the backend's export reads as well. Nothing else in a backend imports
`internal.preview`. The import guard keeps the player out of the rest of a backend's `internal` package.

## Building

```bash
./gradlew build            # everything, including the Apple frameworks
./gradlew check            # tests, formatting, ABI validation, lint, and both layering guards
./gradlew spotlessApply    # format to .editorconfig
./gradlew updateKotlinAbi  # regenerate the ABI dumps after a public API change
```

JDK 17 or newer, and macOS for the Apple targets. [CONTRIBUTING.md](CONTRIBUTING.md) lists what each test suite
needs and how the test fixtures are made and published.

## Releasing

Tag the commit and publish a GitHub release. The tag is the version: the publish workflow passes it
as `ORG_GRADLE_PROJECT_VERSION_NAME`, so no file in the repo records a released version.
`VERSION_NAME` in `gradle.properties` only affects local builds.

## Licence

MIT, see [LICENSE](LICENSE).
