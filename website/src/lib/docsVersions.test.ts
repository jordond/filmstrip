import { describe, expect, test } from 'bun:test'
import {
  DEV,
  LATEST,
  releaseTags,
  routeFor,
  SERVED_MINORS,
  SERVED_OLDER_MAJORS,
  segmentOf,
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
    expect(siteVersions(['0.9.0', '0.10.0', '0.11.0']).versions.map((v) => v.version)).toEqual([
      DEV,
      '0.11',
      '0.10',
      '0.9',
    ])
  })

  test('the newest major keeps its newest minors, the middle of the run decides which', () => {
    const tags = ['0.1.0', '0.2.0', '0.3.0', '0.4.0', '0.5.0']
    expect(siteVersions(tags).versions.map((v) => v.version)).toEqual([DEV, '0.5', '0.4', '0.3'])
    expect(siteVersions(tags.slice(0, SERVED_MINORS)).versions).toHaveLength(1 + SERVED_MINORS)
  })

  test('each of the majors before the newest keeps its newest minor, and older majors are left out', () => {
    const tags = ['0.3.0', '0.15.2', '1.0.0', '1.4.1', '2.0.0', '4.9.0', '5.0.0', '5.1.0', '5.2.0', '5.3.0']
    const manifest = siteVersions(tags)
    expect(manifest.versions.map((v) => v.version)).toEqual([DEV, '5.3', '5.2', '5.1', '4.9', '2.0'])
    expect(manifest.versions.filter((v) => v.version !== DEV)).toHaveLength(SERVED_MINORS + SERVED_OLDER_MAJORS)
    expect(manifest.latest).toBe('5.3')
  })

  test('a new major starts alone beside the majors before it', () => {
    expect(siteVersions(['0.14.0', '0.15.0', '1.0.0']).versions.map((v) => v.version)).toEqual([DEV, '1.0', '0.15'])
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

  test('segmentOf serves the latest stable version under latest and the others under their own', () => {
    expect(segmentOf(some, '0.2')).toBe(LATEST)
    expect(segmentOf(some, '0.1')).toBe('0.1')
    expect(segmentOf(some, DEV)).toBe(DEV)
    expect(segmentOf(none, DEV)).toBe(DEV)
  })

  test('swapVersion keeps the page', () => {
    expect(swapVersion('/dev/guides/edits/', '0.1')).toBe('/0.1/guides/edits/')
    expect(swapVersion('/dev/', '0.1')).toBe('/0.1/')
    expect(swapVersion('/dev', '0.1')).toBe('/0.1')
  })
})

describe('routeFor', () => {
  test('the root goes to latest', () => {
    expect(route('/', some)).toEqual({ redirect: '/latest/' })
  })

  test('the root goes to dev while nothing is released', () => {
    expect(route('/', none)).toEqual({ redirect: '/dev/' })
  })

  test('a miss inside latest is served as asked', () => {
    expect(route('/latest/nope?a=1', some)).toEqual({ asset: '/latest/nope' })
  })

  test('latest goes to dev while nothing is released, with the page and the query', () => {
    expect(route('/latest/api/?a=1', none)).toEqual({ redirect: '/dev/api/?a=1' })
    expect(route('/latest', none)).toEqual({ redirect: '/dev' })
  })

  test('the number of the latest stable version goes to latest, with the page and the query', () => {
    expect(route('/0.2/guides/edits/?a=1', some)).toEqual({ redirect: '/latest/guides/edits/?a=1' })
    expect(route('/0.2', some)).toEqual({ redirect: '/latest' })
  })

  test('a path from before versions goes to the same page in latest', () => {
    expect(route('/guides/edits/?a=1', some)).toEqual({ redirect: '/latest/guides/edits/?a=1' })
    expect(route('/api/', none)).toEqual({ redirect: '/dev/api/' })
  })

  test('a miss inside an older version or dev is served as asked', () => {
    expect(route('/0.1/nope', some)).toEqual({ asset: '/0.1/nope' })
    expect(route('/dev/nope', some)).toEqual({ asset: '/dev/nope' })
    expect(route('/dev/nope', none)).toEqual({ asset: '/dev/nope' })
  })

  test('a version the site does not serve goes to the same page in latest', () => {
    expect(route('/0.7/guides/?a=1', some)).toEqual({ redirect: '/latest/guides/?a=1' })
    expect(route('/0.7', some)).toEqual({ redirect: '/latest' })
    expect(route('/0.7/guides/', none)).toEqual({ redirect: '/dev/guides/' })
  })

  test('a first segment that only starts like a version is a page', () => {
    expect(route('/0.7.1/guides/', some)).toEqual({ redirect: '/latest/0.7.1/guides/' })
  })

  test('the demo host root serves the full screen demo', () => {
    expect(route('/', some, DEMO)).toEqual({ asset: '/latest/demo/tv/' })
    expect(route('/', none, DEMO)).toEqual({ asset: '/dev/demo/tv/' })
  })

  test('the demo host routes everything else like the site', () => {
    expect(route('/demo/app/', some, DEMO)).toEqual({ redirect: '/latest/demo/app/' })
  })
})
