import { expect, test } from 'bun:test'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { repoRoot } from './snippets'

const src = join(import.meta.dir, '..')

test('the landing page imports nothing from Starlight', () => {
  // Importing any Starlight component links its stylesheet, the docs theme included, into the landing page.
  const landing = readdirSync(join(src, 'components/landing')).map((name) => join('components/landing', name))
  const files = ['pages/index.astro', 'components/DemoFrame.astro', ...landing]
  const offenders = files.filter((path) => readFileSync(join(src, path), 'utf8').includes('@astrojs/starlight'))
  expect(offenders).toEqual([])
})

test('the platforms table names modules the build publishes', () => {
  const settings = readFileSync(join(repoRoot, 'settings.gradle.kts'), 'utf8')
  const platforms = readFileSync(join(src, 'components/landing/Platforms.astro'), 'utf8')
  const artifacts = [...platforms.matchAll(/artifact: '([^']+)'/g)].map((match) => match[1])
  expect(artifacts).toHaveLength(4)
  for (const artifact of artifacts) expect(settings).toContain(`":${artifact}"`)
})
