// Usage: bun scripts/assemble-versions.ts [stored]
// Puts website/dist together from the development build in website/dist-version, which scripts/assemble.sh makes, and
// the release sites in [stored], a directory of the archives scripts/served-releases.ts names.
// With no [stored] the site is the development version alone. FILMSTRIP_SITE_ENV=preview keeps the whole site out of search indexes.
import { join } from 'node:path'
import { assembleSite } from './assembleVersions.ts'

const website = join(import.meta.dir, '..')
const stored = process.argv[2] ?? join(website, 'stored-versions')
const preview = process.env.FILMSTRIP_SITE_ENV === 'preview'
const { manifest, files } = assembleSite(join(website, 'dist-version'), stored, join(website, 'dist'), preview)

console.log(`latest: ${manifest.latest ?? 'none'}`)
for (const [version, count] of Object.entries(files)) console.log(`${version}: ${count} files`)
