import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { DEV, siteVersions } from './docsVersions'
import { repoRoot } from './snippets'

/** The oldest iOS filmstrip supports. */
export const MIN_IOS = '16'

/** The oldest macOS filmstrip supports. */
export const MIN_MACOS = '12'

/** The browsers filmstrip is tested on. */
export const TESTED_BROWSERS = 'Chrome'

const catalogPath = 'gradle/libs.versions.toml'
const webcodecsBuildPath = 'filmstrip-transform-webcodecs/build.gradle.kts'
const toolchainPath =
  'filmstrip-transform-ffmpeg/src/commonMain/kotlin/dev/jordond/filmstrip/ffmpeg/internal/process/ToolchainReport.kt'

/**
 * Reads `name = "value"` out of the `[versions]` table of a Gradle version catalog. Throws naming the key when it
 * isn't there.
 */
export function catalogVersion(toml: string, name: string): string {
  const table = toml.split(/^\[versions\]\s*$/m)[1]?.split(/^\[/m)[0]
  if (table === undefined) throw new Error('No [versions] table in the version catalog')
  const match = table.match(new RegExp(`^${name.replace(/[.-]/g, '\\$&')}\\s*=\\s*"([^"]+)"`, 'm'))
  if (!match) throw new Error(`No "${name}" in the [versions] table`)
  return match[1]
}

/**
 * Reads the ffmpeg floor, `MINIMUM_MAJOR.MINIMUM_MINOR`, out of the ffmpeg backend's ToolchainReport.kt.
 */
export function ffmpegFloor(source: string): string {
  const read = (name: string) => {
    const match = source.match(new RegExp(`const val ${name}: Int = (\\d+)`))
    if (!match) throw new Error(`No ${name} in ToolchainReport.kt`)
    return match[1]
  }
  return `${read('MINIMUM_MAJOR')}.${read('MINIMUM_MINOR')}`
}

/**
 * Reads the mediabunny version out of the `npm("mediabunny", "...")` line of the WebCodecs backend's build script.
 */
export function mediabunnyVersion(build: string): string {
  const match = build.match(/npm\("mediabunny",\s*"([^"]+)"\)/)
  if (!match) throw new Error('No npm("mediabunny", ...) in the WebCodecs build script')
  return match[1]
}

/**
 * The library version the docs quote: [override] when a release sets one, the way `ORG_GRADLE_PROJECT_VERSION_NAME`
 * overrides the build, and otherwise the newest release in [tags]. Throws when [tags] holds no release.
 */
export function libraryVersion(tags: readonly string[], override?: string): string {
  if (override) return override
  const newest = siteVersions(tags).versions.find((it) => it.version !== DEV)
  if (!newest) throw new Error('No release tag to read the library version from')
  return newest.title
}

function gitTags(root: string): string[] {
  return execFileSync('git', ['tag', '--list'], { cwd: root, encoding: 'utf8' }).split('\n').filter(Boolean)
}

/**
 * The ffmpeg release inside a bytedeco [artifactVersion], which reads `<ffmpeg>-<javacpp>`.
 */
export function bundledFfmpeg(artifactVersion: string): string {
  return artifactVersion.split('-')[0]
}

/** The versions the docs quote, read from the build at site build time. */
export interface Versions {
  group: string
  library: string
  kotlin: string
  composeMultiplatform: string
  minSdk: string
  jdk: string
  ffmpeg: string
  bytedecoFfmpeg: string
  bundledFfmpeg: string
  mediabunny: string
}

/**
 * Reads [Versions] out of the repository at [root].
 */
export function readVersions(root: string = repoRoot): Versions {
  const toml = readFileSync(join(root, catalogPath), 'utf8')
  const bytedecoFfmpeg = catalogVersion(toml, 'bytedeco-ffmpeg')
  return {
    group: catalogVersion(toml, 'group'),
    library: libraryVersion(gitTags(root), process.env.ORG_GRADLE_PROJECT_VERSION_NAME),
    kotlin: catalogVersion(toml, 'kotlin'),
    composeMultiplatform: catalogVersion(toml, 'compose-multiplatform'),
    minSdk: catalogVersion(toml, 'sdk-min'),
    jdk: catalogVersion(toml, 'jvmTarget'),
    ffmpeg: ffmpegFloor(readFileSync(join(root, toolchainPath), 'utf8')),
    bytedecoFfmpeg,
    bundledFfmpeg: bundledFfmpeg(bytedecoFfmpeg),
    mediabunny: mediabunnyVersion(readFileSync(join(root, webcodecsBuildPath), 'utf8')),
  }
}

/** The repository's versions, read once. */
export const versions: Versions = readVersions()
