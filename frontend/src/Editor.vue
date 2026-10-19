<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { api, ApiError, copy, date, labels, message, rawUrl, type Skill } from './api'
import { renderMarkdown } from './markdown'
const route = useRoute(), router = useRouter()
const template = '# 使用场景\n\n说明何时使用这个 Skill。\n\n## 操作步骤\n\n1. 检查输入。\n2. 执行任务。\n\n## 输出要求\n\n说明预期输出和注意事项。\n'
const skill = ref<Skill | null>(null), name = ref(''), description = ref(''), content = ref(template)
const saved = ref(JSON.stringify(['', '', template])), busy = ref(false), loading = ref(false), error = ref(''), notice = ref(''), conflict = ref(false)
const tab = ref<'draft' | 'published'>('draft')
let leavingAfterSave = false
const isNew = computed(() => route.path === '/skills/new')
const dirty = computed(() => JSON.stringify([name.value, description.value, content.value]) !== saved.value)
const preview = computed(() => renderMarkdown(tab.value === 'draft' ? content.value : skill.value?.publishedSnapshot?.content ?? ''))
const bytes = computed(() => new TextEncoder().encode(content.value).length)
function accept(s: Skill) {
  skill.value = s; name.value = s.name; description.value = s.description; content.value = s.content
  saved.value = JSON.stringify([s.name, s.description, s.content]); conflict.value = false
}
async function load() {
  if (isNew.value) return
  loading.value = true; error.value = ''
  try { accept(await api<Skill>('/' + route.params.id)) } catch (e) { error.value = message(e) }
  finally { loading.value = false }
}
function report(e: unknown) {
  error.value = message(e)
  if (e instanceof ApiError && e.status === 409 && e.code === 'VERSION_CONFLICT') conflict.value = true
}
function validate(): boolean {
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name.value) || name.value.length > 64) { error.value = 'name 为 1–64 位小写字母、数字和单连字符，不能以连字符开头或结尾'; return false }
  if (!description.value.trim() || description.value.length > 1024) { error.value = '请输入 1–1024 字符的描述'; return false }
  if (!content.value.trim() || bytes.value > 256 * 1024) { error.value = '正文不能为空白且不超过 256 KiB UTF-8 字节'; return false }
  return true
}
async function save() {
  if (!validate() || conflict.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    const wasNew = isNew.value
    const s = wasNew ? await api<Skill>('', 'POST', { name: name.value, description: description.value, content: content.value })
      : await api<Skill>('/' + skill.value!.id, 'PUT', { description: description.value, content: content.value, lockVersion: skill.value!.lockVersion })
    accept(s); notice.value = '草稿已保存，客户端发布内容未改变'
    if (wasNew) { leavingAfterSave = true; await router.replace('/skills/' + s.id); leavingAfterSave = false }
  } catch (e) { report(e) } finally { busy.value = false }
}
async function operation(op: 'publish' | 'unpublish') {
  if (!skill.value || conflict.value) return
  if (dirty.value) { error.value = '存在未保存修改，请先保存草稿后再操作'; return }
  if (!confirm(op === 'publish' ? '发布已保存的草稿？这会立即改变客户端读取内容。' : '确认下架？无法撤销客户端此前下载的副本。')) return
  busy.value = true; error.value = ''; notice.value = ''
  try { accept(await api<Skill>(`/${skill.value.id}/${op}`, 'POST', { lockVersion: skill.value.lockVersion })); notice.value = op === 'publish' ? '已发布' : '已下架' }
  catch (e) { report(e) } finally { busy.value = false }
}
async function refresh() {
  if (!confirm('刷新会丢弃当前表单输入。请先复制保留需要的内容，确认后继续。')) return
  await load()
}
async function clipboard() {
  try { await copy(rawUrl(name.value)); notice.value = '发布地址已复制' } catch { error.value = '自动复制失败，请手动复制下方地址' }
}
function insertTemplate() { if (confirm('用初始模板替换当前正文？')) content.value = template }
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(() => leavingAfterSave || (!busy.value && (!dirty.value || confirm('有未保存修改，确定离开并丢弃修改？'))))
onMounted(() => { load(); window.addEventListener('beforeunload', beforeUnload) })
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>
<template>
  <section class="title"><div><RouterLink to="/">← 返回列表</RouterLink><h1>{{ isNew ? '新建 Skill' : name || '编辑 Skill' }}</h1><p v-if="skill"><span class="badge" :class="skill.status">{{ labels[skill.status] }}</span> · 并发版本 {{ skill.lockVersion }} · 更新 {{ date(skill.updatedAt) }} <strong v-if="dirty"> · 尚未保存</strong></p><p v-else>填写元数据和 Markdown 正文，创建后手动发布。</p></div></section>
  <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="notice" class="success" role="status">{{ notice }}</p>
  <div v-if="conflict" class="alert">已保留你的输入。请先复制需要的内容，再刷新读取最新版本并重新编辑。<button :disabled="busy" @click="refresh">放弃本地输入并刷新</button></div>
  <p v-if="loading" class="empty">正在加载…</p>
  <button v-else-if="!isNew && !skill" @click="load">重试加载</button>
  <template v-else><form class="card form" @submit.prevent="save"><fieldset :disabled="busy"><label>name <small>创建后不可修改</small><input v-model="name" :readonly="!isNew" maxlength="64" required placeholder="java-code-review"></label><label>description <small>用途与适用场景，最多 1024 字符</small><textarea v-model="description" maxlength="1024" rows="3" required></textarea></label>
  <div class="editor-head"><h2>Markdown 正文</h2><span>{{ bytes.toLocaleString() }} / 262,144 字节</span><button type="button" @click="insertTemplate">填入初始模板</button></div>
  <p class="muted">只填写指令正文；服务端自动生成 name、description 的 YAML frontmatter。</p>
  <div class="editor-grid"><label>编辑草稿<textarea v-model="content" class="code" rows="22" spellcheck="false" required></textarea></label><div><div class="tabs"><button type="button" :class="{ selected: tab === 'draft' }" @click="tab = 'draft'">草稿预览</button><button type="button" :disabled="!skill?.publishedSnapshot" :class="{ selected: tab === 'published' }" @click="tab = 'published'">当前发布快照</button></div><template v-if="tab === 'published' && skill?.publishedSnapshot"><p><b>{{ skill.publishedSnapshot.name }}</b> · 发布版本 {{ skill.publishedSnapshot.publishedVersion }}</p><p class="prewrap">{{ skill.publishedSnapshot.description }}</p></template><article class="markdown" v-html="preview"></article></div></div>
  <div class="toolbar"><button class="primary" :disabled="conflict">{{ busy ? '处理中…' : '保存草稿' }}</button><button type="button" :disabled="!skill || conflict" @click="operation('publish')">发布已保存草稿</button><button v-if="skill?.publishedSnapshot" type="button" :disabled="conflict" @click="operation('unpublish')">下架</button></div></fieldset></form>
  <section v-if="skill?.publishedSnapshot" class="card publish-info"><p>发布时间 {{ date(skill.publishedAt) }} · 客户端当前读取发布版本 {{ skill.publishedSnapshot.publishedVersion }}</p><a :href="rawUrl(name)" target="_blank" rel="noopener">{{ rawUrl(name) }}</a> <button :disabled="busy" @click="clipboard">复制地址</button></section></template>
</template>
