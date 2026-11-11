import { appendFileSync, cpSync, existsSync, mkdirSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { DEV, MANIFEST_PATH, releaseTags, siteVersions, type VersionManifest } from '../src/lib/docsVersions.ts'

/** Cloudflare's ceiling on the files in one Worker version's static assets, on the Free plan. */
export const ASSET_FILE_LIMIT = 20_000

const ARCHIVE = '.tar.gz'
const HEADERS = '_headers'
const NOINDEX = '\n/*\n  X-Robots-Tag: noindex, nofollow\n'

/** What [assembleSite] put together. */
export interface Assembled {
  manifest: VersionManifest
  files: Record<string, number>
}

function countFiles(dir: string): number {
  return readdirSync(dir, { recursive: true, withFileTypes: true }).filter((entry) => entry.isFile()).length
}

function unpack(archive: string, into: string): void {
  mkdirSync(into, { recursive: true })
  const { exitCode, stderr } = Bun.spawnSync(['tar', '-xzf', archive, '-C', into])
  if (exitCode !== 0) throw new Error(`Could not unpack ${archive}: ${stderr.toString().trim()}`)
}

/**
 * Puts the whole site together in [out]: the build in [dev] as the development version, and each release's stored
 * site from [stored], a directory of `<tag>.tar.gz` archives, under its own version. A minor with several releases
 * keeps its highest patch. Writes the manifest and the one `_headers` file at the root, which keeps every page of
 * every version out of search indexes when [noindex] is set, as a pull request preview needs.
 *
 * Throws when a version has no front page, or when the site has more files than [limit].
 */
export function assembleSite(
  dev: string,
  stored: string,
  out: string,
  noindex: boolean = false,
  limit: number = ASSET_FILE_LIMIT,
): Assembled {
  const archives = existsSync(stored) ? readdirSync(stored).filter((name) => name.endsWith(ARCHIVE)) : []
  const manifest = siteVersions(archives.map((name) => name.slice(0, -ARCHIVE.length)))

  rmSync(out, { recursive: true, force: true })
  mkdirSync(out, { recursive: true })
  cpSync(dev, join(out, DEV), { recursive: true })
  for (const [version, tag] of Object.entries(releaseTags(manifest))) {
    unpack(join(stored, `${tag}${ARCHIVE}`), join(out, version))
  }

  const files: Record<string, number> = {}
  for (const { version } of manifest.versions) {
    const dir = join(out, version)
    if (!existsSync(join(dir, 'index.html'))) throw new Error(`Version ${version} has no index.html`)
    // Cloudflare reads _headers at the root alone, so the development build's copy speaks for every version.
    if (version === DEV) renameSync(join(dir, HEADERS), join(out, HEADERS))
    else rmSync(join(dir, HEADERS), { force: true })
    files[version] = countFiles(dir)
  }
  if (noindex) appendFileSync(join(out, HEADERS), NOINDEX)
  writeFileSync(join(out, MANIFEST_PATH), `${JSON.stringify(manifest, null, 2)}\n`)

  const total = Object.values(files).reduce((sum, count) => sum + count, 0)
  if (total > limit) {
    const counts = Object.entries(files).map(([version, count]) => `${version}: ${count}`)
    throw new Error(`The site has ${total} files and Cloudflare takes ${limit}. ${counts.join(', ')}`)
  }
  return { manifest, files }
}
