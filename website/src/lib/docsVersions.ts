/** The version built from the main branch. */
export const DEV = 'dev'

/** The path segment the latest stable version is served under. */
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

/** How many minors of the newest major the site serves, the newest ones. */
export const SERVED_MINORS = 3

/** How many majors before the newest the site serves, each as its newest minor. */
export const SERVED_OLDER_MAJORS = 2

const RELEASE = /^(\d+)\.(\d+)\.(\d+)$/
const VERSION = /^\d+\.\d+$/

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
 * [versionLabel] are left out. The site serves the newest [SERVED_MINORS] minors of the newest major and the newest
 * minor of each of the [SERVED_OLDER_MAJORS] majors before it, and every other minor is left out too.
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
  const minors = [...newest.values()].sort((a, b) => b[0] - a[0] || b[1] - a[1])
  const majors = [...new Set(minors.map((parts) => parts[0]))].slice(0, 1 + SERVED_OLDER_MAJORS)
  const stable = majors
    .flatMap((major, at) => minors.filter((parts) => parts[0] === major).slice(0, at === 0 ? SERVED_MINORS : 1))
    .map((parts) => ({ version: `${parts[0]}.${parts[1]}`, title: parts.join('.') }))
  return { latest: stable[0]?.version ?? null, versions: [{ version: DEV, title: DEV }, ...stable] }
}

/** The release tag each stable version of [manifest] was built from, keyed by version. */
export function releaseTags(manifest: VersionManifest): Record<string, string> {
  return Object.fromEntries(manifest.versions.filter((v) => v.version !== DEV).map((v) => [v.version, v.title]))
}

/** The path segment [version] is served under, [LATEST] for the latest stable version of [manifest]. */
export function segmentOf(manifest: VersionManifest, version: string): string {
  return version === manifest.latest ? LATEST : version
}

/** The path segment of the site [pathname] is in, its first. The root has none. */
export function versionOfPath(pathname: string): string | null {
  return pathname.split('/')[1] || null
}

/** The path of the page at [pathname] under the path segment [to]. */
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
 * The latest stable version is served under [LATEST], and the root, that version's own number and any path outside a
 * version go there, or to [DEV] while there is no stable one. So does the same page of a version the site doesn't
 * serve. A path inside a version is served as asked, so that version's own 404 page answers.
 */
export function routeFor(url: URL, manifest: VersionManifest, demoHost: string, demoPage: string): Route {
  const home = manifest.latest === null ? DEV : LATEST
  if (url.hostname === demoHost && url.pathname === '/') return { asset: `/${home}/${demoPage}` }
  const first = versionOfPath(url.pathname)
  if (first === null) return { redirect: `/${home}/${url.search}` }
  if (first === home) return { asset: url.pathname }
  const served = manifest.versions.some((v) => v.version === first)
  if (served && first !== manifest.latest) return { asset: url.pathname }
  if (served || first === LATEST || VERSION.test(first)) return { redirect: swapVersion(url.pathname, home) + url.search }
  return { redirect: `/${home}${url.pathname}${url.search}` }
}
