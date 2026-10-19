export type Status = 'UNPUBLISHED' | 'PUBLISHED' | 'MODIFIED'
export const labels: Record<Status, string> = { UNPUBLISHED: '未发布', PUBLISHED: '已发布', MODIFIED: '有未发布修改' }
export interface Snapshot { name: string; description: string; content: string; publishedVersion: number; publishedAt: string }
export interface Skill {
  id: string; name: string; description: string; content: string; status: Status; lockVersion: number
  publishedSnapshot: Snapshot | null; createdAt: string; updatedAt: string; publishedAt: string | null
}
export interface Page<T> { items: T[]; page: number; pageSize: number; total: number }
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId: string) { super(message) }
}
export async function api<T>(path = '', method = 'GET', data?: unknown): Promise<T> {
  const response = await fetch('/api/admin/skills' + path, {
    method, headers: method === 'GET' ? {} : { 'Content-Type': 'application/json' },
    body: data === undefined ? undefined : JSON.stringify(data),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: '服务响应异常' }))
    throw new ApiError(response.status, error.code ?? 'HTTP_ERROR', error.message, error.requestId ?? response.headers.get('X-Request-Id') ?? '')
  }
  return response.status === 204 ? undefined as T : response.json()
}
export function message(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}${error.requestId ? '（请求 ' + error.requestId + '）' : ''}`
  return '网络连接失败，请检查服务后重试'
}
export const date = (value: string | null) => value ? new Date(value).toLocaleString() : '—'
export const rawUrl = (name: string) => new URL(`/api/v1/skills/${encodeURIComponent(name)}/SKILL.md`, window.location.origin).href
export async function copy(value: string) {
  if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(value); return }
  const input = document.createElement('textarea'); input.value = value; input.style.position = 'fixed'; input.style.opacity = '0'
  document.body.append(input); input.select()
  try { if (!document.execCommand('copy')) throw new Error('复制失败') } finally { input.remove() }
}
