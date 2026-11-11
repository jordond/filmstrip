# filmstrip

[![Maven Central](https://img.shields.io/maven-central/v/dev.jordond.filmstrip/filmstrip?color=blue)](https://central.sonatype.com/artifact/dev.jordond.filmstrip/filmstrip)
[![CI](https://github.com/jordond/filmstrip/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/jordond/filmstrip/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-filmstrip.jordond.dev-0A7EA4)](https://filmstrip.jordond.dev)
[![Kotlin](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fjordond%2Ffilmstrip%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.kotlin&label=Kotlin&color=7F52FF&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fjordond%2Ffilmstrip%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.compose-multiplatform&label=Compose%20Multiplatform&color=4285F4&logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/compose-multiplatform/)
[![Media3](https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fjordond%2Ffilmstrip%2Fmain%2Fgradle%2Flibs.versions.toml&query=%24.versions.media3&label=Media3&color=3DDC84&logo=android&logoColor=white)](https://developer.android.com/media/media3)
[![Licence](https://img.shields.io/github/license/jordond/filmstrip)](LICENSE)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/jordond/filmstrip/badge)](https://scorecard.dev/viewer/?uri=github.com/jordond/filmstrip)

![Android](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)
![iOS](https://img.shields.io/badge/iOS-000000?logo=apple&logoColor=white)
![macOS](https://img.shields.io/badge/macOS-000000?logo=apple&logoColor=white)
![JVM](https://img.shields.io/badge/JVM-ED8B00?logo=openjdk&logoColor=white)
![Browser](https://img.shields.io/badge/Browser-JS%20%2B%20Wasm-F7DF1E)

A Kotlin Multiplatform library for encoding, editing and previewing video. It targets Android, iOS,
macOS, the JVM and the browser, and uses whatever encoder the platform already has: Media3
Transformer on Android, AVFoundation and VideoToolbox on Apple, WebCodecs in the browser. The
published artifacts carry no native binaries. The JVM backend is the exception, and it shells out to
an ffmpeg you installed yourself, 8.0 or later.

> filmstrip is pre-1.0.0, so the API might break between releases until then.

## Install

```kotlin
kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation("dev.jordond.filmstrip:filmstrip:<version>")
    }
  }
}
```

`filmstrip` brings core, the effects, the player and the right backend for each target.
[Installation](https://filmstrip.jordond.dev/start/installation/) lists the other artifacts and what each platform
needs.

## Usage

```kotlin
val filmstrip = Filmstrip { allBackends() }

val composition = compositionOf {
  clip(MediaSource.of(path))
}

val plan = when (val verdict = filmstrip.plan(composition, ExportSpec.Upload)) {
  is Verdict.Capable -> verdict.plan
  is Verdict.Degraded -> verdict.plan
  is Verdict.Incapable -> error("Can't export on this device: ${verdict.reasons}")
}

filmstrip.export(plan, MediaSink.temporary()).collect { status ->
  if (status is ExportStatus.Success) println("Wrote ${status.output}")
}
```

## Docs

- [Quick start](https://filmstrip.jordond.dev/start/quick-start/) and the guides
- [API reference](https://filmstrip.jordond.dev/api/)
- [Demo](https://filmstrip.jordond.dev/demo/), the sample editor running in the browser

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) covers building, the test suites, the sample app and releasing.

## Licence

MIT, see [LICENSE](LICENSE).
