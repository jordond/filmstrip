import { describe, expect, test } from 'bun:test'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { repoRoot } from './snippets'
import { catalogVersion, ffmpegFloor, readVersions } from './versions'

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

  test('reads the group and the library version', () => {
    const properties = readFileSync(join(repoRoot, 'gradle.properties'), 'utf8')
    const line = properties.split('\n').find((it) => it.startsWith('VERSION_NAME=')) ?? ''
    expect(versions.group).toBe(catalog.versions.group)
    expect(versions.library).toBe(line.slice('VERSION_NAME='.length).trim())
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
