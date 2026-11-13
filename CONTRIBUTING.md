# Contributing

## Building

```bash
./gradlew build            # everything, including the Apple frameworks
./gradlew check            # tests, formatting, ABI validation, lint, and both layering guards
./gradlew spotlessApply    # format to .editorconfig
./gradlew updateKotlinAbi  # regenerate the ABI dumps after a public API change
```

JDK 17 or newer, and macOS for the Apple targets.

## Module layering

Layering is one ladder, `core -> effects -> transform -> backends -> player -> compose -> compose-ui`, and a module
may depend on any rung below its own. `filmstrip` sits over the player. The io adapters and `filmstrip-test` branch
off core instead: `core -> {io-filekit -> io-filekit-compose, io-okio, io-kotlinx, test}`. The build enforces it:
`check` runs a resolved-dependency guard on every module, plus an import guard where one is needed.

Each backend lowers an edit for both export and preview. The player owns playback and drives that preview lowering
through the backend's `@InternalFilmstripApi` declarations under a named set of its `internal` packages, since a
backend may not take a playback runtime such as media3-exoplayer. `internal.preview` holds what only the player reads,
and a few leaf packages hold what the backend's export reads as well. Nothing else in a backend imports
`internal.preview`. The import guard keeps the player out of the rest of a backend's `internal` package.

## Running the tests

A clean checkout runs every suite without any credentials. What each one needs on the machine:

| Suite | Task | Needs |
|---|---|---|
| JVM | `./gradlew jvmTest` | JDK 17 or newer, and ffmpeg and ffprobe 8.0 or newer with libx264 and libx265 |
| ffmpeg backend | `./gradlew :filmstrip-transform-ffmpeg:jvmTest` | The same ffmpeg |
| Android host | `./gradlew testAndroidHostTest` | JDK and the Android SDK |
| Android device | `./gradlew connectedAndroidDeviceTest` | A running emulator or a connected device |
| macOS | `./gradlew macosArm64Test` | A Mac with Xcode |
| iOS simulator | `./gradlew iosSimulatorArm64Test` | A Mac with Xcode and an iOS simulator |
| Browser | `./gradlew jsBrowserTest` and `./gradlew wasmJsBrowserTest` | Chrome |
| Build logic | `./gradlew -p build-logic :convention:test` | JDK |

ffmpeg is found through `FILMSTRIP_FFMPEG`, then the `PATH`, and ffprobe beside it. Before the ffmpeg suite runs a
single test, and before the player's JVM suite does too, `checkFfmpegTestToolchain` looks the toolchain up the way the
backend does and fails the build naming what it found when ffmpeg or ffprobe is missing, below 8.0, or its HDR encoder
cannot write ten-bit HEVC.

## Running the sample

`sample/demo` exercises every backend through the public API, which makes it the quickest way to see a change for real.

| Target | Command | Needs |
|---|---|---|
| Android | `./gradlew :sample:demo:androidApp:installDebug` | A running emulator or a connected device |
| Desktop | `./gradlew :sample:demo:desktopApp:run` | ffmpeg and ffprobe 8.0 or newer on the `PATH` |
| Browser | `./gradlew :sample:demo:webApp:wasmJsBrowserDevelopmentRun`, then <http://localhost:8080> | Chrome |
| iOS | `open sample/demo/iosApp/iosApp.xcodeproj`, pick a simulator and press Run | A Mac with Xcode, and your own team for a physical iPhone |

## Test fixtures

The clips the export and player suites read are not in the repository. Each module that reads them has a
`test-fixtures.txt` manifest, one `name sha256 bytes` row per clip, and a test task depends on its module's
`download<Name>TestMedia` task. That fetches `https://filmstrip-media.jordond.dev/test-fixtures/<sha256>.mp4`
anonymously, checks the digest, and caches the clip under `~/.gradle/filmstrip/test-media`, so one download serves
every module, branch and worktree on the machine. A clip whose bytes do not match its row fails the build.

The pictures the core, effects and io suites read are built in Kotlin by `filmstrip-test`, so they need nothing
downloaded.

### Adding or changing a fixture

1. Edit the `FixtureSpec` list passed to `testMedia` in the module's `build.gradle.kts`.
2. Run `./gradlew :<module>:generate<Name>TestMedia`. It encodes every clip with your ffmpeg and checks each one with
   ffprobe. Every pass runs on one thread with its SIMD code off where that changes the output, so the same spec on the
   same ffmpeg, libx264 and libx265 gives the same bytes at any core count and CPU tier.
3. Run `./gradlew :<module>:publish<Name>TestMedia`. It uploads each clip to the bucket with `wrangler`, then rewrites
   `test-fixtures.txt` with the new digests under a `# ffmpeg <build>` header naming the ffmpeg, libx264 and libx265
   that made them. `--no-upload` writes the manifest and prints the keys without uploading.
4. Commit the manifest.

Publishing needs `wrangler` on the `PATH`, logged in to the Cloudflare account that owns the bucket. The publish task
checks for it before generating anything. Pass `--account <id>` or set `CLOUDFLARE_ACCOUNT_ID` when it can reach more
than one.

Another build of ffmpeg or either encoder encodes the same spec to other bytes. When your build is not the one the
manifest was pinned with and any clip changed, publish refuses and names both builds and the changed clips. Pass
`-PrepinFixtures` to re-pin every changed clip on your build, which means every suite that reads them sees new bytes.

Never fetch a new digest's URL before its upload has finished. The CDN caches a 404 for four hours, so an early
request leaves that clip missing for everyone until it expires.

## Docs site

The site in `website/` is served in versions, each a whole site under its own prefix: `/dev/` is built from `main`,
and each release gets `/<major>.<minor>/`. The newest release is served under `/latest/` instead, and the root and its
own `/<major>.<minor>/` redirect there. The site keeps the newest three minors of the newest major and the newest
minor of each of the two majors before it. An older version's pages redirect to `/latest/`.

To run it locally:

```sh
cd website
bun install
bun run dev                      # the development version at /dev/
bun run assemble --skip-demo     # one built version in dist-version, needs ./gradlew dokkaGenerate first
bun run assemble:site            # every version in dist, then `bun x wrangler dev` serves it like production
```

Write links in the docs from the site root, like `/guides/edits/`. The build moves them under the version.

A release's version is built once, when the `Publish` workflow has put the release on Maven Central, and stored on the
GitHub release as `docs-site.tar.gz`, beside `docs-site-latest.tar.gz`, the same site built for `/latest/`. Every deploy
builds `/dev/` and downloads the stored versions, so an old version only changes when it is rebuilt. To rebuild one, run the `docs` workflow with `release_tag` set, and `source_ref` set
to a branch when the fix is not in the tag. A patch release replaces its minor's version.

## Releasing

Tag the commit and publish a GitHub release. The tag is the version: the publish workflow passes it
as `ORG_GRADLE_PROJECT_VERSION_NAME`, so no file in the repo records a released version.
`VERSION_NAME` in `gradle.properties` only affects local builds.
