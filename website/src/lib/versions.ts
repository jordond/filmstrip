import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { repoRoot } from './snippets'

/** The oldest iOS filmstrip supports. */
export const MIN_IOS = '15'

/** The oldest macOS filmstrip supports. */
export const MIN_MACOS = '12'

/** The browsers filmstrip is tested on. */
export const TESTED_BROWSERS = 'Chromium-based browsers (Chrome, Edge)'

const catalogPath = 'gradle/libs.versions.toml'
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
 * Reads `VERSION_NAME` out of gradle.properties.
 */
export function libraryVersion(properties: string): string {
  const match = properties.match(/^VERSION_NAME=(.+)$/m)
  if (!match) throw new Error('No VERSION_NAME in gradle.properties')
  return match[1].trim()
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
}

/**
 * Reads [Versions] out of the repository at [root].
 */
export function readVersions(root: string = repoRoot): Versions {
  const toml = readFileSync(join(root, catalogPath), 'utf8')
  return {
    group: catalogVersion(toml, 'group'),
    library: libraryVersion(readFileSync(join(root, 'gradle.properties'), 'utf8')),
    kotlin: catalogVersion(toml, 'kotlin'),
    composeMultiplatform: catalogVersion(toml, 'compose-multiplatform'),
    minSdk: catalogVersion(toml, 'sdk-min'),
    jdk: catalogVersion(toml, 'jvmTarget'),
    ffmpeg: ffmpegFloor(readFileSync(join(root, toolchainPath), 'utf8')),
  }
}

/** The repository's versions, read once. */
export const versions: Versions = readVersions()
