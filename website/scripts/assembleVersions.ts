import { appendFileSync, cpSync, existsSync, mkdirSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  DEV,
  LATEST,
  MANIFEST_PATH,
  releaseTags,
  segmentOf,
  siteVersions,
  type VersionManifest,
} from '../src/lib/docsVersions.ts'

/** Cloudflare's ceiling on the files in one Worker version's static assets, on the Free plan. */
export const ASSET_FILE_LIMIT = 20_000

const ARCHIVE = '.tar.gz'

/** The ending of a release's site built to be served under [LATEST], beside its `<tag>.tar.gz`. */
export const LATEST_ARCHIVE = `.${LATEST}${ARCHIVE}`
const HEADERS = '_headers'
const NOINDEX = '\n/*\n  X-Robots-Tag: noindex, nofollow\n'

/** The name of the archive [assembleSite] reads the stable [version] of [manifest] from. */
export function storedName(manifest: VersionManifest, version: string): string {
  const tag = releaseTags(manifest)[version]
  return `${tag}${version === manifest.latest ? LATEST_ARCHIVE : ARCHIVE}`
}

/** What [assembleSite] put together, with the file count of each path segment. */
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
 * Puts the whole site together in [out]: the build in [dev] as the development version, and the stored site of each
 * release [siteVersions] serves from [stored], a directory of `<tag>.tar.gz` archives, under its own version. The
 * latest stable version is served under [LATEST] alone, from its release's `<tag>.latest.tar.gz`, the same site built
 * for that prefix. Writes the manifest and the one `_headers` file at the
 * root, which keeps every page of every version out of search indexes when [noindex] is set, as a pull request preview
 * needs.
 *
 * Throws when a served release's archive is missing, when a version has no front page, or when the site
 * has more files than [limit].
 */
export function assembleSite(
  dev: string,
  stored: string,
  out: string,
  noindex: boolean = false,
  limit: number = ASSET_FILE_LIMIT,
): Assembled {
  const names = existsSync(stored) ? readdirSync(stored) : []
  const archives = names.filter((name) => name.endsWith(ARCHIVE))
  const manifest = siteVersions(
    archives.map((name) => name.slice(0, name.endsWith(LATEST_ARCHIVE) ? -LATEST_ARCHIVE.length : -ARCHIVE.length)),
  )
  const segments = manifest.versions.map(({ version }) => segmentOf(manifest, version))

  rmSync(out, { recursive: true, force: true })
  mkdirSync(out, { recursive: true })
  cpSync(dev, join(out, DEV), { recursive: true })
  for (const [version, tag] of Object.entries(releaseTags(manifest))) {
    const archive = storedName(manifest, version)
    if (!names.includes(archive)) throw new Error(`Release ${tag} has no ${archive}`)
    unpack(join(stored, archive), join(out, segmentOf(manifest, version)))
  }

  const files: Record<string, number> = {}
  for (const segment of segments) {
    const dir = join(out, segment)
    if (!existsSync(join(dir, 'index.html'))) throw new Error(`Version ${segment} has no index.html`)
    // Cloudflare reads _headers at the root alone, so the development build's copy speaks for every version.
    if (segment === DEV) renameSync(join(dir, HEADERS), join(out, HEADERS))
    else rmSync(join(dir, HEADERS), { force: true })
    files[segment] = countFiles(dir)
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
