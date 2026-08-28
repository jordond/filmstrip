# filmstrip

[![Maven Central](https://img.shields.io/maven-central/v/dev.jordond.filmstrip/filmstrip-core?label=Maven%20Central)](https://central.sonatype.com/namespace/dev.jordond.filmstrip)
[![Kotlin](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fjordond%2Ffilmstrip%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.kotlin&label=Kotlin&color=7F52FF)](https://kotlinlang.org)
[![Media3](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fjordond%2Ffilmstrip%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.media3&label=Media3&color=3DDC84)](https://developer.android.com/media/media3)

A Kotlin Multiplatform video encoding, editing and preview library for Android and iOS. It uses the
platform's own hardware encoders, Media3 Transformer on Android and AVFoundation plus VideoToolbox
on iOS, and ships no native binaries and no FFmpeg.

> **Status: pre-alpha.** Things are subject to break or change.

## Modules

| Artifact | What's in it |
|---|---|
| `filmstrip` | The bundle: core, effects, player, and the right export engine for each target, plus `Filmstrip.create()`. Deliberately not Compose. |
| `filmstrip-core` | Model, `MediaSource`/`MediaSink`, `probe()`, `thumbnail()`, and the effect SPI. Pulls in no runtime and no effect catalogue. |
| `filmstrip-effects` | The built-in effect catalogue. GL effects on Android, Core Image on Apple. |
| `filmstrip-transform` | The export planner: shared policy every engine builds on. Add an engine artifact alongside it to actually export. |
| `filmstrip-transform-media3` | The media3-transformer export engine, Android. |
| `filmstrip-transform-avfoundation` | The AVFoundation export engine, iOS and macOS. |
| `filmstrip-transform-webcodecs` | The WebCodecs export engine, browser. |
| `filmstrip-transform-ffmpeg` | The ffmpeg export engine, JVM. Shells out to an `ffmpeg` and `ffprobe` already on the machine. |
| `filmstrip-player` | Preview playback. media3-exoplayer on Android, AVKit on Apple. |
| `filmstrip-compose` | Compose Multiplatform bindings. The only artifact that sees Compose. |
| `filmstrip-test` | Test fixtures and the frame-similarity harness. |

Layering is `core -> effects -> {transform -> engines, player} -> {compose, filmstrip}`, and the
build enforces it rather than review: `check` runs an import guard and a resolved-dependency guard
per module.

## Building

```bash
./gradlew build          # everything, including the sample APK and the Apple frameworks
./gradlew check          # tests, formatting, ABI validation, lint, and both layering guards
./gradlew spotlessApply  # format to .editorconfig
./gradlew apiDump        # regenerate the klib ABI dumps after a public API change
```

JDK 17 or newer, and macOS for the Apple targets.

## Releasing

Tag the commit and publish a GitHub release. The tag is the version: the publish workflow passes it
as `ORG_GRADLE_PROJECT_VERSION_NAME`, so no file in the repo records a released version and none can
drift from it. `VERSION_NAME` in `gradle.properties` is only what local builds get.

## Licence

MIT.
