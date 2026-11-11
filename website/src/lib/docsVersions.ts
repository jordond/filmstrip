/** The version built from the main branch. */
export const DEV = 'dev'

/** The path segment that always leads to the newest stable version. */
export const LATEST = 'latest'

/** The file at the site root that lists every version, read by the worker and by every page's version menu. */
export const MANIFEST_PATH = '/versions.json'

/** One version of the site, served under `/<version>/`. */
export interface SiteVersion {
  version: string
  title: string
}

/** Every version the site serves, [DEV] first and then the newest stable first, and which one is the latest stable. */
export interface VersionManifest {
  latest: string | null
  versions: SiteVersion[]
}

const RELEASE = /^(\d+)\.(\d+)\.(\d+)$/

/**
 * The version a release [tag] is served under, its major and minor. A prerelease or anything else that isn't `X.Y.Z`
 * has none.
 */
export function versionLabel(tag: string): string | null {
  const match = tag.match(RELEASE)
  return match ? `${match[1]}.${match[2]}` : null
}

/**
 * Builds the manifest for the releases in [tags]. Each minor keeps its highest patch, and tags without a
 * [versionLabel] are left out.
 */
export function siteVersions(tags: readonly string[]): VersionManifest {
  const newest = new Map<string, number[]>()
  for (const tag of tags) {
    const label = versionLabel(tag)
    if (label === null) continue
    const parts = tag.split('.').map(Number)
    const held = newest.get(label)
    if (!held || parts[2] > held[2]) newest.set(label, parts)
  }
  const stable = [...newest.values()]
    .sort((a, b) => b[0] - a[0] || b[1] - a[1])
    .map((parts) => ({ version: `${parts[0]}.${parts[1]}`, title: parts.join('.') }))
  return { latest: stable[0]?.version ?? null, versions: [{ version: DEV, title: DEV }, ...stable] }
}

/** The release tag each stable version of [manifest] was built from, keyed by version. */
export function releaseTags(manifest: VersionManifest): Record<string, string> {
  return Object.fromEntries(manifest.versions.filter((v) => v.version !== DEV).map((v) => [v.version, v.title]))
}

/** The version of the site [pathname] is in, its first segment. The root has none. */
export function versionOfPath(pathname: string): string | null {
  return pathname.split('/')[1] || null
}

/** The path of the page at [pathname] in the version [to]. */
export function swapVersion(pathname: string, to: string): string {
  const [, , ...rest] = pathname.split('/')
  return `/${[to, ...rest].join('/')}`
}

/** What the worker does with a request: send the reader somewhere else, or serve an asset. */
export type Route = { redirect: string } | { asset: string }

/**
 * Routes a request for [url] that matched no asset. [demoHost] is the host whose root is the full screen demo at
 * [demoPage], a path inside a version.
 *
 * The root, `/latest/` and any path outside a version go to the latest stable version, or to [DEV] while there is no
 * stable one. A path inside a version is served as asked, so that version's own 404 page answers.
 */
export function routeFor(url: URL, manifest: VersionManifest, demoHost: string, demoPage: string): Route {
  const home = manifest.latest ?? DEV
  if (url.hostname === demoHost && url.pathname === '/') return { asset: `/${home}/${demoPage}` }
  const first = versionOfPath(url.pathname)
  if (first === LATEST) return { redirect: swapVersion(url.pathname, home) + url.search }
  if (first !== null && manifest.versions.some((v) => v.version === first)) return { asset: url.pathname }
  return { redirect: `/${home}${url.pathname}${url.search}` }
}
