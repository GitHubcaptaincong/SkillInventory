// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { renderMarkdown } from './markdown'
describe('Markdown 安全边界', () => {
  it('禁用原始 HTML 和事件处理器', () => {
    const html = renderMarkdown('<script>alert(1)</script>\n\n<img src=x onerror="alert(1)">\n\n**安全正文**')
    expect(html).not.toMatch(/<script|<img|onerror/i)
    expect(html).toContain('<strong>安全正文</strong>')
  })
  it('过滤 javascript 协议', () => {
    const html = renderMarkdown('[点击](javascript:alert%281%29)')
    expect(html).not.toContain('href="javascript:')
    expect(html).toContain('点击')
  })
  it('代码块内命令只作为文本', () => {
    expect(renderMarkdown('```sh\ncurl https://example.com\n```')).toContain('<code class="language-sh">')
  })
  it('不加载正文中的图片 URL', () => {
    expect(renderMarkdown('![remote](https://example.com/tracker.png)')).not.toContain('<img')
  })
})
