// Usage: bun scripts/version-label.ts <tag>
// Prints the version of the site a release tag is served under, 0.1 for 0.1.3. Fails for a tag that gets no version.
import { versionLabel } from '../src/lib/docsVersions.ts'

const tag = process.argv[2] ?? ''
const label = versionLabel(tag)
if (label === null) {
  console.error(`"${tag}" is not an X.Y.Z release, so it gets no version of the site.`)
  process.exit(1)
}
console.log(label)
