import { createReadStream, statSync } from 'node:fs'
import { extname, join, normalize, resolve } from 'node:path'
import type { Plugin } from 'vite'

/**
 * What scripts/assemble.sh copies into a built site, keyed by the path a version of the site serves it at. The dev
 * server reads each in place, so a Gradle output has to be built before it shows up there. A longer path wins over a
 * shorter one it sits inside.
 */
export const BUILT_MOUNTS: Record<string, string> = {
  '/api/fonts/': 'website/src/fonts',
  '/api/': 'dokka',
  '/demo/app/': 'sample/demo/webApp/build/dist/wasmJs/productionExecutable',
  '/demo/try/': 'sample/tryIt/build/dist/wasmJs/productionExecutable',
  '/demo/playground/': 'sample/playground/build/dist/wasmJs/productionExecutable',
}

/** Files scripts/assemble.sh builds from the site's own sources, which the dev server compiles on request. */
export const SOURCE_MOUNTS: Record<string, string> = {
  '/api/version-menu.js': '/src/lib/versionMenu.client.ts',
}

const TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.wasm': 'application/wasm',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.ttf': 'font/ttf',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.mp4': 'video/mp4',
  '.txt': 'text/plain; charset=utf-8',
}

/**
 * A Vite plugin for `astro dev` that serves each of [BUILT_MOUNTS] straight from its directory under [repoRoot], at
 * its path under [base], and sends each of [SOURCE_MOUNTS] to its source. A request for a directory gets its
 * index.html, and a mount that hasn't been built answers 404 with the path it looked in.
 */
export function builtMounts(repoRoot: string, base: string): Plugin {
  const mounts = Object.keys(BUILT_MOUNTS).sort((a, b) => b.length - a.length)
  return {
    name: 'filmstrip-built-mounts',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const asked = decodeURIComponent((req.url ?? '').split('?')[0])
        // The dev server has taken the base off by the time a request gets here, and a build preview has not.
        const path = asked.startsWith(`${base}/`) ? asked.slice(base.length) : asked
        if (path in SOURCE_MOUNTS) {
          res.writeHead(302, { Location: `${base}${SOURCE_MOUNTS[path]}` })
          return res.end()
        }
        const mount = mounts.find((prefix) => path === prefix.slice(0, -1) || path.startsWith(prefix))
        if (!mount) return next()
        if (path === mount.slice(0, -1)) {
          res.writeHead(301, { Location: `${base}${mount}` })
          return res.end()
        }
        const root = resolve(repoRoot, BUILT_MOUNTS[mount])
        let file = normalize(join(root, path.slice(mount.length)))
        if (!file.startsWith(root)) {
          res.statusCode = 403
          return res.end()
        }
        try {
          if (statSync(file).isDirectory()) file = join(file, 'index.html')
          const size = statSync(file).size
          res.writeHead(200, {
            'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream',
            'Content-Length': size,
            'Cache-Control': 'no-store',
          })
          createReadStream(file).pipe(res)
        } catch {
          res.statusCode = 404
          res.end(`Nothing built at ${root}`)
        }
      })
    },
  }
}
