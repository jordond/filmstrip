import { withBase } from './base.ts'
import { DEV } from './docsVersions.ts'

export { DEMO_HOST, SITE_URL } from './hosts.ts'

/** The filmstrip repository on GitHub. */
export const REPO_URL = 'https://github.com/jordond/filmstrip'

/** The filmstrip namespace on Maven Central. */
export const MAVEN_CENTRAL_URL = 'https://central.sonatype.com/namespace/dev.jordond.filmstrip'

/** The page the landing page's DOCS links open. */
export const DOCS_ENTRY = withBase('/start/installation/')

/** The API reference's front page. */
export const API_ENTRY = withBase('/api/')

/** The site's icon. */
export const FAVICON = withBase('/favicon.svg')

/** The version of the site being built, `FILMSTRIP_DOCS_VERSION` or the development version. */
export const DOCS_VERSION = process.env.FILMSTRIP_DOCS_VERSION || DEV

/** Whether this is a pull request preview build, which is kept out of search indexes. */
export const isPreviewBuild = process.env.FILMSTRIP_SITE_ENV === 'preview'
