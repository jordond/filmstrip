import type { HastPluginDefinition } from 'satteri'

interface Node {
  type: string
  value?: string
  children?: readonly Node[]
}

interface Mark {
  type: 'element'
  tagName: 'span'
  properties: { className: string[]; role: 'img'; ariaLabel: string }
  children: [{ type: 'text'; value: string }]
}

const MARKS: Record<string, { className: string; label: string }> = {
  '✓': { className: 'mark-yes', label: 'Yes' },
  '✗': { className: 'mark-no', label: 'No' },
}

/**
 * The span that replaces a table cell's only text when that text is a ✓ or a ✗, with a class to colour it and a label
 * for screen readers. Any other cell gets null.
 */
export function markFor(cell: Node): Mark | null {
  const [only, ...rest] = cell.children ?? []
  if (!only || rest.length > 0 || only.type !== 'text') return null
  const value = only.value?.trim() ?? ''
  const found = MARKS[value]
  if (!found) return null
  return {
    type: 'element',
    tagName: 'span',
    properties: { className: [found.className], role: 'img', ariaLabel: found.label },
    children: [{ type: 'text', value }],
  }
}

/**
 * A Sätteri hast plugin that swaps a lone ✓ or ✗ in a table cell for the span [markFor] builds.
 */
export const tableMarks: HastPluginDefinition = {
  name: 'table-marks',
  element: [
    {
      filter: ['td'],
      visit(node, ctx) {
        const mark = markFor(node as Node)
        const text = node.children?.[0]
        if (mark && text) ctx.replaceNode(text, mark)
      },
    },
  ],
}
