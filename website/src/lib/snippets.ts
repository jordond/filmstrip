import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

/**
 * The repository root, the nearest directory above the working directory with a settings.gradle.kts. Astro bundles
 * this module before it runs, so its own location says nothing about where the repository is.
 */
export const repoRoot = findRepoRoot(process.cwd())

function findRepoRoot(from: string): string {
  let dir = from
  while (!existsSync(join(dir, 'settings.gradle.kts'))) {
    const parent = dirname(dir)
    if (parent === dir) throw new Error(`No settings.gradle.kts above ${from}`)
    dir = parent
  }
  return dir
}

/** The source set root every snippet path is relative to. */
export const samplesRoot = 'internal/docs-samples/src'

// A marker is a whole line, so `"// region x"` inside a string never opens a region.
const marker = (kind: 'region' | 'endregion', name?: string) =>
  new RegExp(`^\\s*//\\s*#?${kind}${name === undefined ? '\\b' : `\\s+${escape(name)}\\s*$`}`)

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * Pulls the lines between `// region name` and the next `// endregion` out of a Kotlin file, with the indent they
 * share removed. Throws naming the region and the file when the region isn't there.
 */
export function extractRegion(source: string, region: string, file: string): string {
  const lines = source.split(/\r?\n/)
  const open = marker('region', region)
  const start = lines.findIndex((line) => open.test(line))
  if (start < 0) throw new Error(`Snippet region "${region}" not found in ${file}`)

  const close = marker('endregion')
  const end = lines.findIndex((line, index) => index > start && close.test(line))
  if (end < 0) throw new Error(`Snippet region "${region}" in ${file} has no // endregion`)

  const body = lines.slice(start + 1, end)
  const filled = body.filter((line) => line.trim() !== '')
  if (filled.length === 0) throw new Error(`Snippet region "${region}" in ${file} is empty`)

  const indent = Math.min(...filled.map((line) => line.length - line.trimStart().length))
  return body
    .map((line) => line.slice(indent).trimEnd())
    .join('\n')
    .replace(/^\n+|\n+$/g, '')
}

/**
 * Reads [region] out of [file], a path relative to [root], and takes a leading `internal` off each line so the snippet
 * shows code a reader can copy. Throws naming the file when it doesn't exist.
 */
export function readSnippet(file: string, region: string, root: string = join(repoRoot, samplesRoot)): string {
  const path = join(root, file)
  if (!existsSync(path)) throw new Error(`Snippet file ${file} not found under ${root}`)
  return extractRegion(readFileSync(path, 'utf8'), region, file).replace(/^([ \t]*)internal /gm, '$1')
}
