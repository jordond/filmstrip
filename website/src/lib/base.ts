/**
 * Puts [path], a path from the root of one version of the site, under [base]. [base] may end in a slash or not.
 */
export function joinBase(base: string, path: string): string {
  return `${base.replace(/\/$/, '')}${path}`
}

/** The prefix this version of the site is served under, without a trailing slash. Empty outside an Astro build. */
export const BASE: string = joinBase(import.meta.env?.BASE_URL ?? '', '')

/** The address of [path] in this version of the site. */
export function withBase(path: string): string {
  return joinBase(BASE, path)
}
