export { DEMO_HOST, SITE_URL } from './hosts.ts'

/** The filmstrip repository on GitHub. */
export const REPO_URL = 'https://github.com/jordond/filmstrip'

/** The filmstrip namespace on Maven Central. */
export const MAVEN_CENTRAL_URL = 'https://central.sonatype.com/namespace/dev.jordond.filmstrip'

/** The page the landing page's DOCS links open. */
export const DOCS_ENTRY = '/start/installation/'

/** Whether this is a pull request preview build, which is kept out of search indexes. */
export const isPreviewBuild = process.env.FILMSTRIP_SITE_ENV === 'preview'
