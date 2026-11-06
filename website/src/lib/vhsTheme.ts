import type { ThemeRegistration } from 'shiki'

/**
 * The Shiki theme the landing page's code cards use. Keywords are amber, strings cyan, numbers coral and
 * annotations muted, on a transparent ground so the card's own panel shows through.
 */
export const vhsTheme: ThemeRegistration = {
  name: 'filmstrip-vhs',
  type: 'dark',
  bg: 'transparent',
  fg: '#f2ede4',
  settings: [
    { settings: { foreground: '#f2ede4', background: 'transparent' } },
    {
      scope: ['keyword', 'storage', 'storage.type', 'storage.modifier', 'keyword.control', 'constant.language'],
      settings: { foreground: '#ffb627' },
    },
    { scope: ['keyword.operator', 'punctuation'], settings: { foreground: '#f2ede4' } },
    { scope: ['string', 'string.quoted'], settings: { foreground: '#38e1ff' } },
    { scope: ['constant.numeric'], settings: { foreground: '#ff9a8a' } },
    { scope: ['meta.annotation', 'storage.type.annotation', 'entity.name.type.annotation'], settings: { foreground: '#a39cae' } },
    { scope: ['comment'], settings: { foreground: '#a39cae', fontStyle: 'italic' } },
  ],
}
