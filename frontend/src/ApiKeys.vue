<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { request, ApiError, copy, type Page } from './api'
import KeyOverlay from './KeyOverlay.vue'
interface Key { id: string; name: string; masked: string; createdAt: string; expiresAt: string | null; revokedAt: string | null; status: 'ACTIVE' | 'EXPIRED' | 'REVOKED' }
const items = ref<Key[]>([]), page = ref(1), total = ref(0), pageSize = ref(20), loadedPage = ref(1)
const loaded = ref(false), loading = ref(false), listError = ref(''), notice = ref(''), expired = ref(false)
const drawer = ref(false), name = ref(''), expires = ref(''), duration = ref('never'), submitted = ref(false)
const creating = ref(false), createError = ref(''), revoking = ref(false), revokeError = ref(''), target = ref<Key | null>(null)
const secret = ref(''), created = ref<Key | null>(null), copied = ref(''), closing = ref(false), secretInput = ref<HTMLTextAreaElement>()
let sequence = 0, disposed = false, sessionVersion = 0, copyTimer: ReturnType<typeof setTimeout> | undefined
const zone = Intl.DateTimeFormat().resolvedOptions().timeZone
const labels = { ACTIVE: '有效', EXPIRED: '已过期', REVOKED: '已撤销' }
const format = (value: string | null) => value ? new Intl.DateTimeFormat('sv-SE', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '永不过期'
const nameError = computed(() => !name.value.trim() ? '请输入密钥名称' : name.value.length > 100 ? '密钥名称最多 100 字' : '')
const expiryError = computed(() => duration.value === 'custom' && (!expires.value || !Number.isFinite(new Date(expires.value).getTime()) || new Date(expires.value).getTime() <= Date.now()) ? '请选择未来的日期时间' : '')
const finalExpiry = computed(() => duration.value === 'never' ? null : duration.value === 'custom' ? expires.value && !expiryError.value ? new Date(expires.value).toISOString() : null : expires.value)
const curl = `curl --header "X-API-Key: $SKILL_API_KEY" \\\n  "${window.location.origin}/api/v1/skills?page=1&pageSize=20"`
function selectDuration() {
  expires.value = duration.value === 'never' || duration.value === 'custom' ? '' : new Date(Date.now() + Number(duration.value) * 86400000).toISOString()
}
function failure(e: unknown) {
  if (e instanceof ApiError) {
    if (e.status === 401) return '登录已失效，请重新登录后重试。'
    if (e.status === 403) return '权限不足或 CSRF 校验失败，请重新连接后手动重试。'
    if (e.code === 'INVALID_ARGUMENT') return '请检查名称和到期时间后重试。'
    return `请求失败（${e.status}），请稍后手动重试。`
  }
  return '网络连接失败，请检查连接后重试。'
}
async function load() {
  const ticket = ++sequence
  loading.value = true; listError.value = ''
  try {
    const result = await request<Page<Key>>(`/api/admin/api-keys?page=${page.value}`)
    if (ticket !== sequence || disposed) return
    items.value = result.items; total.value = result.total; pageSize.value = result.pageSize; loadedPage.value = result.page; loaded.value = true
  } catch (e) { if (ticket === sequence && !disposed) listError.value = failure(e) }
  finally { if (ticket === sequence && !disposed) loading.value = false }
}
function clearSecret() {
  if (secretInput.value) { secretInput.value.value = ''; secretInput.value.defaultValue = '' }
  secret.value = ''; created.value = null; copied.value = ''; closing.value = false; clearTimeout(copyTimer)
}
function loseSession() {
  sessionVersion++; expired.value = true; clearSecret(); target.value = null
  ++sequence; loading.value = false
}
function connected() { expired.value = false; void load() }
async function create() {
  if (creating.value || expired.value) return
  submitted.value = true
  if (nameError.value || expiryError.value) return
  if (finalExpiry.value && new Date(finalExpiry.value).getTime() <= Date.now()) { createError.value = '到期时间已过去，请重新选择未来时间。'; return }
  creating.value = true; createError.value = ''; notice.value = ''
  const version = sessionVersion
  try {
    const result = await request<{ key: string; metadata: Key }>('/api/admin/api-keys', 'POST', { name: name.value.trim(), expiresAt: finalExpiry.value })
    if (disposed || version !== sessionVersion) return
    secret.value = result.key; created.value = result.metadata; drawer.value = false
    name.value = ''; expires.value = ''; duration.value = 'never'; submitted.value = false
    notice.value = '密钥已创建。'; void load()
  } catch (e) {
    if (!disposed) createError.value = !(e instanceof ApiError) || e.status >= 500 ? '创建结果未确认，请刷新列表检查。若已创建但未收到完整密钥，请确认记录后撤销并重新创建；不要直接重复提交。' : failure(e)
  } finally { creating.value = false }
}
async function revoke() {
  if (!target.value || revoking.value || expired.value) return
  revoking.value = true; revokeError.value = ''; notice.value = ''
  const id = target.value.id, version = sessionVersion
  try {
    const result = await request<Key>(`/api/admin/api-keys/${id}/revoke`, 'POST', {})
    if (disposed || version !== sessionVersion) return
    items.value = items.value.map(k => k.id === id ? result : k)
    target.value = null; notice.value = '撤销已成功。'; void load()
  } catch (e) { revokeError.value = e instanceof ApiError ? failure(e) : '撤销结果未确认，请刷新列表检查状态后再操作。' }
  finally { revoking.value = false }
}
async function clipboard() {
  const value = secret.value
  try { await copy(value); if (secret.value === value) { copied.value = '已复制'; copyTimer = setTimeout(() => copied.value = '', 2500) } }
  catch { if (secret.value === value) { copied.value = '自动复制不可用，请选中密钥手动复制。'; secretInput.value?.focus(); secretInput.value?.select() } }
}
async function copyGuide() { try { await copy(curl); notice.value = '接入示例已复制。' } catch { notice.value = '自动复制不可用，请选中示例手动复制。' } }
function move(delta: number) { page.value += delta; void load() }
function beforeUnload(e: BeforeUnloadEvent) { if (secret.value || creating.value) { e.preventDefault(); e.returnValue = '' } }
onBeforeRouteLeave(() => {
  if (creating.value) return false
  if (secret.value && !window.confirm('完整密钥仅显示一次。离开后无法找回，确认已妥善保存并离开？')) return false
  clearSecret(); return true
})
onMounted(() => { void load(); window.addEventListener('session-expired', loseSession); window.addEventListener('session-restored', connected); window.addEventListener('beforeunload', beforeUnload) })
onBeforeUnmount(() => { disposed = true; sequence++; clearSecret(); window.removeEventListener('session-expired', loseSession); window.removeEventListener('session-restored', connected); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="api-keys">
    <div class="key-title"><div><h1>API 密钥</h1><p>为内部插件和客户端创建访问凭证，仅可读取已发布的 Skill。</p></div><button class="primary" :disabled="expired" @click="drawer = true">创建 API Key</button></div>
    <p class="rotation">轮换方式：创建新密钥 → 更新调用方 → 撤销旧密钥。</p>
    <details class="key-guide"><summary>接入说明</summary><ol><li>创建并妥善保存密钥。</li><li>将密钥放到调用方安全配置或环境变量 <code>SKILL_API_KEY</code>。</li><li>通过 X-API-Key 请求头调用已发布 Skill 只读接口。</li><li>轮换时先验证新密钥，再撤销旧密钥。</li></ol><pre tabindex="0">{{ curl }}</pre><button @click="copyGuide">复制 curl 示例</button><p>示例使用当前部署地址，运行前请配置环境变量。此 Key 不能创建、修改、发布或删除 Skill，也不能访问管理员接口。不要将长期共享密钥硬编码进网页前端或提交到仓库。</p></details>
    <p v-if="expired" class="alert" role="alert">登录已失效，请通过上方入口重新登录并连接。非敏感输入和页码已保留，完整密钥已清除且无法恢复。</p>
    <p v-if="notice" class="success" role="status">{{ notice }}</p>
    <section class="key-list" aria-label="密钥列表" :aria-busy="loading">
      <div class="key-list-head"><h2>密钥列表 <small v-if="loaded">{{ total }} 个</small></h2><span v-if="loading && loaded" role="status">正在刷新…</span><button :disabled="expired || loading" @click="load">刷新</button></div>
      <p class="key-timezone">日期时间按本地时区 {{ zone }} 展示</p>
      <div v-if="listError" class="alert" role="alert">{{ notice ? '列表刷新失败：' : '列表加载失败：' }}{{ listError }} <button :disabled="expired || loading" @click="load">重试</button></div>
      <div v-if="!loaded && loading" class="key-skeleton" role="status" aria-label="正在加载密钥列表"><div v-for="n in 4" :key="n"></div></div>
      <div v-else-if="loaded && !items.length && !listError" class="empty"><h2>还没有 API 密钥</h2><p>密钥用于让内部插件和客户端读取已发布 Skill。点击右上方“创建 API Key”开始接入。</p></div>
      <p v-if="loaded && loadedPage !== page" class="key-timezone" role="status">正在查看第 {{ page }} 页；当前表格暂保留上次成功加载的第 {{ loadedPage }} 页数据。</p>
      <div v-if="loaded && items.length" class="table-wrap" tabindex="0" aria-label="密钥表格，可横向滚动"><table class="key-table"><thead><tr><th>名称与标识</th><th>权限</th><th>状态</th><th>创建时间</th><th>有效期</th><th>操作</th></tr></thead><tbody><tr v-for="key in items" :key="key.id"><td><span class="key-name" tabindex="0" :title="key.name">{{ key.name }}</span><code class="key-masked">{{ key.masked }}</code></td><td><span class="badge">Skill 只读</span></td><td><span class="badge" :class="key.status">{{ labels[key.status] }}</span><small v-if="key.revokedAt" class="key-revoked">撤销于 {{ format(key.revokedAt) }}</small></td><td class="times">{{ format(key.createdAt) }}</td><td class="times">{{ format(key.expiresAt) }}</td><td><button v-if="key.status === 'ACTIVE'" class="key-revoke" :disabled="expired || revoking" :aria-label="`撤销 ${key.name}`" @click="target = key; revokeError = ''">撤销</button><span v-else class="muted">—</span></td></tr></tbody></table></div>
      <div class="pagination key-pages"><button :disabled="page === 1 || expired" @click="move(-1)">上一页</button><span aria-live="polite">第 {{ page }} 页<span v-if="loaded"> / 共 {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span></span><button :disabled="!loaded || page * pageSize >= total || expired" @click="move(1)">下一页</button></div>
    </section>
    <KeyOverlay v-if="drawer" title="创建 API Key" drawer :busy="creating" @close="!creating && (drawer = false)">
      <form @submit.prevent="create"><div class="key-panel-body"><p v-if="expired" class="alert" role="alert">登录已失效，请先关闭抽屉，再通过页面上方入口重新登录并连接。输入已保留。</p><p class="muted">为调用方创建独立凭证，便于识别和轮换。</p><fieldset :disabled="creating || expired"><label for="key-name">密钥名称</label><input id="key-name" v-model="name" @blur="submitted = true" maxlength="100" required :aria-invalid="!!nameError && (submitted || !!name)" aria-describedby="key-name-help key-name-error"><p id="key-name-help" class="field-help">建议按调用方和环境命名，例如 policy-plugin-prod</p><p v-if="nameError && (submitted || !!name)" id="key-name-error" class="field-error">{{ nameError }}</p><label for="key-duration">有效期</label><select id="key-duration" v-model="duration" @change="selectDuration"><option value="never">永不过期</option><option value="7">7 天</option><option value="30">30 天</option><option value="90">90 天</option><option value="custom">自定义</option></select><template v-if="duration === 'custom'"><label for="key-expires">到期日期时间</label><input id="key-expires" v-model="expires" type="datetime-local" required aria-describedby="key-expiry-error" :aria-invalid="!!expiryError"><p v-if="expiryError" id="key-expiry-error" class="field-error">{{ expiryError }}</p></template><p class="field-help">到期时间：{{ expiryError ? '请选择未来时间' : format(finalExpiry) }}（{{ zone }}）</p><div class="key-scope"><strong>权限范围</strong><p>只读访问已发布 Skill</p></div></fieldset><p v-if="createError" class="alert" role="alert">{{ createError }}</p></div><div class="key-panel-foot"><button type="button" :disabled="creating" @click="drawer = false">取消</button><button class="primary" :disabled="creating || expired">{{ creating ? '正在创建…' : '创建密钥' }}</button></div></form>
    </KeyOverlay>
    <KeyOverlay v-if="secret && created" title="API Key 已创建" @close="closing = true">
      <div class="key-panel-body"><p class="success">密钥创建成功，可以配置到调用方。</p><p class="key-warning">完整密钥仅显示一次。关闭后将无法再次查看，请保存到安全的位置。</p><dl class="key-metadata"><dt>名称</dt><dd>{{ created.name }}</dd><dt>范围</dt><dd>只读访问已发布 Skill</dd><dt>到期</dt><dd>{{ format(created.expiresAt) }}（{{ zone }}）</dd></dl><label for="created-key">完整密钥</label><textarea id="created-key" ref="secretInput" class="key-secret" :value="secret" readonly spellcheck="false" rows="3"/><button @click="clipboard">复制密钥</button><p aria-live="polite">{{ copied }}</p><div v-if="closing" class="key-warning" role="alert"><p>关闭后完整密钥无法找回。确认已妥善保存？</p><button @click="closing = false">继续保存</button><button class="primary" @click="clearSecret">确认关闭并清除</button></div></div><div class="key-panel-foot"><button @click="closing = true">完成并关闭</button></div>
    </KeyOverlay>
    <KeyOverlay v-if="target" title="撤销此密钥？" :busy="revoking" @close="!revoking && (target = null)"><div class="key-panel-body"><strong class="key-target">{{ target.name }}</strong><p class="code">{{ target.masked }}</p><p class="key-warning">撤销后，使用此密钥的调用方将无法继续读取 Skill。此操作不可恢复。</p><p>如需轮换，请先更新并验证调用方使用的新密钥。</p><p v-if="revokeError" class="alert" role="alert">{{ revokeError }}</p></div><div class="key-panel-foot"><button :disabled="revoking" @click="target = null">取消</button><button class="key-danger" :disabled="revoking || expired" @click="revoke">{{ revoking ? '正在撤销…' : '确认撤销' }}</button></div></KeyOverlay>
  </div>
</template>
