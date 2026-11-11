import { describe, expect, test } from 'bun:test'
import {
  DEV,
  releaseTags,
  routeFor,
  siteVersions,
  swapVersion,
  versionLabel,
  versionOfPath,
  type VersionManifest,
} from './docsVersions.ts'

const DEMO = 'demo.example.dev'
const PAGE = 'demo/tv/'
const none: VersionManifest = siteVersions([])
const some: VersionManifest = siteVersions(['0.1.0', '0.2.0'])
const route = (path: string, manifest: VersionManifest, host = 'example.dev') =>
  routeFor(new URL(path, `https://${host}`), manifest, DEMO, PAGE)

describe('versionLabel', () => {
  test('a release is served under its minor', () => {
    expect(versionLabel('0.1.3')).toBe('0.1')
    expect(versionLabel('12.34.5')).toBe('12.34')
  })

  test('a prerelease or anything else has no version', () => {
    expect(versionLabel('0.2.0-rc1')).toBeNull()
    expect(versionLabel('v0.1.0')).toBeNull()
    expect(versionLabel('archive/x')).toBeNull()
  })
})

describe('siteVersions', () => {
  test('with no release there is only dev', () => {
    expect(none).toEqual({ latest: null, versions: [{ version: DEV, title: DEV }] })
  })

  test('each minor keeps its highest patch, the middle one too', () => {
    const manifest = siteVersions(['0.1.0', '0.2.1', '0.2.10', '0.2.9', '0.3.0', '0.3.0-rc1'])
    expect(manifest.versions).toEqual([
      { version: DEV, title: DEV },
      { version: '0.3', title: '0.3.0' },
      { version: '0.2', title: '0.2.10' },
      { version: '0.1', title: '0.1.0' },
    ])
    expect(manifest.latest).toBe('0.3')
  })

  test('minors order as numbers', () => {
    expect(siteVersions(['0.9.0', '0.10.0', '1.0.0']).versions.map((v) => v.version)).toEqual([
      DEV,
      '1.0',
      '0.10',
      '0.9',
    ])
  })

  test('releaseTags names the tag behind each stable version', () => {
    expect(releaseTags(siteVersions(['0.1.0', '0.1.2', '0.2.0']))).toEqual({ '0.2': '0.2.0', '0.1': '0.1.2' })
  })
})

describe('paths', () => {
  test('versionOfPath reads the first segment', () => {
    expect(versionOfPath('/0.1/guides/edits/')).toBe('0.1')
    expect(versionOfPath('/dev')).toBe(DEV)
    expect(versionOfPath('/')).toBeNull()
  })

  test('swapVersion keeps the page', () => {
    expect(swapVersion('/dev/guides/edits/', '0.1')).toBe('/0.1/guides/edits/')
    expect(swapVersion('/dev/', '0.1')).toBe('/0.1/')
    expect(swapVersion('/dev', '0.1')).toBe('/0.1')
  })
})

describe('routeFor', () => {
  test('the root goes to the latest stable version', () => {
    expect(route('/', some)).toEqual({ redirect: '/0.2/' })
  })

  test('the root goes to dev while nothing is released', () => {
    expect(route('/', none)).toEqual({ redirect: '/dev/' })
  })

  test('latest keeps the page and the query', () => {
    expect(route('/latest/guides/edits/?a=1', some)).toEqual({ redirect: '/0.2/guides/edits/?a=1' })
    expect(route('/latest', some)).toEqual({ redirect: '/0.2' })
    expect(route('/latest/api/', none)).toEqual({ redirect: '/dev/api/' })
  })

  test('a path from before versions goes to the same page in the latest stable version', () => {
    expect(route('/guides/edits/?a=1', some)).toEqual({ redirect: '/0.2/guides/edits/?a=1' })
    expect(route('/api/', none)).toEqual({ redirect: '/dev/api/' })
  })

  test('a miss inside a version is served as asked', () => {
    expect(route('/0.1/nope', some)).toEqual({ asset: '/0.1/nope' })
    expect(route('/dev/nope', none)).toEqual({ asset: '/dev/nope' })
  })

  test('a version that does not exist is treated as a page', () => {
    expect(route('/0.7/guides/', some)).toEqual({ redirect: '/0.2/0.7/guides/' })
  })

  test('the demo host root serves the full screen demo', () => {
    expect(route('/', some, DEMO)).toEqual({ asset: '/0.2/demo/tv/' })
    expect(route('/', none, DEMO)).toEqual({ asset: '/dev/demo/tv/' })
  })

  test('the demo host routes everything else like the site', () => {
    expect(route('/demo/app/', some, DEMO)).toEqual({ redirect: '/0.2/demo/app/' })
  })
})
