import { assetPath } from '../src/lib/hosts.ts'

interface Env {
  ASSETS: { fetch(request: Request): Promise<Response> }
}

/**
 * Serves the site's static assets, with the demo host's root answered by the full screen demo page.
 */
export default {
  fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url)
    const path = assetPath(url)
    if (path === url.pathname) return env.ASSETS.fetch(request)
    url.pathname = path
    return env.ASSETS.fetch(new Request(url, request))
  },
}
