import { Marked } from 'marked'
import DOMPurify from 'dompurify'
const renderer = new Marked({ renderer: { html: () => '', image: () => '' }, gfm: true, breaks: false })
export function renderMarkdown(content: string): string {
  return DOMPurify.sanitize(renderer.parse(content, { async: false }), { USE_PROFILES: { html: true } })
}
