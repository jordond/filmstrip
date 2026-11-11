import { MANIFEST_PATH, routeFor, siteVersions, type VersionManifest } from '../src/lib/docsVersions.ts'
import { DEMO_HOST, DEMO_PAGE } from '../src/lib/hosts.ts'

interface Env {
  ASSETS: { fetch(request: Request): Promise<Response> }
}

const demoHost = new URL(DEMO_HOST).hostname
let manifest: Promise<VersionManifest> | undefined

async function readManifest(env: Env, origin: string): Promise<VersionManifest> {
  const response = await env.ASSETS.fetch(new Request(new URL(MANIFEST_PATH, origin)))
  if (response.ok) return response.json()
  manifest = undefined
  return siteVersions([])
}

/**
 * Answers the requests no static asset matched. The root, `/latest/` and paths from before the site had versions are
 * redirected into a version, the demo host's root is the full screen demo, and a miss inside a version gets that
 * version's 404 page.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url)
    manifest ??= readManifest(env, url.origin)
    const route = routeFor(url, await manifest, demoHost, DEMO_PAGE)
    if ('redirect' in route) return Response.redirect(new URL(route.redirect, url.origin).href, 302)
    if (route.asset === url.pathname) return env.ASSETS.fetch(request)
    url.pathname = route.asset
    return env.ASSETS.fetch(new Request(url, request))
  },
}
