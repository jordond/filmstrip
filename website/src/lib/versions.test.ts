import { describe, expect, test } from 'bun:test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { repoRoot } from './snippets'
import { bundledFfmpeg, catalogVersion, ffmpegFloor, libraryVersion, mediabunnyVersion, readVersions } from './versions'

const toml = readFileSync(join(repoRoot, 'gradle/libs.versions.toml'), 'utf8')
const catalog = Bun.TOML.parse(toml) as { versions: Record<string, string> }
const toolchain = readFileSync(
  join(
    repoRoot,
    'filmstrip-transform-ffmpeg/src/commonMain/kotlin/dev/jordond/filmstrip/ffmpeg/internal/process/ToolchainReport.kt',
  ),
  'utf8',
)

function constant(name: string): string {
  const line = toolchain.split('\n').find((it) => it.includes(`const val ${name}`))
  if (line === undefined) throw new Error(`${name} missing`)
  return line.split('=')[1].trim()
}

describe('readVersions', () => {
  const versions = readVersions()

  test('reads Kotlin, Compose, minSdk and the JDK out of the version catalog', () => {
    expect(versions.kotlin).toBe(catalog.versions.kotlin)
    expect(versions.composeMultiplatform).toBe(catalog.versions['compose-multiplatform'])
    expect(versions.minSdk).toBe(catalog.versions['sdk-min'])
    expect(versions.jdk).toBe(catalog.versions.jvmTarget)
  })

  test('reads the group and a released library version', () => {
    expect(versions.group).toBe(catalog.versions.group)
    expect(versions.library).toMatch(/^\d+\.\d+\.\d+$/)
  })

  test('reads the ffmpeg floor out of ToolchainReport.kt', () => {
    expect(versions.ffmpeg).toBe(`${constant('MINIMUM_MAJOR')}.${constant('MINIMUM_MINOR')}`)
  })
})

describe('catalogVersion', () => {
  test('reads only the versions table', () => {
    const text = '[versions]\nkotlin = "2.0"\n\n[libraries]\nkotlin = "other"\n'
    expect(catalogVersion(text, 'kotlin')).toBe('2.0')
  })

  test('throws naming a missing key', () => {
    expect(() => catalogVersion('[versions]\n', 'kotlin')).toThrow(/"kotlin"/)
  })
})

describe('ffmpegFloor', () => {
  test('throws naming a missing constant', () => {
    expect(() => ffmpegFloor('const val MINIMUM_MAJOR: Int = 8')).toThrow(/MINIMUM_MINOR/)
  })
})

describe('mediabunnyVersion', () => {
  test('reads the version out of the npm dependency', () => {
    expect(mediabunnyVersion('      implementation(npm("mediabunny", "1.2.3"))\n')).toBe('1.2.3')
  })

  test('throws when the build script has no mediabunny dependency', () => {
    expect(() => mediabunnyVersion('implementation(npm("other", "1.0.0"))')).toThrow(/mediabunny/)
  })
})

describe('bundledFfmpeg', () => {
  test('reads the ffmpeg release out of a bytedeco version', () => {
    expect(bundledFfmpeg('8.1.2-1.5.14')).toBe('8.1.2')
  })
})

describe('libraryVersion', () => {
  test('reads the newest release out of the tags', () => {
    expect(libraryVersion(['0.2.2', '0.10.1', '0.3.0', '0.10.0', '1.0.0-rc1', 'backup/old'])).toBe('0.10.1')
  })

  test('a release version replaces it', () => {
    expect(libraryVersion(['0.2.2'], '0.1.3')).toBe('0.1.3')
    expect(libraryVersion(['0.2.2'], '')).toBe('0.2.2')
  })

  test('throws when no tag is a release', () => {
    expect(() => libraryVersion(['backup/old', '1.0.0-rc1'])).toThrow(/release tag/)
  })
})
