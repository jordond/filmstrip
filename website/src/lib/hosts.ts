/** Where the docs site is served. */
export const SITE_URL = 'https://filmstrip.jordond.dev'

/** Where the sample app is served on its own, in the television filling the window. */
export const DEMO_HOST = 'https://demo.filmstrip.jordond.dev/'

/** The page [DEMO_HOST] serves at its root. */
export const DEMO_PAGE = '/demo/tv/'

/**
 * The asset path to serve for a request to [url]. The demo host's root is the full screen demo, and everything else is
 * served as asked.
 */
export function assetPath(url: URL): string {
  return url.hostname === new URL(DEMO_HOST).hostname && url.pathname === '/' ? DEMO_PAGE : url.pathname
}
