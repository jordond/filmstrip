// Usage: bun scripts/served-releases.ts [tag...]
// Prints a line for each release the site serves out of the given tags, its tag and then the name
// scripts/assemble-versions.ts reads its site from, <tag>.latest.tar.gz for the latest and <tag>.tar.gz for the rest.
import { releaseTags, siteVersions } from '../src/lib/docsVersions.ts'
import { storedName } from './assembleVersions.ts'

const manifest = siteVersions(process.argv.slice(2))
for (const [version, tag] of Object.entries(releaseTags(manifest))) console.log(`${tag} ${storedName(manifest, version)}`)
