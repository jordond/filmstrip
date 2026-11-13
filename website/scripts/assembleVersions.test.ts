import { afterEach, beforeEach, describe, expect, test } from 'bun:test'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DEV, LATEST, siteVersions } from '../src/lib/docsVersions.ts'
import { assembleSite, LATEST_ARCHIVE, storedName } from './assembleVersions.ts'

let work: string
const at = (...parts: string[]) => join(work, ...parts)

function site(dir: string, marker: string, extra: string[] = []): string {
  mkdirSync(join(dir, 'guides'), { recursive: true })
  writeFileSync(join(dir, 'index.html'), marker)
  writeFileSync(join(dir, 'guides', 'index.html'), marker)
  writeFileSync(join(dir, '_headers'), `headers of ${marker}`)
  for (const name of extra) writeFileSync(join(dir, name), marker)
  return dir
}

function pack(name: string, marker: string, extra: string[]): void {
  const built = site(at('built', name), marker, extra)
  mkdirSync(at('stored'), { recursive: true })
  const { exitCode } = Bun.spawnSync(['tar', '-czf', at('stored', name), '-C', built, '.'])
  expect(exitCode).toBe(0)
}

/** Stores the release [tag] the way the workflow does, its own site and the one built for latest. */
function store(tag: string, extra: string[] = []): void {
  pack(`${tag}.tar.gz`, tag, extra)
  pack(`${tag}${LATEST_ARCHIVE}`, `${tag} as latest`, extra)
}

beforeEach(() => {
  work = mkdtempSync(join(tmpdir(), 'filmstrip-versions-'))
  site(at('dev'), DEV)
})

afterEach(() => rmSync(work, { recursive: true, force: true }))

describe('assembleSite', () => {
  test('with nothing stored the site is the development version alone', () => {
    const { manifest, files } = assembleSite(at('dev'), at('stored'), at('out'))
    expect(manifest).toEqual(siteVersions([]))
    expect(files).toEqual({ [DEV]: 2 })
    expect(readFileSync(at('out', DEV, 'index.html'), 'utf8')).toBe(DEV)
  })

  test('each minor is served from its highest patch', () => {
    for (const tag of ['0.1.0', '0.2.0', '0.2.3', '0.2.1', '0.3.0']) store(tag)
    const { manifest } = assembleSite(at('dev'), at('stored'), at('out'))
    expect(manifest).toEqual(siteVersions(['0.1.0', '0.2.3', '0.3.0']))
    expect(manifest.versions.map((v) => v.title)).toEqual([DEV, '0.3.0', '0.2.3', '0.1.0'])
    expect(readFileSync(at('out', '0.2', 'guides', 'index.html'), 'utf8')).toBe('0.2.3')
    expect(readFileSync(at('out', LATEST, 'index.html'), 'utf8')).toBe('0.3.0 as latest')
    expect(readFileSync(at('out', '0.1', 'index.html'), 'utf8')).toBe('0.1.0')
    expect(JSON.parse(readFileSync(at('out', 'versions.json'), 'utf8'))).toEqual(manifest)
  })

  test('the latest stable version is served under latest alone, from the site built for it', () => {
    for (const tag of ['0.1.0', '0.2.0', '0.2.1']) store(tag)
    const { files } = assembleSite(at('dev'), at('stored'), at('out'))
    expect(readFileSync(at('out', LATEST, 'guides', 'index.html'), 'utf8')).toBe('0.2.1 as latest')
    expect(readFileSync(at('out', '0.1', 'index.html'), 'utf8')).toBe('0.1.0')
    expect(existsSync(at('out', '0.2'))).toBe(false)
    expect(files).toEqual({ [DEV]: 2, [LATEST]: 2, '0.1': 2 })
  })

  test('a latest release without a site built for latest fails', () => {
    store('0.1.0')
    store('0.2.0')
    rmSync(at('stored', `0.2.0${LATEST_ARCHIVE}`))
    expect(() => assembleSite(at('dev'), at('stored'), at('out'))).toThrow('Release 0.2.0 has no 0.2.0.latest.tar.gz')
  })

  test('the archives the workflow downloads are enough, one for each served release', () => {
    for (const tag of ['0.1.0', '0.2.0', '0.3.0', '0.4.0']) store(tag)
    const manifest = siteVersions(['0.1.0', '0.2.0', '0.3.0', '0.4.0'])
    const needed = ['0.4', '0.3', '0.2'].map((version) => storedName(manifest, version))
    expect(needed).toEqual(['0.4.0.latest.tar.gz', '0.3.0.tar.gz', '0.2.0.tar.gz'])
    for (const name of readdirSync(at('stored'))) if (!needed.includes(name)) rmSync(at('stored', name))
    const { files } = assembleSite(at('dev'), at('stored'), at('out'))
    expect(Object.keys(files)).toEqual([DEV, LATEST, '0.3', '0.2'])
    expect(existsSync(at('out', '0.1'))).toBe(false)
  })

  test('the development build headers are the only ones, at the root', () => {
    store('0.1.0')
    store('0.2.0')
    assembleSite(at('dev'), at('stored'), at('out'))
    expect(readFileSync(at('out', '_headers'), 'utf8')).toBe(`headers of ${DEV}`)
    expect(existsSync(at('out', DEV, '_headers'))).toBe(false)
    expect(existsSync(at('out', '0.1', '_headers'))).toBe(false)
    expect(existsSync(at('out', LATEST, '_headers'))).toBe(false)
  })

  test('a preview keeps every version out of search indexes', () => {
    store('0.1.0')
    assembleSite(at('dev'), at('stored'), at('out'), true)
    expect(readFileSync(at('out', '_headers'), 'utf8')).toBe(`headers of ${DEV}\n/*\n  X-Robots-Tag: noindex, nofollow\n`)
  })

  test('a stale output directory is replaced', () => {
    mkdirSync(at('out', '0.9'), { recursive: true })
    writeFileSync(at('out', '0.9', 'index.html'), 'stale')
    assembleSite(at('dev'), at('stored'), at('out'))
    expect(existsSync(at('out', '0.9'))).toBe(false)
  })

  test('more files than the limit fails with the count of each version', () => {
    store('0.1.0', ['a.html', 'b.html'])
    expect(() => assembleSite(at('dev'), at('stored'), at('out'), false, 5)).toThrow(
      'The site has 6 files and Cloudflare takes 5. dev: 2, latest: 4',
    )
    expect(() => assembleSite(at('dev'), at('stored'), at('out'), false, 6)).not.toThrow()
  })

  test('a version without a front page fails', () => {
    store('0.1.0')
    rmSync(at('dev', 'index.html'))
    expect(() => assembleSite(at('dev'), at('stored'), at('out'))).toThrow('Version dev has no index.html')
  })
})
