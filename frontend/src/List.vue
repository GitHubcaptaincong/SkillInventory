<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, copy, date, labels, message, rawUrl, type Page, type Skill } from './api'
const keyword = ref(''), status = ref(''), page = ref(1), pageSize = ref(20)
const items = ref<Skill[]>([]), total = ref(0), loading = ref(false), busy = ref(false), error = ref(''), notice = ref('')
let sequence = 0
async function load() {
  const seq = ++sequence; loading.value = true; error.value = ''
  try {
    const query = new URLSearchParams({ keyword: keyword.value, page: String(page.value), pageSize: String(pageSize.value) })
    if (status.value) query.set('status', status.value)
    const result = await api<Page<Skill>>('?' + query)
    if (seq === sequence) { items.value = result.items; total.value = result.total }
  } catch (e) { if (seq === sequence) error.value = message(e) }
  finally { if (seq === sequence) loading.value = false }
}
async function action(s: Skill, operation: 'publish' | 'unpublish' | 'delete') {
  const prompt = operation === 'publish' ? '发布已保存的草稿？这会立即改变客户端读取内容。'
    : operation === 'unpublish' ? '确认下架？服务端将停止分发，但不能撤销客户端已下载的副本。' : '确认永久删除此草稿？此操作不可撤销。'
  if (!confirm(prompt)) return
  busy.value = true; error.value = ''; notice.value = ''
  try {
    if (operation === 'delete') await api(`/${s.id}?lockVersion=${s.lockVersion}`, 'DELETE')
    else await api(`/${s.id}/${operation}`, 'POST', { lockVersion: s.lockVersion })
    notice.value = '操作成功'; await load()
  } catch (e) { error.value = message(e) }
  finally { busy.value = false }
}
async function clipboard(value: string) {
  try { await copy(value); notice.value = '已复制' } catch { error.value = '无法自动复制，请手动选择并复制地址或 name' }
}
function search() { page.value = 1; load() }
function changePage(delta: number) { page.value += delta; load() }
onMounted(load)
</script>
<template>
  <section class="title"><div><p class="eyebrow">技能目录</p><h1>管理团队的 Skill</h1><p>先保存草稿，确认后发布。编辑不会改变已发布内容。</p></div><RouterLink class="button primary" to="/skills/new">＋ 新建 Skill</RouterLink></section>
  <p v-if="error" class="alert" role="alert">{{ error }} <button :disabled="loading" @click="load">重新加载</button></p>
  <p v-if="notice" class="success" role="status">{{ notice }}</p>
  <form class="toolbar card" @submit.prevent="search"><input v-model="keyword" maxlength="128" placeholder="搜索 name 或描述" aria-label="搜索关键词"><select v-model="status" aria-label="状态"><option value="">全部状态</option><option v-for="(label, key) in labels" :key="key" :value="key">{{ label }}</option></select><button :disabled="loading || busy">查询</button></form>
  <div class="card table-wrap" :aria-busy="loading"><p v-if="loading" class="empty">正在加载…</p><template v-else><table v-if="items.length"><thead><tr><th>Skill / 描述</th><th>状态</th><th>更新时间 / 发布时间</th><th>操作</th></tr></thead><tbody><tr v-for="s in items" :key="s.id"><td><RouterLink :to="`/skills/${s.id}`" class="name">{{ s.name }}</RouterLink><p class="excerpt">{{ s.description }}</p></td><td><span class="badge" :class="s.status">{{ labels[s.status] }}</span></td><td class="times">{{ date(s.updatedAt) }}<br><span>发布 {{ date(s.publishedAt) }}</span></td><td><div class="actions"><RouterLink :to="`/skills/${s.id}`">编辑</RouterLink><button :disabled="busy" @click="action(s, 'publish')">发布</button><button v-if="s.publishedSnapshot" :disabled="busy" @click="action(s, 'unpublish')">下架</button><button v-else :disabled="busy" class="danger" @click="action(s, 'delete')">删除</button><button :disabled="busy" @click="clipboard(s.name)">复制 name</button><button v-if="s.publishedSnapshot" :disabled="busy" @click="clipboard(rawUrl(s.name))">复制发布地址</button></div><small v-if="s.publishedSnapshot">删除前请先下架</small></td></tr></tbody></table><p v-else class="empty">没有符合条件的 Skill。可以调整筛选或新建一个。</p></template></div>
  <div class="pagination"><span>共 {{ total }} 条匹配记录</span><select v-model="pageSize" aria-label="每页条数" :disabled="loading || busy" @change="search"><option :value="20">20 / 页</option><option :value="50">50 / 页</option><option :value="100">100 / 页</option></select><button :disabled="page <= 1 || loading || busy" @click="changePage(-1)">上一页</button><span>第 {{ page }} 页</span><button :disabled="page * pageSize >= total || page >= 100000 || loading || busy" @click="changePage(1)">下一页</button></div>
</template>
