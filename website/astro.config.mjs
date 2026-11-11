import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { satteri } from '@astrojs/markdown-satteri'
import starlight from '@astrojs/starlight'
import { ExpressiveCodeTheme } from '@astrojs/starlight/expressive-code'
import { defineConfig } from 'astro/config'
import starlightImageZoom from 'starlight-image-zoom'
import starlightLinksValidator from 'starlight-links-validator'
import starlightLlmsTxt from 'starlight-llms-txt'
import { baseLinks } from './src/lib/baseLinks.ts'
import { builtMounts } from './src/lib/devMounts.ts'
import { tableMarks } from './src/lib/tableMarks.ts'
import { DOCS_VERSION, isPreviewBuild as preview, REPO_URL as repo, SITE_URL } from './src/lib/site.ts'
import { tokenValue } from './src/lib/tokens.ts'
import { vhsTheme } from './src/lib/vhsTheme.ts'

// Every version of the site is served under its own prefix, /dev for the main branch and /0.1 for a release.
const base = `/${DOCS_VERSION}`
const tokensCss = readFileSync(new URL('./src/styles/tokens.css', import.meta.url), 'utf8')

export default defineConfig({
  site: SITE_URL,
  base,
  // One version of the site. scripts/assemble-versions.ts puts every version together in dist.
  outDir: './dist-version',
  output: 'static',
  trailingSlash: 'ignore',
  markdown: { processor: satteri({ hastPlugins: [tableMarks, baseLinks(base)] }) },
  vite: { plugins: [builtMounts(fileURLToPath(new URL('..', import.meta.url)), base)] },
  integrations: [
    starlight({
      title: 'filmstrip',
      description: 'Docs for filmstrip, a Kotlin Multiplatform library for encoding, editing and previewing video.',
      editLink: { baseUrl: `${repo}/edit/main/website/` },
      lastUpdated: true,
      head: [
        { tag: 'meta', attrs: { name: 'theme-color', content: tokenValue(tokensCss, 'ground-deep') } },
        ...(preview ? [{ tag: 'meta', attrs: { name: 'robots', content: 'noindex, nofollow' } }] : []),
      ],
      customCss: ['./src/styles/fonts.css', './src/styles/tokens.css', './src/styles/crt.css', './src/styles/docs.css'],
      components: {
        Banner: './src/components/docs/Banner.astro',
        Footer: './src/components/docs/Footer.astro',
        PageSidebar: './src/components/docs/PageSidebar.astro',
        Pagination: './src/components/docs/Pagination.astro',
        SiteTitle: './src/components/docs/SiteTitle.astro',
        SocialIcons: './src/components/docs/SocialIcons.astro',
        ThemeProvider: './src/components/docs/Nothing.astro',
        ThemeSelect: './src/components/docs/Nothing.astro',
      },
      expressiveCode: {
        themes: [new ExpressiveCodeTheme(vhsTheme)],
        // The landing page's code colours exactly, they already clear 4.5:1 on the panel.
        minSyntaxHighlightingColorContrast: 0,
        styleOverrides: {
          borderColor: 'var(--line)',
          codeBackground: 'var(--panel)',
          codeForeground: 'var(--text)',
          codeFontFamily: 'var(--font-body)',
          codeFontSize: '14px',
          codeLineHeight: '1.6',
          uiFontFamily: 'var(--font-osd)',
          uiFontSize: '20px',
          focusBorder: 'var(--cyan)',
          frames: {
            shadowColor: 'transparent',
            frameBoxShadowCssValue: 'none',
            editorBackground: 'var(--panel)',
            editorTabBarBackground: 'var(--ground)',
            editorTabBarBorderColor: 'var(--line)',
            editorTabBarBorderBottomColor: 'var(--line)',
            editorActiveTabBackground: 'var(--panel)',
            editorActiveTabForeground: 'var(--amber)',
            editorActiveTabBorderColor: 'var(--line)',
            editorActiveTabIndicatorTopColor: 'var(--amber)',
            editorActiveTabIndicatorBottomColor: 'transparent',
            terminalBackground: 'var(--panel)',
            terminalTitlebarBackground: 'var(--ground)',
            terminalTitlebarForeground: 'var(--muted-strong)',
            terminalTitlebarBorderBottomColor: 'var(--line)',
            terminalTitlebarDotsForeground: 'var(--line-strong)',
            terminalTitlebarDotsOpacity: '1',
            inlineButtonBackground: 'var(--panel-raised)',
            inlineButtonBackgroundIdleOpacity: '1',
            inlineButtonBackgroundHoverOrFocusOpacity: '1',
            inlineButtonBackgroundActiveOpacity: '1',
            inlineButtonForeground: 'var(--amber)',
            inlineButtonBorder: 'var(--line-strong)',
            inlineButtonBorderOpacity: '1',
            tooltipSuccessBackground: 'var(--amber)',
            tooltipSuccessForeground: 'var(--ground)',
          },
        },
      },
      plugins: [
        // /api and /demo/app are copied into dist after astro build, so the validator can't see them.
        starlightLinksValidator({ exclude: ['/api', '/api/**', '/demo/app', '/demo/app/**'] }),
        starlightLlmsTxt({
          projectName: 'filmstrip',
          description:
            'filmstrip is a Kotlin Multiplatform library for encoding, editing and previewing video on Android, iOS, macOS, the JVM and the browser.',
          // The demo and playgrounds are LOAD buttons for a browser, nothing a reader of the text can use.
          customSelectors: { all: ['[data-demo]', '[data-playground]'] },
        }),
        starlightImageZoom(),
      ],
      sidebar: [
        {
          label: 'Start',
          items: [
            { label: 'Installation', slug: 'start/installation' },
            { label: 'Requirements', slug: 'start/requirements' },
            { label: 'Quick start', slug: 'start/quick-start' },
          ],
        },
        {
          label: 'Guides',
          items: [
            { label: 'Setup', slug: 'guides/setup' },
            { label: 'Sources and sinks', slug: 'guides/sources-and-sinks' },
            { label: 'Edits', slug: 'guides/edits' },
            { label: 'Effects', slug: 'guides/effects' },
            { label: 'Filters', slug: 'guides/filters' },
            { label: 'Overlays', slug: 'guides/overlays' },
            { label: 'Audio', slug: 'guides/audio' },
            { label: 'Export', slug: 'guides/export' },
            { label: 'Probe and frames', slug: 'guides/probe-and-frames' },
            { label: 'Stills', slug: 'guides/stills' },
            { label: 'HDR', slug: 'guides/hdr' },
            { label: 'Player', slug: 'guides/player' },
          ],
        },
        {
          label: 'Compose',
          items: [
            { label: 'Bindings', slug: 'compose/bindings' },
            { label: 'Timeline', slug: 'compose/timeline' },
            { label: 'Pickers', slug: 'compose/pickers' },
          ],
        },
        {
          label: 'Reference',
          items: [
            { label: 'Targets', slug: 'reference/targets' },
            { label: 'Backends', slug: 'reference/backends' },
            { label: 'Effects', slug: 'reference/effects' },
            { label: 'Codecs', slug: 'reference/codecs' },
            { label: 'Limits', slug: 'reference/limits' },
          ],
        },
        {
          label: 'Extending',
          items: [{ label: 'Custom effects', slug: 'extending/custom-effects' }],
        },
        { label: 'Demo', slug: 'demo' },
        { label: 'API reference', link: '/api/' },
      ],
    }),
  ],
})
