import {
  DEV,
  LATEST,
  MANIFEST_PATH,
  segmentOf,
  swapVersion,
  versionOfPath,
  type VersionManifest,
} from './docsVersions.ts'

/** One entry of the version menu. */
export interface MenuOption {
  value: string
  label: string
  selected: boolean
}

/**
 * The version menu's entries for [manifest], read from a page under the path segment [current]. Each entry's value is
 * the path segment its version is served under. The latest stable version says so, and a [current] the manifest
 * doesn't list yet is added at the top so the menu still shows where the reader is.
 */
export function menuOptions(manifest: VersionManifest, current: string): MenuOption[] {
  const listed = manifest.versions.map(({ version }) => {
    const value = segmentOf(manifest, version)
    return { value, label: value === LATEST ? `${version} (${LATEST})` : version, selected: value === current }
  })
  if (listed.some((option) => option.selected)) return listed
  return [{ value: current, label: current, selected: true }, ...listed]
}

/**
 * Where choosing the version under the path segment [version] takes a reader on [pathname]: the same page in that
 * version when [exists] finds it, and that version's front page when it doesn't.
 */
export async function versionTarget(
  pathname: string,
  version: string,
  exists: (path: string) => Promise<boolean>,
): Promise<string> {
  const page = swapVersion(pathname, version)
  return (await exists(page)) ? page : `/${version}/`
}

const exists = (path: string) =>
  fetch(path, { method: 'HEAD' }).then(
    (response) => response.ok,
    () => false,
  )

/**
 * Fills every `[data-version-menu]` select on the page from the site's manifest and moves the reader when one changes.
 * On the development version it shows each `[data-dev-banner]`. The banner's `[data-latest-link]` leads to the same
 * page in the latest stable version, and its `[data-latest]` sentence is hidden while there is none or the manifest can't be read.
 */
export async function mountVersionUi(): Promise<void> {
  const current = versionOfPath(location.pathname)
  if (current === null) return
  const go = async (version: string) => location.assign(await versionTarget(location.pathname, version, exists))

  const banners = current === DEV ? [...document.querySelectorAll<HTMLElement>('[data-dev-banner]')] : []
  for (const banner of banners) banner.hidden = false

  const manifest: VersionManifest | null = await fetch(MANIFEST_PATH).then(
    (response) => (response.ok ? response.json() : null),
    () => null,
  )
  const latest = manifest?.latest ?? null
  for (const banner of banners) {
    for (const sentence of banner.querySelectorAll<HTMLElement>('[data-latest]')) sentence.hidden = latest === null
  }
  if (manifest === null) return

  for (const menu of document.querySelectorAll<HTMLSelectElement>('select[data-version-menu]')) {
    menu.replaceChildren(
      ...menuOptions(manifest, current).map(({ value, label, selected }) => new Option(label, value, selected, selected)),
    )
    menu.hidden = false
    menu.addEventListener('change', () => go(menu.value))
  }
  if (latest === null) return
  for (const banner of banners) {
    for (const link of banner.querySelectorAll('a[data-latest-link]')) {
      link.addEventListener('click', (event) => {
        event.preventDefault()
        go(LATEST)
      })
    }
  }
}
