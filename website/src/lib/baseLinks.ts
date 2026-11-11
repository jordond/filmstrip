import type { HastPluginDefinition, HastVisitorContext } from 'satteri'

/**
 * [url] under [base] when it is a path from the site root, like `/guides/edits/#trim`. A relative path, a fragment,
 * another site or a path already under [base] comes back as it is.
 */
export function basedUrl(url: string, base: string): string {
  if (!url.startsWith('/') || url.startsWith('//')) return url
  if (url === base || url.startsWith(`${base}/`)) return url
  return `${base}${url}`
}

/**
 * A Sätteri hast plugin that moves every root path in a page's links and images under [base], so the pages are written
 * without knowing which version of the site they end up in.
 */
export function baseLinks(base: string): HastPluginDefinition {
  const rebase = (tag: string, name: string) => ({
    filter: [tag],
    visit(node: Parameters<HastVisitorContext['setProperty']>[0], ctx: HastVisitorContext) {
      const url = (node as { properties?: Record<string, unknown> }).properties?.[name]
      if (typeof url === 'string' && basedUrl(url, base) !== url) ctx.setProperty(node, name, basedUrl(url, base))
    },
  })
  return { name: 'base-links', element: [rebase('a', 'href'), rebase('img', 'src')] }
}
