import { createReadStream, statSync } from 'node:fs'
import { extname, join, normalize, resolve } from 'node:path'
import type { Plugin } from 'vite'

/**
 * The Gradle outputs that scripts/assemble.sh copies into dist after a build, keyed by the path the site serves them
 * at. The dev server reads them in place, so each one has to be built before it shows up there.
 */
export const BUILT_MOUNTS: Record<string, string> = {
  '/api/': 'dokka',
  '/demo/app/': 'sample/demo/webApp/build/dist/wasmJs/productionExecutable',
  '/demo/try/': 'sample/tryIt/build/dist/wasmJs/productionExecutable',
  '/demo/playground/': 'sample/playground/build/dist/wasmJs/productionExecutable',
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
 * A Vite plugin for `astro dev` that serves each of [BUILT_MOUNTS] straight from its Gradle output under [repoRoot].
 * A request for a directory gets its index.html, and a mount that hasn't been built answers 404 with the path it
 * looked in.
 */
export function builtMounts(repoRoot: string): Plugin {
  return {
    name: 'filmstrip-built-mounts',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const path = decodeURIComponent((req.url ?? '').split('?')[0])
        const mount = Object.keys(BUILT_MOUNTS).find((prefix) => path === prefix.slice(0, -1) || path.startsWith(prefix))
        if (!mount) return next()
        if (path === mount.slice(0, -1)) {
          res.writeHead(301, { Location: mount })
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
